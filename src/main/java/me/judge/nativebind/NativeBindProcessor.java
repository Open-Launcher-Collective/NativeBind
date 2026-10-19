package me.judge.nativebind;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.JavaFile;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@SupportedAnnotationTypes({"me.judge.nativebind.GenerateNativeWrapper", "me.judge.nativebind.GenerateJNIWrapper"})
@SupportedOptions({NativeBindProcessor.PREFIX_OPTION, NativeBindProcessor.PACKAGE_OPTION,
        NativeBindProcessor.JNI_CLASS_OPTION, NativeBindProcessor.JNI_GENERATE_CLASS_OPTION})
@AutoService(Processor.class)
public class NativeBindProcessor extends AbstractProcessor {
    static final String PREFIX_OPTION = "nativebind.prefix";
    static final String PACKAGE_OPTION = "nativebind.package";
    static final String JNI_CLASS_OPTION = "nativebind.jni.class";
    static final String JNI_GENERATE_CLASS_OPTION = "nativebind.jni.generateClass";

    // Symbols live in one flat C namespace, so uniqueness is enforced across every class and round.
    // Maps each symbol to a description of what exports it.
    private final Map<String, String> exportedSymbols = new HashMap<>();
    // The isolate and handle entry points exist once per flavor (C and JNI), however many classes are wrapped
    private boolean supportGenerated;
    private boolean jniSupportGenerated;
    private final Set<String> generatedJvmClasses = new HashSet<>();
    private String releaseHandleSymbol = "the handle release entry point";
    // Enums passed to or from C, by qualified name, and the prefix their C names take: the support prefix
    private final Set<String> mirroredEnums = new HashSet<>();
    private final Set<String> unmirrorableEnums = new HashSet<>();
    private String enumPrefix = "";
    // The C support package, once generated, and the classes its feature initializes at build time, by binary name
    private String cSupportPackage;
    private final Map<String, TypeElement> buildTimeClasses = new LinkedHashMap<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            writeBuildTimeClasses();
            return false;
        }
        if (annotations.isEmpty()) {
            return false;
        }

        TypeElement wordBase = processingEnv.getElementUtils().getTypeElement(CEntryPointGenerator.WORD_BASE);
        if (wordBase == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "NativeBind requires org.graalvm.sdk:nativeimage on the compile classpath");
            return true;
        }

        Set<? extends Element> cMethods = roundEnv.getElementsAnnotatedWith(GenerateNativeWrapper.class);
        if (!cMethods.isEmpty()) {
            processC(cMethods, wordBase);
        }
        Set<? extends Element> jniMethods = roundEnv.getElementsAnnotatedWith(GenerateJNIWrapper.class);
        if (!jniMethods.isEmpty()) {
            processJNI(jniMethods, wordBase);
        }
        return true;
    }

    private void processC(Set<? extends Element> elements, TypeElement wordBase) {
        String defaultPrefix = processingEnv.getOptions().getOrDefault(PREFIX_OPTION, "");
        if (!CNames.isValidPrefix(defaultPrefix)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "-A" + PREFIX_OPTION + "=" + defaultPrefix
                    + " is not a valid C identifier prefix; use only [A-Za-z0-9_] and do not start with a digit");
            return;
        }
        CEntryPointGenerator generator = new CEntryPointGenerator(processingEnv, wordBase);

        List<Candidate> candidates = new ArrayList<>();
        for (Element element : elements) {
            if (!isWrappable(element, "@GenerateNativeWrapper")) {
                continue;
            }
            GenerateNativeWrapper annotation = element.getAnnotation(GenerateNativeWrapper.class);
            String prefix = annotation.prefix().isEmpty() ? defaultPrefix : annotation.prefix();
            for (Wrapped member : Wrapped.of(element)) {
                try {
                    generator.checkListeners(member);
                } catch (IllegalArgumentException e) {
                    error(element, e.getMessage());
                    continue;
                }
                String symbol = symbolName(member, annotation, prefix);
                if (symbol != null) {
                    candidates.add(new Candidate(member, prefix, symbol, exceptionHandler(annotation)));
                }
            }
        }

        if (!supportGenerated && !candidates.isEmpty()) {
            supportGenerated = true;
            generateSupport(generator, candidates);
        }

        Map<TypeElement, List<CEntryPointGenerator.Entry>> byOwner = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            Wrapped member = candidate.member();
            if (!export(candidate.symbol(), member, "; set @GenerateNativeWrapper(name = ...) to disambiguate")
                    || !mirrorEnums(generator, member)) {
                continue;
            }
            byOwner.computeIfAbsent(member.owner(), k -> new ArrayList<>())
                    .add(new CEntryPointGenerator.Entry(member, candidate.symbol(), candidate.exceptionHandler()));
        }

        byOwner.forEach((owner, entries) -> write(generator.generate(owner, entries, releaseHandleSymbol), owner));
    }

    private record JNICandidate(Wrapped member, String jniClass, JNIEntryPointGenerator.Signature signature,
                                JNIEntryPointGenerator.JvmNative jvmNative) {
    }

    /** {@code enums} are the enums the native method passes, each mocked in its JVM class. */
    private record DeclaredNative(JNIEntryPointGenerator.JvmNative jvmNative, Wrapped member, Set<TypeElement> enums) {
        DeclaredNative(JNIEntryPointGenerator.JvmNative support) {
            this(support, null, Set.of());
        }
    }

    private void processJNI(Set<? extends Element> elements, TypeElement wordBase) {
        if (processingEnv.getElementUtils().getTypeElement(JNIEntryPointGenerator.JNI) == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@GenerateJNIWrapper requires org.graalvm.sdk:jniutils on the compile classpath");
            return;
        }
        JNIEntryPointGenerator generator = new JNIEntryPointGenerator(processingEnv, wordBase);
        Map<String, String> options = processingEnv.getOptions();
        String defaultClass = options.getOrDefault(JNI_CLASS_OPTION, "");
        boolean generateJvmClasses = !"false".equals(options.get(JNI_GENERATE_CLASS_OPTION));

        List<JNICandidate> candidates = new ArrayList<>();
        for (Element element : elements) {
            if (!isWrappable(element, "@GenerateJNIWrapper")) {
                continue;
            }

            GenerateJNIWrapper annotation = element.getAnnotation(GenerateJNIWrapper.class);
            String jniClass = annotation.jniClass().isEmpty() ? defaultClass : annotation.jniClass();
            if (jniClass.isEmpty()) {
                error(element, "set @GenerateJNIWrapper(jniClass = ...) or -A" + JNI_CLASS_OPTION + " to name the JVM class declaring the native method");
                continue;
            }
            if (!JNINames.isValidBinaryName(jniClass)) {
                error(element, "\"" + jniClass + "\" is not a valid class binary name");
                continue;
            }
            for (Wrapped member : Wrapped.of(element)) {
                String name = annotation.name().isEmpty() ? member.defaultName() : member.name(annotation.name());
                if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                    error(element, "\"" + name + "\" is not a valid Java method name");
                    continue;
                }

                JNIEntryPointGenerator.Signature signature;
                try {
                    signature = generator.signature(member, jniClass);
                } catch (IllegalArgumentException e) {
                    error(element, e.getMessage());
                    continue;
                }
                String enumProblem = generator.mockedEnums(signature).stream()
                        .map(NativeBindProcessor::enumProblem)
                        .filter(p -> p != null)
                        .findFirst().orElse(null);
                if (enumProblem != null) {
                    error(element, enumProblem);
                    continue;
                }
                candidates.add(new JNICandidate(member, jniClass, signature, generator.jvmNative(name, member, signature)));
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        // Every native method each JVM class declares, with the wrapped member it calls (null for support)
        Map<String, List<DeclaredNative>> byJvmClass = new LinkedHashMap<>();
        for (JNICandidate candidate : candidates) {
            byJvmClass.computeIfAbsent(candidate.jniClass(), k -> new ArrayList<>())
                    .add(new DeclaredNative(candidate.jvmNative(), candidate.member(), generator.mockedEnums(candidate.signature())));
        }

        Set<TypeElement> owners = new LinkedHashSet<>();
        candidates.forEach(c -> owners.add(c.member().owner()));

        String supportJvmClass = null;
        String supportPackage = null;
        if (!jniSupportGenerated) {
            jniSupportGenerated = true;
            supportJvmClass = options.get(JNI_CLASS_OPTION);
            if (supportJvmClass == null) {
                Set<String> classes = new TreeSet<>(byJvmClass.keySet());
                if (classes.size() > 1) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@GenerateJNIWrapper methods use "
                            + "different JVM classes " + classes + "; set -A" + JNI_CLASS_OPTION + " to choose the class "
                            + "declaring the shared isolate and handle native methods");
                } else {
                    supportJvmClass = classes.iterator().next();
                }
            } else if (!JNINames.isValidBinaryName(supportJvmClass)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "-A" + JNI_CLASS_OPTION + "=" + supportJvmClass + " is not a valid class binary name");
                supportJvmClass = null;
            }
            supportPackage = supportPackage(owners);
            if (supportJvmClass != null && supportPackage != null) {
                List<DeclaredNative> natives = new ArrayList<>();
                JNIEntryPointGenerator.supportNatives().forEach(n -> natives.add(new DeclaredNative(n)));
                JNIEntryPointGenerator.threadNatives().forEach(n -> natives.add(new DeclaredNative(n)));
                JNIEntryPointGenerator.builtinNatives().forEach(n -> natives.add(new DeclaredNative(n)));
                natives.addAll(byJvmClass.getOrDefault(supportJvmClass, List.of()));
                byJvmClass.put(supportJvmClass, natives);
            } else {
                supportJvmClass = null;
            }
        }

        Map<Wrapped, String> symbols = new HashMap<>();
        Map<String, String> supportSymbols = new HashMap<>();
        // Support natives a hand-written class may leave out
        Set<String> optionalNames = new HashSet<>();
        JNIEntryPointGenerator.threadNatives().forEach(n -> optionalNames.add(n.name()));
        JNIEntryPointGenerator.builtinNatives().forEach(n -> optionalNames.add(n.name()));
        String supportClass = supportJvmClass;
        byJvmClass.forEach((jvmClass, natives) -> {
            TypeElement existing = generateJvmClasses ? null : processingEnv.getElementUtils().getTypeElement(jvmClass.replace('$', '.'));
            Element anyOrigin = natives.stream().map(DeclaredNative::member).filter(m -> m != null).map(Wrapped::element).findFirst().orElse(null);
            if (generateJvmClasses && !generatedJvmClasses.add(jvmClass)) {
                error(anyOrigin, jvmClass + " was already generated in an earlier round; its native methods must all be known in one round");
                return;
            }
            if (generateJvmClasses && jvmClass.contains("$")) {
                error(anyOrigin, "NativeBind cannot generate nested class " + jvmClass + "; declare it yourself and set -A"
                        + JNI_GENERATE_CLASS_OPTION + "=false");
                return;
            }
            if (!generateJvmClasses && existing == null) {
                error(anyOrigin, jvmClass + " was not found; declare it or remove -A" + JNI_GENERATE_CLASS_OPTION + "=false");
                return;
            }

            Map<String, Set<String>> existingDescriptors = existing == null ? Map.of() : generator.nativeDescriptors(existing);
            Map<String, Set<String>> descriptorsByName = new HashMap<>();
            natives.forEach(n -> descriptorsByName.computeIfAbsent(n.jvmNative().name(), k -> new HashSet<>()).add(n.jvmNative().descriptor()));
            existingDescriptors.forEach((name, descriptors) ->
                    descriptorsByName.computeIfAbsent(name, k -> new HashSet<>()).addAll(descriptors));

            Set<String> seen = new HashSet<>();
            List<JNIEntryPointGenerator.JvmNative> accepted = new ArrayList<>();
            // Mock enums by simple name, which two enums nested in differently named packages could share
            Map<String, TypeElement> mocks = new LinkedHashMap<>();
            for (DeclaredNative declared : natives) {
                JNIEntryPointGenerator.JvmNative jvmNative = declared.jvmNative();
                Wrapped member = declared.member();
                Element origin = member != null ? member.element() : anyOrigin;
                if (!seen.add(jvmNative.name() + jvmNative.descriptor())) {
                    error(origin, jvmClass + "." + jvmNative.name() + jvmNative.descriptor() + " is declared more than once; "
                            + "set @GenerateJNIWrapper(name = ...) to disambiguate");
                    continue;
                }
                if (member != null && generateJvmClasses && jvmClass.equals(supportClass)
                        && JNIEntryPointGenerator.JVM_HELPERS.contains(jvmNative.name())) {
                    error(origin, jvmClass + "." + jvmNative.name() + " is reserved for isolate thread management; "
                            + "set @GenerateJNIWrapper(name = ...) to rename it");
                    continue;
                }
                TypeElement clash = declared.enums().stream()
                        .filter(e -> mocks.containsKey(mockSimpleName(jvmClass, e)) && !mocks.get(mockSimpleName(jvmClass, e)).equals(e))
                        .findFirst().orElse(null);
                if (clash != null) {
                    error(origin, jvmClass + " cannot mock both " + mocks.get(mockSimpleName(jvmClass, clash)).getQualifiedName()
                            + " and " + clash.getQualifiedName() + " as enum " + mockSimpleName(jvmClass, clash)
                            + "; bind one of them to another JVM class");
                    continue;
                }
                declared.enums().forEach(e -> mocks.put(mockSimpleName(jvmClass, e), e));
                accepted.add(jvmNative);
                String symbol = descriptorsByName.get(jvmNative.name()).size() > 1
                        ? JNINames.longName(jvmClass, jvmNative.name(), jvmNative.argumentDescriptor())
                        : JNINames.shortName(jvmClass, jvmNative.name());
                if (member == null) {
                    supportSymbols.put(jvmNative.name(), symbol);
                    exportedSymbols.put(symbol, JNIEntryPointGenerator.SUPPORT_CLASS);
                } else if (export(symbol, member, "; set @GenerateJNIWrapper(name = ...) to disambiguate")) {
                    symbols.put(member, symbol);
                }
                // A hand-written class only needs the builtins it wants to call
                boolean optional = member == null && optionalNames.contains(jvmNative.name())
                        && !existingDescriptors.containsKey(jvmNative.name());
                if (existing != null && !optional) {
                    String problem = generator.verify(existing, jvmNative);
                    if (problem != null) {
                        error(origin, problem);
                    }
                }
            }

            if (existing == null) {
                write(generator.generateJvmClass(jvmClass, accepted, jvmClass.equals(supportClass), mocks.values(), owners), anyOrigin);
            } else {
                for (TypeElement javaEnum : mocks.values()) {
                    String problem = generator.verifyMock(existing, javaEnum);
                    if (problem != null) {
                        error(anyOrigin, problem);
                    }
                }
            }
        });

        if (supportJvmClass != null && supportSymbols.size() == JNIEntryPointGenerator.supportNatives().size() + optionalNames.size()) {
            JNIEntryPointGenerator.SupportSymbols support = new JNIEntryPointGenerator.SupportSymbols(
                    supportSymbols.get("createIsolate"), supportSymbols.get("tearDownIsolate"), supportSymbols.get("releaseHandle"),
                    supportSymbols.get("attachThread"), supportSymbols.get("detachThread"), supportSymbols.get("getIsolate"),
                    supportSymbols);
            write(generator.generateSupport(supportPackage, support, owners), null);
            write(generator.generateFeature(supportPackage, owners), null);
            write(generator.generateBridgeSupport(supportPackage, owners), null);
            writeResource("META-INF/native-image/nativebind/" + (supportPackage.isEmpty() ? "default" : supportPackage)
                    + "/native-image.properties", JNIEntryPointGenerator.nativeImageProperties(supportPackage), owners);
        }

        Map<TypeElement, List<JNIEntryPointGenerator.Entry>> byOwner = new LinkedHashMap<>();
        for (JNICandidate candidate : candidates) {
            String symbol = symbols.get(candidate.member());
            if (symbol != null) {
                byOwner.computeIfAbsent(candidate.member().owner(), k -> new ArrayList<>())
                        .add(new JNIEntryPointGenerator.Entry(candidate.member(), symbol, candidate.signature()));
            }
        }
        byOwner.forEach((owner, entries) -> write(generator.generate(owner, entries), owner));
    }

    /** Claims a symbol for {@code member}, reporting an error if something else already exports it. */
    private boolean export(String symbol, Wrapped member, String hint) {
        String previous = exportedSymbols.putIfAbsent(symbol, member.description());
        if (previous != null) {
            error(member.element(), "symbol \"" + symbol + "\" is already exported by " + previous + hint);
            return false;
        }
        return true;
    }

    /**
     * Generates the {@code @CEnum} mirror of each enum {@code member} passes to or from C, unless an earlier member
     * already did. Returns false, after reporting why, if any of them cannot be mirrored.
     */
    private boolean mirrorEnums(CEntryPointGenerator generator, Wrapped member) {
        boolean ok = true;
        for (TypeElement javaEnum : generator.mirroredEnums(member)) {
            String name = javaEnum.getQualifiedName().toString();
            if (mirroredEnums.contains(name)) {
                continue;
            }
            if (unmirrorableEnums.contains(name) || !mirrorEnum(generator, javaEnum, member.element())) {
                unmirrorableEnums.add(name);
                ok = false;
                continue;
            }
            mirroredEnums.add(name);
        }
        return ok;
    }

    private static String mockSimpleName(String jvmClass, TypeElement javaEnum) {
        return JNIEntryPointGenerator.mockName(jvmClass, javaEnum).simpleName();
    }

    /** Why generated code in another class cannot name {@code javaEnum}, or null if it can. */
    private static String enumProblem(TypeElement javaEnum) {
        String description = "enum " + javaEnum.getQualifiedName();
        for (Element e = javaEnum; e instanceof TypeElement type; e = type.getEnclosingElement()) {
            if (type.getNestingKind() != NestingKind.TOP_LEVEL && type.getNestingKind() != NestingKind.MEMBER) {
                return description + " cannot be passed to native code: it must be a top-level or member type";
            }
            if (type.getModifiers().contains(Modifier.PRIVATE)) {
                return description + " cannot be passed to native code: " + type + " is private";
            }
        }
        return null;
    }

    /**
     * Names {@code javaEnum} in C as the library prefix followed by its (flattened) simple name, e.g. {@code libStatus},
     * and each constant as that name in upper snake case followed by the constant's, e.g. {@code LIB_STATUS_OK}.
     */
    private boolean mirrorEnum(CEntryPointGenerator generator, TypeElement javaEnum, Element origin) {
        String description = "enum " + javaEnum.getQualifiedName();
        String problem = enumProblem(javaEnum);
        if (problem != null) {
            error(origin, problem);
            return false;
        }

        String cName = CNames.join(enumPrefix, CNames.sanitize(CEntryPointGenerator.flatName(javaEnum, "")));
        if (!CNames.isValidIdentifier(cName)) {
            error(origin, description + " cannot be passed to C: \"" + cName + "\" is not a valid C identifier");
            return false;
        }
        Map<String, String> constants = new LinkedHashMap<>();
        Set<String> cConstants = new HashSet<>();
        for (Element enclosed : javaEnum.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.ENUM_CONSTANT) {
                continue;
            }
            String javaConstant = enclosed.getSimpleName().toString();
            String cConstant = CNames.upperSnake(cName) + "_" + CNames.upperSnake(CNames.sanitize(javaConstant));
            if (!cConstants.add(cConstant)) {
                error(origin, description + " cannot be passed to C: more than one constant would be named " + cConstant);
                return false;
            }
            constants.put(javaConstant, cConstant);
        }
        if (constants.isEmpty()) {
            error(origin, description + " cannot be passed to C: a C enumeration needs at least one constant");
            return false;
        }

        // Type and constant names share the C namespace with the exported functions
        List<String> names = new ArrayList<>(List.of(cName));
        names.addAll(constants.values());
        for (String name : names) {
            String previous = exportedSymbols.putIfAbsent(name, description);
            if (previous != null) {
                error(origin, description + " cannot be passed to C: \"" + name + "\" is already exported by " + previous);
                return false;
            }
        }

        write(generator.generateEnum(javaEnum, cName, constants), origin);
        // native-image initializes the mirror at build time, and with it the enum its static initializer reads
        buildTimeClasses.put(CEntryPointGenerator.mirrorName(javaEnum).reflectionName(), javaEnum);
        buildTimeClasses.put(processingEnv.getElementUtils().getBinaryName(javaEnum).toString(), javaEnum);
        writeResource(CEntryPointGenerator.ENUM_HEADER_DIRECTORY + cName + ".h",
                CEntryPointGenerator.enumHeader(javaEnum, cName, constants), Set.of(javaEnum));
        return true;
    }

    private record Candidate(Wrapped member, String prefix, String symbol, TypeMirror exceptionHandler) {
    }

    /**
     * Emits the shared create/teardown/release entry points. Their prefix is {@code -Anativebind.prefix}, or the
     * single prefix every wrapper uses; their package is {@code -Anativebind.package}, or the common package of
     * every wrapped class.
     */
    private void generateSupport(CEntryPointGenerator generator, List<Candidate> candidates) {
        Map<String, String> options = processingEnv.getOptions();

        String prefix = options.get(PREFIX_OPTION);
        if (prefix == null) {
            Set<String> prefixes = new TreeSet<>();
            candidates.forEach(c -> prefixes.add(c.prefix()));
            if (prefixes.size() > 1) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@GenerateNativeWrapper methods use "
                        + "different prefixes " + prefixes + "; set -A" + PREFIX_OPTION + " to choose the prefix of the "
                        + "shared isolate and handle entry points");
                return;
            }
            prefix = prefixes.iterator().next();
        }
        enumPrefix = prefix;

        Set<TypeElement> owners = new LinkedHashSet<>();
        candidates.forEach(c -> owners.add(c.member().owner()));

        String packageName = supportPackage(owners);
        if (packageName == null) {
            return;
        }

        // Only copy @CHeader when every wrapped class agrees on it; otherwise the choice would be arbitrary
        Map<String, AnnotationMirror> headers = new LinkedHashMap<>();
        for (TypeElement owner : owners) {
            AnnotationMirror header = generator.findHeader(owner);
            if (header != null) {
                headers.putIfAbsent(header.toString(), header);
            }
        }
        if (headers.size() > 1) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "Wrapped classes use different @CHeader "
                    + "values " + headers.keySet() + "; " + CEntryPointGenerator.SUPPORT_CLASS + " will not be assigned one");
        }
        AnnotationMirror header = headers.size() == 1 ? headers.values().iterator().next() : null;

        Map<String, String> builtins = new LinkedHashMap<>();
        for (String name : CEntryPointGenerator.builtinNames()) {
            builtins.put(name, CNames.join(prefix, name));
        }
        CEntryPointGenerator.SupportSymbols symbols = new CEntryPointGenerator.SupportSymbols(
                CNames.join(prefix, "createIsolate"),
                CNames.join(prefix, "tearDownIsolate"),
                CNames.join(prefix, "releaseHandle"),
                builtins);
        List<String> supportSymbols = new ArrayList<>(List.of(symbols.createIsolate(), symbols.tearDownIsolate(), symbols.releaseHandle()));
        supportSymbols.addAll(builtins.values());
        for (String symbol : supportSymbols) {
            exportedSymbols.put(symbol, packageName.isEmpty()
                    ? CEntryPointGenerator.SUPPORT_CLASS
                    : packageName + "." + CEntryPointGenerator.SUPPORT_CLASS);
        }

        releaseHandleSymbol = symbols.releaseHandle();
        write(generator.generateSupport(packageName, header, symbols, owners), null);
        write(generator.generateFeature(packageName, owners), null);
        writeResource("META-INF/native-image/nativebind/" + (packageName.isEmpty() ? "default" : packageName)
                + "/c/native-image.properties", CEntryPointGenerator.featureProperties(packageName), owners);
        cSupportPackage = packageName;
    }

    /** Lists every class the C feature initializes at build time, now that no further round can add one. */
    private void writeBuildTimeClasses() {
        if (cSupportPackage == null || buildTimeClasses.isEmpty()) {
            return;
        }
        StringBuilder names = new StringBuilder();
        buildTimeClasses.keySet().forEach(name -> names.append(name).append('\n'));
        writeResource(CEntryPointGenerator.buildTimeClassesResource(cSupportPackage), names.toString(),
                new LinkedHashSet<>(buildTimeClasses.values()));
    }

    /** {@code -Anativebind.package}, or the common package of every wrapped class; null after reporting an invalid option. */
    private String supportPackage(Set<TypeElement> owners) {
        String packageName = processingEnv.getOptions().get(PACKAGE_OPTION);
        if (packageName == null) {
            return commonPackage(owners);
        }
        if (!packageName.isEmpty() && !SourceVersion.isName(packageName)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "-A" + PACKAGE_OPTION + "=" + packageName + " is not a valid package name");
            return null;
        }
        return packageName;
    }

    private static String commonPackage(Set<TypeElement> owners) {
        String common = null;
        for (TypeElement owner : owners) {
            Element e = owner;
            while (!(e instanceof PackageElement)) {
                e = e.getEnclosingElement();
            }
            String name = ((PackageElement) e).getQualifiedName().toString();
            if (common == null) {
                common = name;
            }
            while (!common.isEmpty() && !(name.equals(common) || name.startsWith(common + "."))) {
                int dot = common.lastIndexOf('.');
                common = dot < 0 ? "" : common.substring(0, dot);
            }
        }
        return common == null ? "" : common;
    }

    private void write(JavaFile file, Element element) {
        try {
            file.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to write " + file.packageName() + "."
                    + file.typeSpec().name() + ": " + e.getMessage(), element);
        }
    }

    private void writeResource(String path, String content, Set<? extends Element> origins) {
        try (Writer writer = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", path, origins.toArray(new Element[0]))
                .openWriter()) {
            writer.write(content);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to write " + path + ": " + e.getMessage());
        }
    }

    /** The generated class sits in the owner's package and must be able to reach the member from there. */
    private boolean isWrappable(Element member, String annotation) {
        boolean ok = true;
        if (member.getModifiers().contains(Modifier.PRIVATE)) {
            error(member, annotation + " members must not be private");
            ok = false;
        }
        if (member instanceof ExecutableElement method && !method.getTypeParameters().isEmpty()) {
            error(member, annotation + " does not support generic methods");
            ok = false;
        }
        for (Element e = member.getEnclosingElement(); e instanceof TypeElement type; e = type.getEnclosingElement()) {
            if (type.getNestingKind() != NestingKind.TOP_LEVEL && type.getNestingKind() != NestingKind.MEMBER) {
                error(member, annotation + " members must be declared in a top-level or member class");
                return false;
            }
            if (type.getModifiers().contains(Modifier.PRIVATE)) {
                error(member, annotation + " members must not be enclosed in a private class (" + type + ")");
                ok = false;
            }
        }
        ElementKind kind = member.getKind();
        if (kind != ElementKind.METHOD && kind != ElementKind.CONSTRUCTOR && kind != ElementKind.FIELD && kind != ElementKind.ENUM_CONSTANT) {
            error(member, annotation + " only applies to methods, constructors and fields");
            ok = false;
        }
        if (kind == ElementKind.CONSTRUCTOR) {
            // The generated factory calls "new Owner(...)", which needs a concrete class and no enclosing instance
            TypeElement owner = (TypeElement) member.getEnclosingElement();
            if (owner.getKind() == ElementKind.ENUM || owner.getModifiers().contains(Modifier.ABSTRACT)) {
                error(member, annotation + " constructors must belong to a class that can be instantiated");
                ok = false;
            }
            if (owner.getNestingKind() == NestingKind.MEMBER && !owner.getModifiers().contains(Modifier.STATIC)) {
                error(member, annotation + " constructors must not belong to an inner (non-static) class");
                ok = false;
            }
        }
        return ok;
    }

    /** Builds the exported name, or reports an error and returns null when it cannot be a valid C identifier. */
    private String symbolName(Wrapped member, GenerateNativeWrapper annotation, String prefix) {
        Element element = member.element();
        if (!CNames.isValidPrefix(prefix)) {
            error(element, "prefix \"" + prefix + "\" is not a valid C identifier prefix; use only [A-Za-z0-9_] and do not start with a digit");
            return null;
        }

        String base;
        if (annotation.name().isEmpty()) {
            base = CNames.sanitize(member.defaultName());
        } else if (annotation.name().chars().allMatch(c -> c == '_' || (c < 128 && Character.isLetterOrDigit(c)))) {
            base = member.name(annotation.name());
        } else {
            error(element, "name \"" + annotation.name() + "\" may only contain [A-Za-z0-9_]");
            return null;
        }

        String symbol = CNames.join(prefix, base);
        if (!CNames.isValidIdentifier(symbol)) {
            error(element, "\"" + symbol + "\" is not a valid C identifier; set a prefix or name");
            return null;
        }
        if (CNames.isReserved(symbol)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "\"" + symbol + "\" is reserved for the C implementation (leading __ or _ followed by an uppercase letter)", element);
        }
        return symbol;
    }

    private static TypeMirror exceptionHandler(GenerateNativeWrapper annotation) {
        try {
            annotation.exceptionHandler();
            throw new AssertionError("Class-valued annotation members are only reachable as mirrors during processing");
        } catch (MirroredTypeException e) {
            return e.getTypeMirror().getKind() == TypeKind.VOID ? null : e.getTypeMirror();
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}

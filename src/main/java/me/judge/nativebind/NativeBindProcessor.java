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

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
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
            ExecutableElement method = (ExecutableElement) element;
            if (!isWrappable(method, "@GenerateNativeWrapper")) {
                continue;
            }

            GenerateNativeWrapper annotation = method.getAnnotation(GenerateNativeWrapper.class);
            String prefix = annotation.prefix().isEmpty() ? defaultPrefix : annotation.prefix();
            String symbol = symbolName(method, annotation, prefix);
            if (symbol != null) {
                candidates.add(new Candidate(method, prefix, symbol, exceptionHandler(annotation)));
            }
        }

        if (!supportGenerated && !candidates.isEmpty()) {
            supportGenerated = true;
            generateSupport(generator, candidates);
        }

        Map<TypeElement, List<CEntryPointGenerator.Entry>> byOwner = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            ExecutableElement method = candidate.method();
            if (!export(candidate.symbol(), method, "; set @GenerateNativeWrapper(name = ...) to disambiguate")) {
                continue;
            }
            byOwner.computeIfAbsent((TypeElement) method.getEnclosingElement(), k -> new ArrayList<>())
                    .add(new CEntryPointGenerator.Entry(method, candidate.symbol(), candidate.exceptionHandler()));
        }

        byOwner.forEach((owner, entries) -> write(generator.generate(owner, entries, releaseHandleSymbol), owner));
    }

    private record JNICandidate(ExecutableElement method, String jniClass, JNIEntryPointGenerator.Signature signature,
                                JNIEntryPointGenerator.JvmNative jvmNative) {
    }

    private record DeclaredNative(JNIEntryPointGenerator.JvmNative jvmNative, ExecutableElement method) {
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
            ExecutableElement method = (ExecutableElement) element;
            if (!isWrappable(method, "@GenerateJNIWrapper")) {
                continue;
            }

            GenerateJNIWrapper annotation = method.getAnnotation(GenerateJNIWrapper.class);
            String jniClass = annotation.jniClass().isEmpty() ? defaultClass : annotation.jniClass();
            if (jniClass.isEmpty()) {
                error(method, "set @GenerateJNIWrapper(jniClass = ...) or -A" + JNI_CLASS_OPTION + " to name the JVM class declaring the native method");
                continue;
            }
            if (!JNINames.isValidBinaryName(jniClass)) {
                error(method, "\"" + jniClass + "\" is not a valid class binary name");
                continue;
            }
            String name = annotation.name().isEmpty() ? Wrapped.defaultName(method) : annotation.name();
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                error(method, "\"" + name + "\" is not a valid Java method name");
                continue;
            }

            JNIEntryPointGenerator.Signature signature;
            try {
                signature = generator.signature(method);
            } catch (IllegalArgumentException e) {
                error(method, e.getMessage());
                continue;
            }
            candidates.add(new JNICandidate(method, jniClass, signature, generator.jvmNative(name, method, signature)));
        }
        if (candidates.isEmpty()) {
            return;
        }

        // Every native method each JVM class declares, with the annotated method it calls (null for support)
        Map<String, List<DeclaredNative>> byJvmClass = new LinkedHashMap<>();
        for (JNICandidate candidate : candidates) {
            byJvmClass.computeIfAbsent(candidate.jniClass(), k -> new ArrayList<>())
                    .add(new DeclaredNative(candidate.jvmNative(), candidate.method()));
        }

        Set<TypeElement> owners = new LinkedHashSet<>();
        candidates.forEach(c -> owners.add((TypeElement) c.method().getEnclosingElement()));

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
                JNIEntryPointGenerator.supportNatives().forEach(n -> natives.add(new DeclaredNative(n, null)));
                JNIEntryPointGenerator.builtinNatives().forEach(n -> natives.add(new DeclaredNative(n, null)));
                natives.addAll(byJvmClass.getOrDefault(supportJvmClass, List.of()));
                byJvmClass.put(supportJvmClass, natives);
            } else {
                supportJvmClass = null;
            }
        }

        Map<ExecutableElement, String> symbols = new HashMap<>();
        Map<String, String> supportSymbols = new HashMap<>();
        Set<String> builtinNames = new HashSet<>();
        JNIEntryPointGenerator.builtinNatives().forEach(n -> builtinNames.add(n.name()));
        byJvmClass.forEach((jvmClass, natives) -> {
            TypeElement existing = generateJvmClasses ? null : processingEnv.getElementUtils().getTypeElement(jvmClass.replace('$', '.'));
            Element anyOrigin = natives.stream().map(DeclaredNative::method).filter(m -> m != null).findFirst().orElse(null);
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
            for (DeclaredNative declared : natives) {
                JNIEntryPointGenerator.JvmNative jvmNative = declared.jvmNative();
                ExecutableElement method = declared.method();
                Element origin = method != null ? method : anyOrigin;
                if (!seen.add(jvmNative.name() + jvmNative.descriptor())) {
                    error(origin, jvmClass + "." + jvmNative.name() + jvmNative.descriptor() + " is declared more than once; "
                            + "set @GenerateJNIWrapper(name = ...) to disambiguate");
                    continue;
                }
                accepted.add(jvmNative);
                String symbol = descriptorsByName.get(jvmNative.name()).size() > 1
                        ? JNINames.longName(jvmClass, jvmNative.name(), jvmNative.argumentDescriptor())
                        : JNINames.shortName(jvmClass, jvmNative.name());
                if (method == null) {
                    supportSymbols.put(jvmNative.name(), symbol);
                    exportedSymbols.put(symbol, JNIEntryPointGenerator.SUPPORT_CLASS);
                } else if (export(symbol, method, "; set @GenerateJNIWrapper(name = ...) to disambiguate")) {
                    symbols.put(method, symbol);
                }
                // A hand-written class only needs the builtins it wants to call
                boolean optional = method == null && builtinNames.contains(jvmNative.name())
                        && !existingDescriptors.containsKey(jvmNative.name());
                if (existing != null && !optional) {
                    String problem = generator.verify(existing, jvmNative);
                    if (problem != null) {
                        error(origin, problem);
                    }
                }
            }

            if (existing == null) {
                write(generator.generateJvmClass(jvmClass, accepted, owners), anyOrigin);
            }
        });

        if (supportJvmClass != null && supportSymbols.size() == JNIEntryPointGenerator.supportNatives().size() + builtinNames.size()) {
            JNIEntryPointGenerator.SupportSymbols support = new JNIEntryPointGenerator.SupportSymbols(
                    supportSymbols.get("createIsolate"), supportSymbols.get("tearDownIsolate"), supportSymbols.get("releaseHandle"),
                    supportSymbols);
            write(generator.generateSupport(supportPackage, support, owners), null);
            write(generator.generateFeature(supportPackage, owners), null);
            write(generator.generateBridgeSupport(supportPackage, owners), null);
            writeResource("META-INF/native-image/nativebind/" + (supportPackage.isEmpty() ? "default" : supportPackage)
                    + "/native-image.properties", JNIEntryPointGenerator.nativeImageProperties(supportPackage), owners);
        }

        Map<TypeElement, List<JNIEntryPointGenerator.Entry>> byOwner = new LinkedHashMap<>();
        for (JNICandidate candidate : candidates) {
            String symbol = symbols.get(candidate.method());
            if (symbol != null) {
                byOwner.computeIfAbsent((TypeElement) candidate.method().getEnclosingElement(), k -> new ArrayList<>())
                        .add(new JNIEntryPointGenerator.Entry(candidate.method(), symbol, candidate.signature()));
            }
        }
        byOwner.forEach((owner, entries) -> write(generator.generate(owner, entries), owner));
    }

    /** Claims a symbol for {@code method}, reporting an error if something else already exports it. */
    private boolean export(String symbol, ExecutableElement method, String hint) {
        String previous = exportedSymbols.putIfAbsent(symbol, method.getEnclosingElement() + "." + method);
        if (previous != null) {
            error(method, "symbol \"" + symbol + "\" is already exported by " + previous + hint);
            return false;
        }
        return true;
    }

    private record Candidate(ExecutableElement method, String prefix, String symbol, TypeMirror exceptionHandler) {
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

        Set<TypeElement> owners = new LinkedHashSet<>();
        candidates.forEach(c -> owners.add((TypeElement) c.method().getEnclosingElement()));

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

    /** The generated class sits in the owner's package and must be able to call the method from there. */
    private boolean isWrappable(ExecutableElement method, String annotation) {
        boolean ok = true;
        if (method.getModifiers().contains(Modifier.PRIVATE)) {
            error(method, annotation + " methods must not be private");
            ok = false;
        }
        if (!method.getTypeParameters().isEmpty()) {
            error(method, annotation + " does not support generic methods");
            ok = false;
        }
        for (Element e = method.getEnclosingElement(); e instanceof TypeElement type; e = type.getEnclosingElement()) {
            if (type.getNestingKind() != NestingKind.TOP_LEVEL && type.getNestingKind() != NestingKind.MEMBER) {
                error(method, annotation + " methods must be declared in a top-level or member class");
                return false;
            }
            if (type.getModifiers().contains(Modifier.PRIVATE)) {
                error(method, annotation + " methods must not be enclosed in a private class (" + type + ")");
                ok = false;
            }
        }
        if (method.getKind() != ElementKind.METHOD && method.getKind() != ElementKind.CONSTRUCTOR) {
            error(method, annotation + " only applies to methods and constructors");
            ok = false;
        }
        if (Wrapped.isConstructor(method)) {
            // The generated factory calls "new Owner(...)", which needs a concrete class and no enclosing instance
            TypeElement owner = (TypeElement) method.getEnclosingElement();
            if (owner.getKind() == ElementKind.ENUM || owner.getModifiers().contains(Modifier.ABSTRACT)) {
                error(method, annotation + " constructors must belong to a class that can be instantiated");
                ok = false;
            }
            if (owner.getNestingKind() == NestingKind.MEMBER && !owner.getModifiers().contains(Modifier.STATIC)) {
                error(method, annotation + " constructors must not belong to an inner (non-static) class");
                ok = false;
            }
        }
        return ok;
    }

    /** Builds the exported name, or reports an error and returns null when it cannot be a valid C identifier. */
    private String symbolName(ExecutableElement method, GenerateNativeWrapper annotation, String prefix) {
        if (!CNames.isValidPrefix(prefix)) {
            error(method, "prefix \"" + prefix + "\" is not a valid C identifier prefix; use only [A-Za-z0-9_] and do not start with a digit");
            return null;
        }

        String base;
        if (annotation.name().isEmpty()) {
            base = CNames.sanitize(Wrapped.defaultName(method));
        } else if (annotation.name().chars().allMatch(c -> c == '_' || (c < 128 && Character.isLetterOrDigit(c)))) {
            base = annotation.name();
        } else {
            error(method, "name \"" + annotation.name() + "\" may only contain [A-Za-z0-9_]");
            return null;
        }

        String symbol = CNames.join(prefix, base);
        if (!CNames.isValidIdentifier(symbol)) {
            error(method, "\"" + symbol + "\" is not a valid C identifier; set a prefix or name");
            return null;
        }
        if (CNames.isReserved(symbol)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "\"" + symbol + "\" is reserved for the C implementation (leading __ or _ followed by an uppercase letter)", method);
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

package me.judge.nativebind;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;

/**
 * Generates {@code @CEntryPoint} wrappers that take and return plain C types, in the style of a hand-written
 * {@code IsolateThread}-first entry point class.
 */
final class CEntryPointGenerator {
    static final String CLASS_SUFFIX = "CEntryPoints";
    static final String SUPPORT_CLASS = "NativeBindCEntryPoints";
    static final String FEATURE_CLASS = "NativeBindCFeature";
    private static final ClassName FEATURE = ClassName.get("org.graalvm.nativeimage.hosted", "Feature");
    private static final ClassName RUNTIME_CLASS_INITIALIZATION = ClassName.get("org.graalvm.nativeimage.hosted", "RuntimeClassInitialization");

    private static final ClassName C_ENTRY_POINT = ClassName.get("org.graalvm.nativeimage.c.function", "CEntryPoint");
    private static final ClassName C_ENTRY_POINT_BUILTIN = C_ENTRY_POINT.nestedClass("Builtin");
    private static final ClassName ISOLATE_THREAD_CONTEXT = C_ENTRY_POINT.nestedClass("IsolateThreadContext");
    private static final ClassName ISOLATE_THREAD = ClassName.get("org.graalvm.nativeimage", "IsolateThread");
    private static final ClassName OBJECT_HANDLE = ClassName.get("org.graalvm.nativeimage", "ObjectHandle");
    private static final ClassName OBJECT_HANDLES = ClassName.get("org.graalvm.nativeimage", "ObjectHandles");
    private static final ClassName UNMANAGED_MEMORY = ClassName.get("org.graalvm.nativeimage", "UnmanagedMemory");
    private static final ClassName C_CHAR_POINTER = ClassName.get("org.graalvm.nativeimage.c.type", "CCharPointer");
    private static final ClassName C_TYPE_CONVERSION = ClassName.get("org.graalvm.nativeimage.c.type", "CTypeConversion");
    private static final ClassName WORD_FACTORY = ClassName.get("org.graalvm.word", "WordFactory");
    private static final String C_HEADER = "org.graalvm.nativeimage.c.CHeader";
    static final String WORD_BASE = "org.graalvm.word.WordBase";

    private static final String TO_JAVA_STRING = "nativeBind$toJavaString";
    private static final String TO_C_STRING = "nativeBind$toCString";
    private static final String FROM_HANDLE = "nativeBind$fromHandle";
    private static final String TO_HANDLE = "nativeBind$toHandle";
    static final String FUTURE_ON_COMPLETE = "futureOnComplete";
    private static final String FUTURE_CALLBACK = "FutureCallback";
    private static final ClassName C_FUNCTION_POINTER = ClassName.get("org.graalvm.nativeimage.c.function", "CFunctionPointer");
    private static final ClassName INVOKE_C_FUNCTION_POINTER = ClassName.get("org.graalvm.nativeimage.c.function", "InvokeCFunctionPointer");
    private static final ClassName VOID_POINTER = ClassName.get("org.graalvm.nativeimage.c.type", "VoidPointer");
    private static final ClassName CURRENT_ISOLATE = ClassName.get("org.graalvm.nativeimage", "CurrentIsolate");
    private static final String LISTENER = "Listener";
    private static final String LISTENER_FREE = "ListenerFree";
    private static final String FREE_WHEN_UNREACHABLE = "nativeBind$freeWhenUnreachable";

    private static final ClassName C_CONTEXT = ClassName.get("org.graalvm.nativeimage.c", "CContext");
    private static final ClassName C_CONTEXT_DIRECTIVES = C_CONTEXT.nestedClass("Directives");
    private static final ClassName C_ENUM = ClassName.get("org.graalvm.nativeimage.c.constant", "CEnum");
    private static final ClassName C_ENUM_CONSTANT = ClassName.get("org.graalvm.nativeimage.c.constant", "CEnumConstant");
    private static final ClassName C_ENUM_VALUE = ClassName.get("org.graalvm.nativeimage.c.constant", "CEnumValue");
    private static final ClassName C_ENUM_LOOKUP = ClassName.get("org.graalvm.nativeimage.c.constant", "CEnumLookup");
    static final String ENUM_SUFFIX = "CEnum";
    static final String ENUM_HEADER_DIRECTORY = "META-INF/nativebind/include/";

    /**
     * How a Java value crosses the C boundary. {@code PASS_THROUGH} includes enums the user annotated with
     * {@code @CEnum}, which native-image converts itself; {@code ENUM} is any other enum, which crosses as its
     * generated {@code @CEnum} mirror.
     */
    enum Mapping {
        VOID, PASS_THROUGH, STRING, HANDLE, ENUM
    }

    record Entry(Wrapped member, String symbol, TypeMirror exceptionHandler) {
    }

    /** {@code builtins} maps each name from {@link #builtinNames()} to its exported symbol. */
    record SupportSymbols(String createIsolate, String tearDownIsolate, String releaseHandle, Map<String, String> builtins) {
    }

    /** The {@link Builtins} plus {@value #FUTURE_ON_COMPLETE}, which only C callers get. */
    static List<String> builtinNames() {
        List<String> names = new ArrayList<>();
        Builtins.ALL.forEach(b -> names.add(b.name()));
        names.add(FUTURE_ON_COMPLETE);
        return names;
    }

    private final Elements elements;
    private final Types types;
    private final TypeMirror stringType;
    private final TypeMirror wordBaseType;

    CEntryPointGenerator(ProcessingEnvironment env, TypeElement wordBase) {
        this.elements = env.getElementUtils();
        this.types = env.getTypeUtils();
        this.stringType = elements.getTypeElement("java.lang.String").asType();
        this.wordBaseType = wordBase.asType();
    }

    Listener listenerOf(TypeMirror type) {
        return Listener.of(type, types, elements, wordBaseType);
    }

    /** Throws {@link IllegalArgumentException} for a {@code Consumer} parameter whose values cannot reach native code. */
    void checkListeners(Wrapped member) {
        member.parameters().forEach(p -> listenerOf(p.asType()));
    }

    Mapping mappingOf(TypeMirror type) {
        TypeKind kind = type.getKind();
        if (kind == TypeKind.VOID) {
            return Mapping.VOID;
        }
        if (kind.isPrimitive() || types.isAssignable(type, wordBaseType)) {
            return Mapping.PASS_THROUGH;
        }
        TypeElement javaEnum = enumOf(type);
        if (javaEnum != null) {
            return hasAnnotation(javaEnum, C_ENUM) ? Mapping.PASS_THROUGH : Mapping.ENUM;
        }
        if (types.isSameType(type, stringType)) {
            return Mapping.STRING;
        }
        return Mapping.HANDLE;
    }

    private TypeName cType(Mapping mapping, TypeMirror type) {
        return switch (mapping) {
            case VOID, PASS_THROUGH -> TypeName.get(type);
            case STRING -> C_CHAR_POINTER;
            case HANDLE -> OBJECT_HANDLE;
            case ENUM -> mirrorName(enumOf(type));
        };
    }

    private static TypeElement enumOf(TypeMirror type) {
        return type instanceof DeclaredType declared && declared.asElement().getKind() == ElementKind.ENUM
                ? (TypeElement) declared.asElement()
                : null;
    }

    private static boolean hasAnnotation(Element element, ClassName annotation) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation.canonicalName())) {
                return true;
            }
        }
        return false;
    }

    /** The enums {@code member} takes or returns that cross as a generated {@code @CEnum} mirror. */
    Set<TypeElement> mirroredEnums(Wrapped member) {
        Set<TypeElement> enums = new LinkedHashSet<>();
        List<TypeMirror> crossing = new ArrayList<>();
        crossing.add(member.returnType(types));
        for (VariableElement p : member.parameters()) {
            Listener listener = listenerOf(p.asType());
            if (listener == null) {
                crossing.add(p.asType());
            } else if (listener.kind() == Listener.Kind.ENUM) {
                crossing.add(listener.valueType());
            }
        }
        for (TypeMirror type : crossing) {
            if (mappingOf(type) == Mapping.ENUM) {
                enums.add(enumOf(type));
            }
        }
        return enums;
    }

    /** {@code demo.Outer.Status} is mirrored by {@code demo.Outer_StatusCEnum}. */
    static ClassName mirrorName(TypeElement javaEnum) {
        PackageElement pkg = (PackageElement) packageOf(javaEnum);
        return ClassName.get(pkg.getQualifiedName().toString(), flatName(javaEnum, ENUM_SUFFIX));
    }

    private static Element packageOf(Element element) {
        while (!(element instanceof PackageElement)) {
            element = element.getEnclosingElement();
        }
        return element;
    }

    /**
     * The C header defining {@code cName} as a {@code typedef enum} whose constants, named by {@code constants}
     * (Java constant to C constant), take the Java ordinals as values.
     */
    static String enumHeader(TypeElement javaEnum, String cName, Map<String, String> constants) {
        String guard = "NATIVEBIND_" + CNames.upperSnake(cName) + "_H";
        StringBuilder header = new StringBuilder()
                .append("#ifndef ").append(guard).append('\n')
                .append("#define ").append(guard).append('\n')
                .append('\n')
                .append("/* Mirrors ").append(javaEnum.getQualifiedName()).append(" */\n")
                .append("typedef enum ").append(cName).append(" {\n");
        int ordinal = 0;
        for (String cConstant : constants.values()) {
            header.append("    ").append(cConstant).append(" = ").append(ordinal).append(ordinal + 1 < constants.size() ? ",\n" : "\n");
            ordinal++;
        }
        return header.append("} ").append(cName).append(";\n")
                .append('\n')
                .append("#endif\n")
                .toString();
    }

    /**
     * The {@code @CEnum} through which entry points pass {@code javaEnum} as the C enumeration {@code cName}. Its
     * {@code @CContext} hands native-image the definition from {@link #enumHeader}, so the C values are the Java
     * ordinals and the generated image header can name the type.
     */
    JavaFile generateEnum(TypeElement javaEnum, String cName, Map<String, String> constants) {
        ClassName mirror = mirrorName(javaEnum);
        ClassName directives = mirror.nestedClass("Directives");
        TypeName javaType = ClassName.get(javaEnum);
        String headerFile = ENUM_HEADER_DIRECTORY + cName + ".h";

        TypeSpec.Builder clazz = TypeSpec.enumBuilder(mirror.simpleName())
                .addModifiers(Modifier.PUBLIC)
                .addOriginatingElement(javaEnum)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("javax.annotation.processing", "Generated"))
                        .addMember("value", "$S", NativeBindProcessor.class.getName())
                        .build())
                .addAnnotation(AnnotationSpec.builder(C_CONTEXT).addMember("value", "$T.class", directives).build())
                .addAnnotation(AnnotationSpec.builder(C_ENUM).addMember("value", "$S", cName).build())
                .addJavadoc("The C enumeration {@code $L}, through which NativeBind entry points pass {@link $T}.\n", cName, javaType)
                .addJavadoc("<p>\n")
                .addJavadoc("Its definition, {@link #HEADER}, is written to {@code $L.h} beside the image headers, which include it,\n", cName)
                .addJavadoc("by {@code $L}. It is also the {@code $L} resource.\n", FEATURE_CLASS, headerFile);
        constants.forEach((javaConstant, cConstant) -> clazz.addEnumConstant(javaConstant, TypeSpec.anonymousClassBuilder("")
                .addAnnotation(AnnotationSpec.builder(C_ENUM_CONSTANT).addMember("value", "$S", cConstant).build())
                .build()));

        ArrayTypeName javaArray = ArrayTypeName.of(javaType);
        ArrayTypeName mirrorArray = ArrayTypeName.of(mirror);
        clazz.addField(FieldSpec.builder(String.class, "HEADER", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .addJavadoc("The C definition of {@code $L}.\n", cName)
                        .initializer("$S", enumHeader(javaEnum, cName, constants))
                        .build())
                .addField(FieldSpec.builder(javaArray, "JAVA", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.values()", javaType)
                        .build())
                .addField(FieldSpec.builder(mirrorArray, "MIRRORS", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("values()")
                        .build())
                .addMethod(MethodSpec.methodBuilder("toC")
                        .addJavadoc("This constant's C value.\n")
                        .addModifiers(Modifier.PUBLIC, Modifier.NATIVE)
                        .addAnnotation(C_ENUM_VALUE)
                        .returns(int.class)
                        .build())
                .addMethod(MethodSpec.methodBuilder("fromC")
                        .addJavadoc("The constant with the given C value, or null if there is none.\n")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.NATIVE)
                        .addAnnotation(C_ENUM_LOOKUP)
                        .returns(mirror)
                        .addParameter(int.class, "value")
                        .build())
                .addMethod(MethodSpec.methodBuilder("toJava")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(javaType)
                        .addParameter(mirror, "value")
                        .addStatement("return value == null ? null : JAVA[value.ordinal()]")
                        .build())
                .addMethod(MethodSpec.methodBuilder("fromJava")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(mirror)
                        .addParameter(javaType, "value")
                        .addStatement("return value == null ? null : MIRRORS[value.ordinal()]")
                        .build())
                .addType(TypeSpec.classBuilder(directives.simpleName())
                        .addJavadoc("Hands native-image {@link #HEADER} as a header file, as it only reads C definitions from files.\n")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .addSuperinterface(C_CONTEXT_DIRECTIVES)
                        .addMethod(MethodSpec.methodBuilder("getHeaderFiles")
                                .addAnnotation(Override.class)
                                .addModifiers(Modifier.PUBLIC)
                                .returns(ParameterizedTypeName.get(List.class, String.class))
                                .beginControlFlow("try")
                                .addStatement("$T header = $T.createTempFile($S, $S)", Path.class, Files.class, "nativebind-" + cName + "-", ".h")
                                .addStatement("header.toFile().deleteOnExit()")
                                .addStatement("$T.writeString(header, HEADER)", Files.class)
                                // Forward slashes, since a backslash in an #include path is not portable
                                .addStatement("return $T.of(\"\\\"\" + header.toAbsolutePath().toString().replace('\\\\', '/') + \"\\\"\")", List.class)
                                .nextControlFlow("catch ($T e)", IOException.class)
                                .addStatement("throw new $T(e)", UncheckedIOException.class)
                                .endControlFlow()
                                .build())
                        .build());

        return JavaFile.builder(mirror.packageName(), clazz.build()).skipJavaLangImports(true).build();
    }

    JavaFile generate(TypeElement owner, List<Entry> entries, String releaseHandleSymbol) {
        String packageName = elements.getPackageOf(owner).getQualifiedName().toString();
        TypeSpec.Builder clazz = generatedClass(generatedSimpleName(owner))
                .addOriginatingElement(owner)
                .addJavadoc("C entry points generated by NativeBind from {@link $T}.\n", ClassName.get(owner))
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "{$S, $S}", "unchecked", "rawtypes")
                        .build());

        AnnotationMirror header = findHeader(owner);
        if (header != null) {
            clazz.addAnnotation(AnnotationSpec.get(header));
        }

        ClassName generated = ClassName.get(packageName, generatedSimpleName(owner));
        Set<Mapping> used = EnumSet.noneOf(Mapping.class);
        Set<Mapping> usedReturns = EnumSet.noneOf(Mapping.class);
        Map<String, Listener> listeners = new TreeMap<>();
        for (Entry entry : entries) {
            clazz.addMethod(wrapper(owner, generated, entry, releaseHandleSymbol, used, usedReturns, listeners));
        }
        if (!listeners.isEmpty()) {
            addListeners(clazz, generated, listeners, usedReturns);
        }
        addHelpers(clazz, used, usedReturns);

        return JavaFile.builder(packageName, clazz.build()).skipJavaLangImports(true).build();
    }

    /** The library-wide isolate lifecycle and handle release entry points; generated once per compilation. */
    JavaFile generateSupport(String packageName, AnnotationMirror header, SupportSymbols symbols, Iterable<TypeElement> origins) {
        TypeSpec.Builder clazz = generatedClass(SUPPORT_CLASS)
                .addJavadoc("Isolate lifecycle and handle management shared by every NativeBind C entry point.\n");
        origins.forEach(clazz::addOriginatingElement);
        if (header != null) {
            clazz.addAnnotation(AnnotationSpec.get(header));
        }

        clazz.addMethod(MethodSpec.methodBuilder("createIsolate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.NATIVE)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT)
                        .addMember("name", "$S", symbols.createIsolate())
                        .addMember("builtin", "$T.CREATE_ISOLATE", C_ENTRY_POINT_BUILTIN)
                        .addMember("documentation", "$S", "Creates an isolate and returns its initial thread, which every other entry point takes first.")
                        .build())
                .returns(ISOLATE_THREAD)
                .build());
        clazz.addMethod(MethodSpec.methodBuilder("tearDownIsolate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.NATIVE)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT)
                        .addMember("name", "$S", symbols.tearDownIsolate())
                        .addMember("builtin", "$T.TEAR_DOWN_ISOLATE", C_ENTRY_POINT_BUILTIN)
                        .addMember("documentation", "$S", "Tears down the isolate owning the given thread; returns 0 on success.")
                        .build())
                .returns(int.class)
                .addParameter(ISOLATE_THREAD, "thread")
                .build());
        clazz.addMethod(MethodSpec.methodBuilder("releaseHandle")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT)
                        .addMember("name", "$S", symbols.releaseHandle())
                        .addMember("documentation", "$S", "Releases a handle returned by an entry point, allowing its object to be collected. Null handles are ignored.")
                        .build())
                .addParameter(ParameterSpec.builder(ISOLATE_THREAD, "isolateThread")
                        .addAnnotation(ISOLATE_THREAD_CONTEXT)
                        .build())
                .addParameter(OBJECT_HANDLE, "handle")
                .beginControlFlow("if (handle.notEqual($T.zero()))", WORD_FACTORY)
                .addStatement("$T.getGlobal().destroy(handle)", OBJECT_HANDLES)
                .endControlFlow()
                .build());

        addBuiltins(clazz, packageName, symbols);
        addHelpers(clazz, EnumSet.of(Mapping.STRING, Mapping.HANDLE), EnumSet.of(Mapping.STRING, Mapping.HANDLE));
        return JavaFile.builder(packageName, clazz.build()).skipJavaLangImports(true).build();
    }

    /** Where the classes {@link #FEATURE_CLASS} initializes at build time are listed, one binary name per line. */
    static String buildTimeClassesResource(String packageName) {
        return "META-INF/nativebind/" + (packageName.isEmpty() ? "default" : packageName) + "/build-time-classes";
    }

    static String featureProperties(String packageName) {
        return "Args = --features=" + (packageName.isEmpty() ? "" : packageName + ".") + FEATURE_CLASS + "\n";
    }

    /**
     * Initializes each {@code @CEnum} mirror, and the enum its static initializer reads, at build time, where
     * native-image initializes the mirror. Mirrors can be generated in any round, so their names are read from the
     * {@link #buildTimeClassesResource} written once processing is over.
     * <p>
     * The image headers name each enumeration without defining it, so once they are written the feature writes
     * {@code <type>.h} beside them and includes it from each header that uses the type.
     */
    JavaFile generateFeature(String packageName, Iterable<? extends Element> origins) {
        TypeName classList = ParameterizedTypeName.get(ClassName.get(List.class), ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)));
        TypeName names = ParameterizedTypeName.get(Collection.class, String.class);
        TypeSpec.Builder clazz = TypeSpec.classBuilder(FEATURE_CLASS)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("javax.annotation.processing", "Generated"))
                        .addMember("value", "$S", NativeBindProcessor.class.getName())
                        .build())
                .addJavadoc("Initializes the enums NativeBind passes to C, and their {@code @CEnum} mirrors, at build time, and writes the\n")
                .addJavadoc("C definitions of the mirrors beside the image headers, which include them. Enabled through the generated\n")
                .addJavadoc("{@code native-image.properties}.\n")
                .addSuperinterface(FEATURE)
                .addField(FieldSpec.builder(classList, "mirrors", Modifier.PRIVATE, Modifier.FINAL)
                        .initializer("new $T<>()", ArrayList.class)
                        .build())
                // Features are instantiated reflectively by native-image, so the class needs a public constructor
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build())
                .addMethod(MethodSpec.methodBuilder("afterImageWrite")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(FEATURE.nestedClass("AfterImageWriteAccess"), "access")
                        .addStatement("$T image = access.getImagePath()", Path.class)
                        .beginControlFlow("if (image == null || mirrors.isEmpty())")
                        .addStatement("return")
                        .endControlFlow()
                        // native-image writes the headers into the directory of the library itself
                        .addStatement("$T directory = image.toAbsolutePath().getParent()", Path.class)
                        .beginControlFlow("try")
                        .addStatement("$T<String, String> headers = new $T<>()", Map.class, LinkedHashMap.class)
                        .beginControlFlow("for (Class<?> mirror : mirrors)")
                        .addStatement("headers.put(mirror.getAnnotation($T.class).value(), (String) mirror.getField($S).get(null))", C_ENUM, "HEADER")
                        .endControlFlow()
                        .beginControlFlow("for ($T<String, String> header : headers.entrySet())", Map.Entry.class)
                        .addStatement("$T.writeString(directory.resolve(header.getKey() + $S), header.getValue())", Files.class, ".h")
                        .endControlFlow()
                        .addStatement("$T<$T> files", List.class, Path.class)
                        .beginControlFlow("try ($T<$T> listing = $T.list(directory))", Stream.class, Path.class, Files.class)
                        .addStatement("files = listing.filter(file -> file.getFileName().toString().endsWith($S)).toList()", ".h")
                        .endControlFlow()
                        .beginControlFlow("for ($T file : files)", Path.class)
                        .addStatement("String name = file.getFileName().toString()")
                        .beginControlFlow("if (headers.containsKey(name.substring(0, name.length() - 2)))")
                        .addStatement("continue")
                        .endControlFlow()
                        .addStatement("String content = $T.readString(file)", Files.class)
                        .addStatement("String updated = includeEnums(content, headers.keySet())")
                        .beginControlFlow("if (!updated.equals(content))")
                        .addStatement("$T.writeString(file, updated)", Files.class)
                        .endControlFlow()
                        .endControlFlow()
                        .nextControlFlow("catch ($T | $T e)", IOException.class, ReflectiveOperationException.class)
                        .addStatement("throw new $T($S, e)", IllegalStateException.class, "NativeBind could not write the C enumeration headers")
                        .endControlFlow()
                        .build())
                .addMethod(MethodSpec.methodBuilder("includeEnums")
                        .addJavadoc("Includes {@code <type>.h} for each of {@code types} that {@code header} uses and does not include yet, right\n")
                        .addJavadoc("after its include guard so that the definitions precede every use.\n")
                        .addModifiers(Modifier.STATIC)
                        .returns(String.class)
                        .addParameter(String.class, "header")
                        .addParameter(names, "types")
                        .addStatement("StringBuilder includes = new StringBuilder()")
                        .beginControlFlow("for (String type : types)")
                        .addStatement("String include = $S + type + $S", "#include \"", ".h\"")
                        .beginControlFlow("if (!header.contains(include) && $T.compile($S + type + $S).matcher(header).find())", Pattern.class, "\\b", "\\b")
                        .addStatement("includes.append(include).append('\\n')")
                        .endControlFlow()
                        .endControlFlow()
                        .beginControlFlow("if (includes.length() == 0)")
                        .addStatement("return header")
                        .endControlFlow()
                        .addStatement("$T guard = $T.compile($S).matcher(header)", Matcher.class, Pattern.class, "(?m)^#define [A-Za-z0-9_]+[ \\t]*\\r?\\n")
                        .addStatement("int at = guard.find() ? guard.end() : 0")
                        .addStatement("return header.substring(0, at) + includes + header.substring(at)")
                        .build())
                .addMethod(MethodSpec.methodBuilder("afterRegistration")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(FEATURE.nestedClass("AfterRegistrationAccess"), "access")
                        .beginControlFlow("try")
                        .addStatement("$T<$T> lists = access.getApplicationClassLoader().getResources($S)",
                                Enumeration.class, URL.class, buildTimeClassesResource(packageName))
                        .beginControlFlow("while (lists.hasMoreElements())")
                        .beginControlFlow("try ($T in = lists.nextElement().openStream())", InputStream.class)
                        .beginControlFlow("for (String name : new String(in.readAllBytes(), $T.UTF_8).split($S))", StandardCharsets.class, "\n")
                        .beginControlFlow("if (name.isBlank())")
                        .addStatement("continue")
                        .endControlFlow()
                        .addStatement("Class<?> type = access.findClassByName(name.strip())")
                        .beginControlFlow("if (type == null)")
                        .addStatement("throw new $T($S + name.strip())", IllegalStateException.class, "NativeBind could not find ")
                        .endControlFlow()
                        .addStatement("$T.initializeAtBuildTime(type)", RUNTIME_CLASS_INITIALIZATION)
                        .beginControlFlow("if (type.isAnnotationPresent($T.class))", C_ENUM)
                        .addStatement("mirrors.add(type)")
                        .endControlFlow()
                        .endControlFlow()
                        .endControlFlow()
                        .endControlFlow()
                        .nextControlFlow("catch ($T e)", IOException.class)
                        .addStatement("throw new $T(e)", UncheckedIOException.class)
                        .endControlFlow()
                        .build());
        origins.forEach(clazz::addOriginatingElement);
        return JavaFile.builder(packageName, clazz.build()).skipJavaLangImports(true).build();
    }

    /** Wraps each {@link Builtins.Builtin} in an entry point, and adds {@value #FUTURE_ON_COMPLETE}. */
    private void addBuiltins(TypeSpec.Builder clazz, String packageName, SupportSymbols symbols) {
        for (Builtins.Builtin builtin : Builtins.ALL) {
            String symbol = symbols.builtins().get(builtin.name());
            MethodSpec.Builder method = MethodSpec.methodBuilder(builtin.name())
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(cType(builtin.returns()))
                    .addParameter(ParameterSpec.builder(ISOLATE_THREAD, "isolateThread")
                            .addAnnotation(ISOLATE_THREAD_CONTEXT)
                            .build());
            List<CodeBlock> args = new ArrayList<>();
            for (Builtins.Param param : builtin.params()) {
                method.addParameter(cType(param.type()), param.name());
                args.add(switch (param.type()) {
                    case HANDLE -> CodeBlock.of("$N($N)", FROM_HANDLE, param.name());
                    case STRING -> CodeBlock.of("$N($N)", TO_JAVA_STRING, param.name());
                    default -> CodeBlock.of("$N", param.name());
                });
            }
            CodeBlock call = CodeBlock.of("$N($L)", builtin.implName(), CodeBlock.join(args, ", "));
            method.addStatement("return $L", switch (builtin.returns()) {
                case HANDLE -> CodeBlock.of("$N($L)", TO_HANDLE, call);
                case STRING -> CodeBlock.of("$N($L)", TO_C_STRING, call);
                default -> call;
            });

            List<String> docs = new ArrayList<>(List.of(builtin.doc()));
            if (builtin.returns() == Builtins.Type.STRING) {
                docs.add("The returned string is allocated with malloc and must be released by the caller with free().");
            } else if (builtin.returns() == Builtins.Type.HANDLE) {
                docs.add("The returned handle keeps its object alive until it is passed to " + symbols.releaseHandle() + "().");
            }
            method.addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT)
                    .addMember("name", "$S", symbol)
                    .addMember("documentation", "{$L}", CodeBlock.join(docs.stream().map(d -> CodeBlock.of("$S", d)).toList(), ", "))
                    .build());
            clazz.addMethod(method.build());
            clazz.addMethod(builtin.impl());
        }
        clazz.addMethod(Builtins.describe());

        ClassName callback = ClassName.get(packageName, SUPPORT_CLASS, FUTURE_CALLBACK);
        clazz.addType(TypeSpec.interfaceBuilder(FUTURE_CALLBACK)
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("{@code void (*)(graal_isolatethread_t *thread, void *userData)}, called by {@code $L}.\n",
                        symbols.builtins().get(FUTURE_ON_COMPLETE))
                .addSuperinterface(C_FUNCTION_POINTER)
                .addMethod(MethodSpec.methodBuilder("invoke")
                        .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                        .addAnnotation(INVOKE_C_FUNCTION_POINTER)
                        .addParameter(ISOLATE_THREAD, "thread")
                        .addParameter(VOID_POINTER, "userData")
                        .build())
                .build());
        clazz.addMethod(MethodSpec.methodBuilder(FUTURE_ON_COMPLETE)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT)
                        .addMember("name", "$S", symbols.builtins().get(FUTURE_ON_COMPLETE))
                        .addMember("documentation", "{$S, $S, $S}",
                                "Calls callback(thread, userData) once the CompletableFuture (or other CompletionStage) behind the handle completes, normally or not.",
                                "It runs on the completing thread, or immediately on this one if the future is already complete; thread may be used to call back into the isolate.",
                                "The future is kept alive until then, even if its handle is released.")
                        .build())
                .addParameter(ParameterSpec.builder(ISOLATE_THREAD, "isolateThread")
                        .addAnnotation(ISOLATE_THREAD_CONTEXT)
                        .build())
                .addParameter(OBJECT_HANDLE, "future")
                .addParameter(callback, "callback")
                .addParameter(VOID_POINTER, "userData")
                // Word values cannot be captured by the lambda's object, so they cross it as raw addresses
                .addStatement("long callbackAddress = callback.rawValue()")
                .addStatement("long userDataAddress = userData.rawValue()")
                .addStatement("(($T<?>) $N(future)).whenComplete((result, error) -> $T.<$T>pointer(callbackAddress)"
                                + ".invoke($T.getCurrentThread(), $T.pointer(userDataAddress)))",
                        CompletionStage.class, FROM_HANDLE, WORD_FACTORY, callback, CURRENT_ISOLATE, WORD_FACTORY)
                .build());
    }

    private static TypeName cType(Builtins.Type type) {
        return switch (type) {
            case HANDLE -> OBJECT_HANDLE;
            case STRING -> C_CHAR_POINTER;
            default -> type.javaType;
        };
    }

    private static TypeSpec.Builder generatedClass(String simpleName) {
        return TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("javax.annotation.processing", "Generated"))
                        .addMember("value", "$S", NativeBindProcessor.class.getName())
                        .build())
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
    }

    /** {@code Outer.Inner} becomes {@code Outer_InnerCEntryPoints}. */
    static String generatedSimpleName(TypeElement owner) {
        return flatName(owner, CLASS_SUFFIX);
    }

    /** {@code Outer.Inner} becomes {@code Outer_Inner} followed by {@code suffix}. */
    static String flatName(TypeElement type, String suffix) {
        StringBuilder name = new StringBuilder(type.getSimpleName());
        for (Element e = type.getEnclosingElement(); e instanceof TypeElement t; e = t.getEnclosingElement()) {
            name.insert(0, t.getSimpleName() + "_");
        }
        return name.append(suffix).toString();
    }

    private MethodSpec wrapper(TypeElement owner, ClassName generated, Entry entry, String releaseHandleSymbol,
                               Set<Mapping> usedParams, Set<Mapping> usedReturns, Map<String, Listener> listeners) {
        Wrapped member = entry.member();
        TypeMirror returnType = member.returnType(types);

        Set<String> taken = new HashSet<>();
        for (VariableElement p : member.parameters()) {
            taken.add(p.getSimpleName().toString());
        }
        String threadParam = unique("isolateThread", taken);
        String selfParam = member.hasReceiver() ? unique("self", taken) : null;

        Mapping returnMapping = mappingOf(returnType);
        usedReturns.add(returnMapping);

        String javaName = SourceVersion.isKeyword(entry.symbol()) ? entry.symbol() + "_" : entry.symbol();
        MethodSpec.Builder builder = MethodSpec.methodBuilder(javaName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(cType(returnMapping, returnType))
                .addParameter(ParameterSpec.builder(ISOLATE_THREAD, threadParam)
                        .addAnnotation(ISOLATE_THREAD_CONTEXT)
                        .build());
        if (selfParam != null) {
            builder.addParameter(OBJECT_HANDLE, selfParam);
            usedParams.add(Mapping.HANDLE);
        }

        List<CodeBlock> args = new ArrayList<>();
        List<String> listenerDocs = new ArrayList<>();
        List<String> enumDocs = new ArrayList<>();
        for (VariableElement p : member.parameters()) {
            TypeMirror type = p.asType();
            String name = p.getSimpleName().toString();
            Listener listener = listenerOf(type);
            if (listener != null) {
                // A Consumer arrives as a callback, the user data handed back to it, and an optional release hook
                String suffix = Listener.register(listeners, listener, types);
                String data = unique(name + "Data", taken);
                String free = unique(name + "Free", taken);
                builder.addParameter(generated.nestedClass(suffix + LISTENER), name)
                        .addParameter(VOID_POINTER, data)
                        .addParameter(generated.nestedClass(LISTENER_FREE), free);
                args.add(CodeBlock.of("($T) $N($N, $N, $N)", Listener.CONSUMER, listenerFactory(suffix), name, data, free));
                listenerDocs.addAll(listenerDocumentation(listener, name, data, free));
                continue;
            }
            Mapping mapping = mappingOf(type);
            usedParams.add(mapping);
            builder.addParameter(cType(mapping, type), name);
            args.add(switch (mapping) {
                case VOID, PASS_THROUGH -> CodeBlock.of("$N", name);
                case STRING -> CodeBlock.of("$N($N)", TO_JAVA_STRING, name);
                case HANDLE -> CodeBlock.of("($T) $N($N)", TypeName.get(types.erasure(type)), FROM_HANDLE, name);
                case ENUM -> CodeBlock.of("$T.toJava($N)", mirrorName(enumOf(type)), name);
            });
            if (mapping == Mapping.ENUM) {
                enumDocs.add("A " + name + " outside its enumeration reaches the Java code as null.");
            }
        }
        for (TypeMirror thrown : member.thrownTypes()) {
            builder.addException(TypeName.get(thrown));
        }
        listenerDocs.addAll(enumDocs);
        builder.addAnnotation(entryPointAnnotation(entry, returnMapping, releaseHandleSymbol, listenerDocs));

        CodeBlock receiver = selfParam == null
                ? null
                : CodeBlock.of("(($T) $N($N))", TypeName.get(types.erasure(owner.asType())), FROM_HANDLE, selfParam);
        CodeBlock call = member.call(receiver, args);

        switch (returnMapping) {
            case VOID -> builder.addStatement("$L", call);
            case PASS_THROUGH -> builder.addStatement("return $L", call);
            case STRING -> builder.addStatement("return $N($L)", TO_C_STRING, call);
            case HANDLE -> builder.addStatement("return $N($L)", TO_HANDLE, call);
            case ENUM -> builder.addStatement("return $T.fromJava($L)", mirrorName(enumOf(returnType)), call);
        }
        return builder.build();
    }

    private AnnotationSpec entryPointAnnotation(Entry entry, Mapping returnMapping, String releaseHandleSymbol, List<String> listenerDocs) {
        AnnotationSpec.Builder annotation = AnnotationSpec.builder(C_ENTRY_POINT).addMember("name", "$S", entry.symbol());

        List<String> docs = documentation(entry.member(), returnMapping, releaseHandleSymbol, listenerDocs);
        if (!docs.isEmpty()) {
            CodeBlock lines = CodeBlock.join(docs.stream().map(line -> CodeBlock.of("$S", line)).toList(), ", ");
            annotation.addMember("documentation", docs.size() == 1 ? "$L" : "{$L}", lines);
        }
        if (entry.exceptionHandler() != null) {
            annotation.addMember("exceptionHandler", "$T.class", TypeName.get(entry.exceptionHandler()));
        }
        return annotation.build();
    }

    /** The member's Javadoc, one entry per line, followed by how listeners are called and ownership notes for the returned value. */
    private List<String> documentation(Wrapped member, Mapping returnMapping, String releaseHandleSymbol, List<String> listenerDocs) {
        List<String> lines = new ArrayList<>();
        String doc = elements.getDocComment(member.element());
        if (doc != null) {
            for (String line : doc.strip().split("\\R")) {
                lines.add(line.strip());
            }
        }
        lines.addAll(listenerDocs);
        if (returnMapping == Mapping.STRING) {
            lines.add("The returned string is allocated with malloc and must be released by the caller with free().");
        } else if (returnMapping == Mapping.HANDLE) {
            lines.add("The returned handle keeps its object alive until it is passed to " + releaseHandleSymbol + "().");
        } else if (returnMapping == Mapping.ENUM) {
            lines.add("A null result from the Java code cannot be represented and fails the call.");
        }
        return lines;
    }

    private List<String> listenerDocumentation(Listener listener, String name, String data, String free) {
        List<String> lines = new ArrayList<>();
        lines.add(name + "(thread, value, " + data + ") is called on every notification, on the notifying thread; thread may be used to call back into the isolate. A NULL " + name + " passes null to the Java code.");
        lines.add(switch (listener.kind()) {
            case STRING -> "The string value is only valid until " + name + " returns.";
            case HANDLE -> "The handle value is only valid until " + name + " returns, and must not be released.";
            case BOXED -> "A null value arrives as " + (listener.primitive(types) == TypeKind.BOOLEAN ? "false." : "0.");
            case ENUM -> "A null value has no C enumeration constant, so notifying it throws a NullPointerException in the Java code.";
        });
        lines.add("If " + free + " is not NULL, it is called with " + data + " once the Java code no longer holds the listener, which may be never.");
        return lines;
    }

    private static String listenerFactory(String suffix) {
        return "nativeBind$to" + suffix + LISTENER;
    }

    /**
     * Declares the callback type and {@code Consumer} factory for each kind of listener the wrappers take, and the
     * hook that releases a listener's user data once its {@code Consumer} is unreachable.
     */
    private void addListeners(TypeSpec.Builder clazz, ClassName generated, Map<String, Listener> listeners, Set<Mapping> usedReturns) {
        ClassName free = generated.nestedClass(LISTENER_FREE);
        clazz.addType(TypeSpec.interfaceBuilder(LISTENER_FREE)
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("{@code void (*)(void *userData)}, called once a listener's {@code Consumer} is unreachable, so that its user data can be released.\n")
                .addSuperinterface(C_FUNCTION_POINTER)
                .addMethod(MethodSpec.methodBuilder("invoke")
                        .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                        .addAnnotation(INVOKE_C_FUNCTION_POINTER)
                        .addParameter(VOID_POINTER, "userData")
                        .build())
                .build());
        clazz.addType(Listener.cleanerHolder());
        clazz.addMethod(MethodSpec.methodBuilder(FREE_WHEN_UNREACHABLE)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(Object.class, "listener")
                .addParameter(free, "free")
                .addParameter(VOID_POINTER, "userData")
                .beginControlFlow("if (free.isNonNull())")
                .addStatement("long freeAddress = free.rawValue()")
                .addStatement("long userDataAddress = userData.rawValue()")
                .addStatement("$T.CLEANER.register(listener, () -> $T.<$T>pointer(freeAddress).invoke($T.pointer(userDataAddress)))",
                        generated.nestedClass(Listener.CLEANER_HOLDER), WORD_FACTORY, free, WORD_FACTORY)
                .endControlFlow()
                .build());

        for (Map.Entry<String, Listener> registered : listeners.entrySet()) {
            String suffix = registered.getKey();
            Listener listener = registered.getValue();
            ClassName callback = generated.nestedClass(suffix + LISTENER);
            TypeName cValue = switch (listener.kind()) {
                case STRING -> C_CHAR_POINTER;
                case BOXED -> TypeName.get(types.getPrimitiveType(listener.primitive(types)));
                case HANDLE -> OBJECT_HANDLE;
                // The @CEnum mirror, or the enum itself if it is a @CEnum, which native-image converts in the call
                case ENUM -> cType(mappingOf(listener.valueType()), listener.valueType());
            };
            clazz.addType(TypeSpec.interfaceBuilder(callback.simpleName())
                    .addModifiers(Modifier.PUBLIC)
                    .addJavadoc("{@code void (*)(graal_isolatethread_t *thread, $L value, void *userData)}, notified through a {@code Consumer<$L>}.\n",
                            listener.kind() == Listener.Kind.STRING ? "const char *" : cValue, TypeName.get(listener.valueType()))
                    .addSuperinterface(C_FUNCTION_POINTER)
                    .addMethod(MethodSpec.methodBuilder("invoke")
                            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                            .addAnnotation(INVOKE_C_FUNCTION_POINTER)
                            .addParameter(ISOLATE_THREAD, "thread")
                            .addParameter(cValue, "value")
                            .addParameter(VOID_POINTER, "userData")
                            .build())
                    .build());

            CodeBlock.Builder notify = CodeBlock.builder();
            switch (listener.kind()) {
                case STRING -> {
                    usedReturns.add(Mapping.STRING);
                    notify.addStatement("$T cValue = $N(value)", C_CHAR_POINTER, TO_C_STRING)
                            .beginControlFlow("try")
                            .addStatement("$L", invokeListener(callback, CodeBlock.of("cValue")))
                            .nextControlFlow("finally")
                            .beginControlFlow("if (cValue.isNonNull())")
                            .addStatement("$T.free(cValue)", UNMANAGED_MEMORY)
                            .endControlFlow()
                            .endControlFlow();
                }
                case BOXED -> notify.addStatement("$L", invokeListener(callback, Listener.unboxedOrZero(listener.primitive(types), "value")));
                case ENUM -> notify.beginControlFlow("if (value == null)")
                        .addStatement("throw new $T($S)", NullPointerException.class,
                                "null cannot be passed to a C listener of " + listener.enumType().getQualifiedName())
                        .endControlFlow()
                        .addStatement("$L", invokeListener(callback, mappingOf(listener.valueType()) == Mapping.ENUM
                                ? CodeBlock.of("$T.fromJava(value)", mirrorName(listener.enumType()))
                                : CodeBlock.of("value")));
                case HANDLE -> {
                    usedReturns.add(Mapping.HANDLE);
                    notify.addStatement("$T handle = $N(value)", OBJECT_HANDLE, TO_HANDLE)
                            .beginControlFlow("try")
                            .addStatement("$L", invokeListener(callback, CodeBlock.of("handle")))
                            .nextControlFlow("finally")
                            .beginControlFlow("if (handle.notEqual($T.zero()))", WORD_FACTORY)
                            .addStatement("$T.getGlobal().destroy(handle)", OBJECT_HANDLES)
                            .endControlFlow()
                            .endControlFlow();
                }
            }

            TypeName consumer = ParameterizedTypeName.get(Listener.CONSUMER, listener.javaType());
            clazz.addMethod(MethodSpec.methodBuilder(listenerFactory(suffix))
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(consumer)
                    .addParameter(callback, "listener")
                    .addParameter(VOID_POINTER, "userData")
                    .addParameter(free, "free")
                    .beginControlFlow("if (listener.isNull())")
                    .addStatement("return null")
                    .endControlFlow()
                    // Word values cannot be captured by the lambda's object, so they cross it as raw addresses
                    .addStatement("long listenerAddress = listener.rawValue()")
                    .addStatement("long userDataAddress = userData.rawValue()")
                    .addCode("$T consumer = value -> {\n$>", consumer)
                    .addCode(notify.build())
                    .addCode("$<};\n")
                    .addStatement("$N(consumer, free, userData)", FREE_WHEN_UNREACHABLE)
                    .addStatement("return consumer")
                    .build());
        }
    }

    private static CodeBlock invokeListener(ClassName callback, CodeBlock value) {
        return CodeBlock.of("$T.<$T>pointer(listenerAddress).invoke($T.getCurrentThread(), $L, $T.pointer(userDataAddress))",
                WORD_FACTORY, callback, CURRENT_ISOLATE, value, WORD_FACTORY);
    }

    AnnotationMirror findHeader(TypeElement owner) {
        for (Element e = owner; e != null && !(e instanceof PackageElement); e = e.getEnclosingElement()) {
            for (AnnotationMirror mirror : e.getAnnotationMirrors()) {
                if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(C_HEADER)) {
                    return mirror;
                }
            }
        }
        return null;
    }

    private static void addHelpers(TypeSpec.Builder clazz, Set<Mapping> params, Set<Mapping> returns) {
        if (params.contains(Mapping.STRING)) {
            clazz.addMethod(MethodSpec.methodBuilder(TO_JAVA_STRING)
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(String.class)
                    .addParameter(C_CHAR_POINTER, "pointer")
                    .addStatement("return pointer.isNull() ? null : $T.utf8ToJavaString(pointer)", C_TYPE_CONVERSION)
                    .build());
        }
        if (returns.contains(Mapping.STRING)) {
            clazz.addMethod(MethodSpec.methodBuilder(TO_C_STRING)
                    .addJavadoc("Copies {@code value} into a NUL-terminated UTF-8 buffer owned by the caller.\n")
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(C_CHAR_POINTER)
                    .addParameter(String.class, "value")
                    .beginControlFlow("if (value == null)")
                    .addStatement("return $T.nullPointer()", WORD_FACTORY)
                    .endControlFlow()
                    .addStatement("byte[] bytes = value.getBytes($T.UTF_8)", StandardCharsets.class)
                    .addStatement("$T pointer = $T.malloc(bytes.length + 1)", C_CHAR_POINTER, UNMANAGED_MEMORY)
                    .beginControlFlow("for (int i = 0; i < bytes.length; i++)")
                    .addStatement("pointer.write(i, bytes[i])")
                    .endControlFlow()
                    .addStatement("pointer.write(bytes.length, (byte) 0)")
                    .addStatement("return pointer")
                    .build());
        }
        if (params.contains(Mapping.HANDLE)) {
            clazz.addMethod(MethodSpec.methodBuilder(FROM_HANDLE)
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(Object.class)
                    .addParameter(OBJECT_HANDLE, "handle")
                    .addStatement("return handle.equal($T.zero()) ? null : $T.getGlobal().get(handle)",
                            WORD_FACTORY, OBJECT_HANDLES)
                    .build());
        }
        if (returns.contains(Mapping.HANDLE)) {
            clazz.addMethod(MethodSpec.methodBuilder(TO_HANDLE)
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(OBJECT_HANDLE)
                    .addParameter(Object.class, "value")
                    .addStatement("return value == null ? $T.zero() : $T.getGlobal().create(value)",
                            WORD_FACTORY, OBJECT_HANDLES)
                    .build());
        }
    }

    private static String unique(String name, Set<String> taken) {
        while (taken.contains(name)) {
            name += "_";
        }
        taken.add(name);
        return name;
    }
}

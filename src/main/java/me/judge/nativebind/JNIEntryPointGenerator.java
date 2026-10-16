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
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates {@code @CEntryPoint}s that a JVM binds to {@code native} methods, along with the JVM-side class
 * declaring those methods.
 */
final class JNIEntryPointGenerator {
    static final String CLASS_SUFFIX = "JNIEntryPoints";
    static final String SUPPORT_CLASS = "NativeBindJNIEntryPoints";
    static final String FEATURE_CLASS = "NativeBindJNIFeature";
    static final String BRIDGE_SUPPORT_CLASS = "NativeBindJNIBridgeSupport";
    static final String JNI = "org.graalvm.jniutils.JNI";

    private static final String JNIUTILS = "org.graalvm.jniutils";
    private static final ClassName JNI_ENV = ClassName.get(JNIUTILS, "JNI", "JNIEnv");
    private static final ClassName JCLASS = ClassName.get(JNIUTILS, "JNI", "JClass");
    private static final ClassName JSTRING = ClassName.get(JNIUTILS, "JNI", "JString");
    private static final ClassName JOBJECT_ARRAY = ClassName.get(JNIUTILS, "JNI", "JObjectArray");
    private static final ClassName JNI_UTIL = ClassName.get(JNIUTILS, "JNIUtil");
    private static final ClassName JNI_METHOD_SCOPE = ClassName.get(JNIUTILS, "JNIMethodScope");
    private static final ClassName JMETHOD_ID = ClassName.get(JNIUTILS, "JNI", "JMethodID");
    private static final ClassName JTHROWABLE = ClassName.get(JNIUTILS, "JNI", "JThrowable");
    private static final ClassName JVALUE = ClassName.get(JNIUTILS, "JNI", "JValue");
    private static final ClassName STACK_VALUE = ClassName.get("org.graalvm.nativeimage", "StackValue");
    private static final ClassName C_TYPE_CONVERSION = ClassName.get("org.graalvm.nativeimage.c.type", "CTypeConversion");
    private static final ClassName C_CHAR_POINTER_HOLDER = C_TYPE_CONVERSION.nestedClass("CCharPointerHolder");
    private static final ClassName NATIVE_BRIDGE_SUPPORT = ClassName.get(JNIUTILS, "NativeBridgeSupport");

    private static final ClassName C_ENTRY_POINT = ClassName.get("org.graalvm.nativeimage.c.function", "CEntryPoint");
    private static final ClassName C_ENTRY_POINT_BUILTIN = C_ENTRY_POINT.nestedClass("Builtin");
    private static final ClassName ISOLATE_THREAD_CONTEXT = C_ENTRY_POINT.nestedClass("IsolateThreadContext");
    private static final ClassName ISOLATE_CONTEXT = C_ENTRY_POINT.nestedClass("IsolateContext");
    private static final ClassName ISOLATE_THREAD = ClassName.get("org.graalvm.nativeimage", "IsolateThread");
    private static final ClassName ISOLATE = ClassName.get("org.graalvm.nativeimage", "Isolate");
    private static final ClassName OBJECT_HANDLE = ClassName.get("org.graalvm.nativeimage", "ObjectHandle");
    private static final ClassName OBJECT_HANDLES = ClassName.get("org.graalvm.nativeimage", "ObjectHandles");
    private static final ClassName IMAGE_SINGLETONS = ClassName.get("org.graalvm.nativeimage", "ImageSingletons");
    private static final ClassName FEATURE = ClassName.get("org.graalvm.nativeimage.hosted", "Feature");
    private static final ClassName WORD_FACTORY = ClassName.get("org.graalvm.word", "WordFactory");
    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");

    // '$' keeps generated names out of the space of Java parameter names a user would write
    private static final String SCOPE = "nativeBind$scope";
    private static final String ERROR = "nativeBind$error";
    private static final String TO_STRING_ARRAY = "nativeBind$toStringArray";
    private static final String TO_HS_STRING_ARRAY = "nativeBind$toHSStringArray";
    private static final String FROM_HANDLE = "nativeBind$fromHandle";
    private static final String TO_HANDLE = "nativeBind$toHandle";
    private static final String THROW_IN_JVM = "nativeBind$throwInJvm";
    private static final String CURRENT = "nativeBind$current";
    private static final String EPOCH = "nativeBind$epoch";

    /** Non-native methods generated into the JVM class declaring the support natives; user natives may not reuse their names. */
    static final Set<String> JVM_HELPERS = Set.of("openIsolate", "currentIsolateThread", "detachCurrentThread", "closeIsolate");

    /** How a Java value crosses the JNI boundary. */
    enum Kind {
        VOID, PRIMITIVE, STRING, STRING_ARRAY, PRIMITIVE_ARRAY, JNI_REFERENCE, ENV, WORD, HANDLE
    }

    /**
     * A Java type as seen from the native-image side ({@code nativeType}) and from the JVM ({@code jvmType}, which
     * is null for the injected {@code JNIEnv}).
     */
    record Mapping(Kind kind, TypeMirror type, TypeName nativeType, TypeName jvmType, String descriptor) {
    }

    record Signature(List<Mapping> params, Mapping returns) {
    }

    /** A {@code static native} method declared by the JVM-side class. */
    record JvmNative(String name, List<TypeName> paramTypes, List<String> paramNames, TypeName returnType,
                     String argumentDescriptor, String returnDescriptor, String javadoc) {
        String descriptor() {
            return "(" + argumentDescriptor + ")" + returnDescriptor;
        }
    }

    record Entry(ExecutableElement method, String symbol, Signature signature) {
    }

    /** {@code builtins} maps each {@link Builtins.Builtin} name to its exported symbol. */
    record SupportSymbols(String createIsolate, String tearDownIsolate, String releaseHandle, String attachThread,
                          String detachThread, String getIsolate, Map<String, String> builtins) {
    }

    private final Elements elements;
    private final Types types;
    private final TypeMirror stringType;
    private final TypeMirror wordBaseType;
    private final TypeMirror jniEnvType;
    private final TypeMirror jobjectType;
    private final Map<TypeMirror, TypeName> jniReferenceTypes;

    JNIEntryPointGenerator(ProcessingEnvironment env, TypeElement wordBase) {
        this.elements = env.getElementUtils();
        this.types = env.getTypeUtils();
        this.stringType = elements.getTypeElement("java.lang.String").asType();
        this.wordBaseType = wordBase.asType();
        this.jniEnvType = jniType("JNIEnv");
        this.jobjectType = jniType("JObject");
        TypeName classType = ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class));
        this.jniReferenceTypes = new LinkedHashMap<>();
        jniReferenceTypes.put(jniType("JString"), ClassName.get(String.class));
        jniReferenceTypes.put(jniType("JClass"), classType);
        jniReferenceTypes.put(jniType("JThrowable"), ClassName.get(Throwable.class));
        jniReferenceTypes.put(jniType("JObjectArray"), ArrayTypeName.of(Object.class));
        jniReferenceTypes.put(jniType("JBooleanArray"), ArrayTypeName.of(boolean.class));
        jniReferenceTypes.put(jniType("JByteArray"), ArrayTypeName.of(byte.class));
        jniReferenceTypes.put(jniType("JCharArray"), ArrayTypeName.of(char.class));
        jniReferenceTypes.put(jniType("JShortArray"), ArrayTypeName.of(short.class));
        jniReferenceTypes.put(jniType("JIntArray"), ArrayTypeName.of(int.class));
        jniReferenceTypes.put(jniType("JLongArray"), ArrayTypeName.of(long.class));
        jniReferenceTypes.put(jniType("JFloatArray"), ArrayTypeName.of(float.class));
        jniReferenceTypes.put(jniType("JDoubleArray"), ArrayTypeName.of(double.class));
    }

    private TypeMirror jniType(String simpleName) {
        return elements.getTypeElement(JNI + "." + simpleName).asType();
    }

    /** Maps one parameter or return type, throwing {@link IllegalArgumentException} for unsupported types. */
    Mapping map(TypeMirror type) {
        TypeKind kind = type.getKind();
        if (kind == TypeKind.VOID) {
            return new Mapping(Kind.VOID, type, TypeName.VOID, TypeName.VOID, "V");
        }
        if (kind.isPrimitive()) {
            return new Mapping(Kind.PRIMITIVE, type, TypeName.get(type), TypeName.get(type), primitiveDescriptor(kind));
        }
        if (types.isSameType(type, stringType)) {
            return new Mapping(Kind.STRING, type, JSTRING, ClassName.get(String.class), "Ljava/lang/String;");
        }
        if (type instanceof ArrayType array) {
            TypeMirror component = array.getComponentType();
            if (types.isSameType(component, stringType)) {
                return new Mapping(Kind.STRING_ARRAY, type, JOBJECT_ARRAY, ArrayTypeName.of(String.class), "[Ljava/lang/String;");
            }
            if (component.getKind().isPrimitive()) {
                String name = component.getKind().name();
                ClassName jniArray = ClassName.get(JNIUTILS, "JNI", "J" + name.charAt(0) + name.substring(1).toLowerCase() + "Array");
                return new Mapping(Kind.PRIMITIVE_ARRAY, type, jniArray, TypeName.get(type), "[" + primitiveDescriptor(component.getKind()));
            }
        }
        if (types.isAssignable(type, jniEnvType)) {
            return new Mapping(Kind.ENV, type, JNI_ENV, null, "");
        }
        if (types.isAssignable(type, jobjectType)) {
            TypeName jvmType = ClassName.get(Object.class);
            for (Map.Entry<TypeMirror, TypeName> reference : jniReferenceTypes.entrySet()) {
                if (types.isAssignable(type, reference.getKey())) {
                    jvmType = reference.getValue();
                    break;
                }
            }
            return new Mapping(Kind.JNI_REFERENCE, type, TypeName.get(type), jvmType, descriptor(jvmType));
        }
        if (types.isAssignable(type, wordBaseType)) {
            return new Mapping(Kind.WORD, type, TypeName.get(type), TypeName.LONG, "J");
        }
        if (kind == TypeKind.TYPEVAR || kind == TypeKind.WILDCARD || kind == TypeKind.INTERSECTION || kind == TypeKind.UNION) {
            // Erasure keeps type variables of a generic owner out of the generated code
            return new Mapping(Kind.HANDLE, types.erasure(type), OBJECT_HANDLE, TypeName.LONG, "J");
        }
        return new Mapping(Kind.HANDLE, type, OBJECT_HANDLE, TypeName.LONG, "J");
    }

    Signature signature(ExecutableElement method) {
        List<Mapping> params = new ArrayList<>();
        for (VariableElement p : method.getParameters()) {
            params.add(map(p.asType()));
        }
        Mapping returns = map(Wrapped.returnType(method));
        if (returns.kind() == Kind.ENV) {
            throw new IllegalArgumentException("JNIEnv cannot be returned to the JVM");
        }
        return new Signature(params, returns);
    }

    /** The JVM-side declaration: {@code [long self,] <non-JNIEnv params>..., long isolateThread}. */
    JvmNative jvmNative(String name, ExecutableElement method, Signature signature) {
        Set<String> taken = new HashSet<>();
        method.getParameters().forEach(p -> taken.add(p.getSimpleName().toString()));

        List<TypeName> paramTypes = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
        StringBuilder descriptor = new StringBuilder();
        if (Wrapped.hasReceiver(method)) {
            paramTypes.add(TypeName.LONG);
            paramNames.add(unique("self", taken));
            descriptor.append('J');
        }
        for (int i = 0; i < signature.params().size(); i++) {
            Mapping mapping = signature.params().get(i);
            if (mapping.kind() != Kind.ENV) {
                paramTypes.add(mapping.jvmType());
                paramNames.add(method.getParameters().get(i).getSimpleName().toString());
                descriptor.append(mapping.descriptor());
            }
        }
        paramTypes.add(TypeName.LONG);
        paramNames.add(unique("isolateThread", taken));
        descriptor.append('J');

        StringBuilder javadoc = new StringBuilder();
        String doc = elements.getDocComment(method);
        if (doc != null) {
            javadoc.append(doc.strip()).append("\n\n");
        }
        javadoc.append("Calls {@code ").append(Wrapped.displayName(method)).append("} in the native image.\n");
        if (signature.returns().kind() == Kind.HANDLE) {
            javadoc.append("The returned handle must be passed to {@code releaseHandle} once it is no longer needed.\n");
        }

        return new JvmNative(name, paramTypes, paramNames, signature.returns().jvmType(), descriptor.toString(),
                signature.returns().descriptor(), javadoc.toString());
    }

    static List<JvmNative> supportNatives() {
        return List.of(
                new JvmNative("createIsolate", List.of(), List.of(), TypeName.LONG, "", "J",
                        "Creates an isolate and returns its initial thread, which every other native method takes last.\n"),
                new JvmNative("tearDownIsolate", List.of(TypeName.LONG), List.of("isolateThread"), TypeName.INT, "J", "I",
                        "Tears down the isolate owning {@code isolateThread}; returns 0 on success.\n"),
                new JvmNative("releaseHandle", List.of(TypeName.LONG, TypeName.LONG), List.of("handle", "isolateThread"), TypeName.VOID, "JJ", "V",
                        "Releases a handle returned by a native method, allowing its object to be collected. Null handles are ignored.\n"));
    }

    /**
     * Per-thread isolate management. An {@code IsolateThread} belongs to the OS thread it was created or attached on,
     * and entering the isolate with it from any other thread crashes the process, so every other thread must attach
     * itself first. Like {@link #builtinNatives()}, a hand-written class may omit them.
     */
    static List<JvmNative> threadNatives() {
        return List.of(
                new JvmNative("attachThread", List.of(TypeName.LONG), List.of("isolate"), TypeName.LONG, "J", "J",
                        "Attaches the calling thread to {@code isolate} (from {@code getIsolate}) and returns its isolate thread, or 0 on "
                                + "failure. A thread that is already attached gets its existing isolate thread back.\n"),
                new JvmNative("detachThread", List.of(TypeName.LONG), List.of("isolateThread"), TypeName.INT, "J", "I",
                        "Detaches the calling thread, which {@code isolateThread} must belong to, from its isolate; returns 0 on success.\n"),
                new JvmNative("getIsolate", List.of(TypeName.LONG), List.of("isolateThread"), TypeName.LONG, "J", "J",
                        "Returns the isolate that {@code isolateThread}, which must belong to the calling thread, is attached to, or 0 on failure.\n"));
    }

    /** The native methods backed by {@link Builtins}; unlike {@link #supportNatives()}, a hand-written class may omit them. */
    static List<JvmNative> builtinNatives() {
        List<JvmNative> natives = new ArrayList<>();
        for (Builtins.Builtin builtin : Builtins.ALL) {
            List<TypeName> paramTypes = new ArrayList<>();
            List<String> paramNames = new ArrayList<>();
            StringBuilder descriptor = new StringBuilder();
            for (Builtins.Param param : builtin.params()) {
                paramTypes.add(jvmType(param.type()));
                paramNames.add(param.name());
                descriptor.append(descriptor(jvmType(param.type())));
            }
            paramTypes.add(TypeName.LONG);
            paramNames.add("isolateThread");
            descriptor.append('J');

            String javadoc = builtin.doc() + "\n";
            if (builtin.returns() == Builtins.Type.HANDLE) {
                javadoc += "The returned handle must be passed to {@code releaseHandle} once it is no longer needed.\n";
            }
            TypeName returns = jvmType(builtin.returns());
            natives.add(new JvmNative(builtin.name(), paramTypes, paramNames, returns, descriptor.toString(), descriptor(returns), javadoc));
        }
        return natives;
    }

    private static TypeName jvmType(Builtins.Type type) {
        return type == Builtins.Type.HANDLE ? TypeName.LONG : type.javaType;
    }

    private static TypeName nativeType(Builtins.Type type) {
        return switch (type) {
            case HANDLE -> OBJECT_HANDLE;
            case STRING -> JSTRING;
            default -> type.javaType;
        };
    }

    JavaFile generate(TypeElement owner, List<Entry> entries) {
        TypeSpec.Builder clazz = generatedClass(generatedSimpleName(owner))
                .addOriginatingElement(owner)
                .addJavadoc("JNI entry points generated by NativeBind from {@link $T}.\n", ClassName.get(owner))
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "{$S, $S}", "unchecked", "rawtypes")
                        .build());

        Set<Kind> params = EnumSet.noneOf(Kind.class);
        Set<Kind> returns = EnumSet.noneOf(Kind.class);
        for (Entry entry : entries) {
            clazz.addMethod(wrapper(owner, entry, params, returns));
        }
        addHelpers(clazz, params, returns);
        clazz.addMethod(throwInJvm());

        return JavaFile.builder(elements.getPackageOf(owner).getQualifiedName().toString(), clazz.build())
                .skipJavaLangImports(true)
                .build();
    }

    /** {@code Outer.Inner} becomes {@code Outer_InnerJNIEntryPoints}. */
    static String generatedSimpleName(TypeElement owner) {
        StringBuilder name = new StringBuilder(owner.getSimpleName());
        for (Element e = owner.getEnclosingElement(); e instanceof TypeElement t; e = t.getEnclosingElement()) {
            name.insert(0, t.getSimpleName() + "_");
        }
        return name.append(CLASS_SUFFIX).toString();
    }

    private MethodSpec wrapper(TypeElement owner, Entry entry, Set<Kind> usedParams, Set<Kind> usedReturns) {
        ExecutableElement method = entry.method();
        Signature signature = entry.signature();
        Set<String> taken = new HashSet<>();
        method.getParameters().forEach(p -> taken.add(p.getSimpleName().toString()));
        String env = unique("env", taken);
        String clazz = unique("clazz", taken);
        String self = Wrapped.hasReceiver(method) ? unique("self", taken) : null;
        String thread = unique("isolateThread", taken);

        Mapping returns = signature.returns();
        usedReturns.add(returns.kind());

        MethodSpec.Builder builder = MethodSpec.methodBuilder(entry.symbol())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT).addMember("name", "$S", entry.symbol()).build())
                .returns(returns.nativeType())
                .addParameter(JNI_ENV, env)
                .addParameter(JCLASS, clazz);
        if (self != null) {
            builder.addParameter(OBJECT_HANDLE, self);
            usedParams.add(Kind.HANDLE);
        }

        List<CodeBlock> args = new ArrayList<>();
        for (int i = 0; i < signature.params().size(); i++) {
            Mapping mapping = signature.params().get(i);
            String name = method.getParameters().get(i).getSimpleName().toString();
            usedParams.add(mapping.kind());
            if (mapping.kind() == Kind.ENV) {
                args.add(CodeBlock.of("$N", env));
                continue;
            }
            builder.addParameter(mapping.nativeType(), name);
            args.add(switch (mapping.kind()) {
                case STRING -> CodeBlock.of("$T.createString($N, $N)", JNI_UTIL, env, name);
                case STRING_ARRAY -> CodeBlock.of("$N($N, $N)", TO_STRING_ARRAY, env, name);
                case PRIMITIVE_ARRAY -> CodeBlock.of("$T.createArray($N, $N)", JNI_UTIL, env, name);
                case HANDLE -> CodeBlock.of("($T) $N($N)", TypeName.get(types.erasure(mapping.type())), FROM_HANDLE, name);
                default -> CodeBlock.of("$N", name);
            });
        }
        builder.addParameter(ParameterSpec.builder(ISOLATE_THREAD, thread).addAnnotation(ISOLATE_THREAD_CONTEXT).build());

        CodeBlock call;
        if (Wrapped.isConstructor(method)) {
            call = CodeBlock.of("new $T($L)", ClassName.get(owner), CodeBlock.join(args, ", "));
        } else {
            CodeBlock target = self == null
                    ? CodeBlock.of("$T", ClassName.get(owner))
                    : CodeBlock.of("(($T) $N($N))", TypeName.get(types.erasure(owner.asType())), FROM_HANDLE, self);
            call = CodeBlock.of("$L.$N($L)", target, method.getSimpleName().toString(), CodeBlock.join(args, ", "));
        }

        String scopeName = owner.getQualifiedName() + "." + method.getSimpleName();
        builder.beginControlFlow("try ($T $N = new $T($S, $N))", JNI_METHOD_SCOPE, SCOPE, JNI_METHOD_SCOPE, scopeName, env);
        switch (returns.kind()) {
            case VOID -> builder.addStatement("$L", call);
            case STRING -> builder.addStatement("return $T.createHSString($N, $L)", JNI_UTIL, env, call);
            case STRING_ARRAY -> builder.addStatement("return $N($N, $L)", TO_HS_STRING_ARRAY, env, call);
            case PRIMITIVE_ARRAY -> builder.addStatement("return $T.createHSArray($N, $L)", JNI_UTIL, env, call);
            case HANDLE -> builder.addStatement("return $N($L)", TO_HANDLE, call);
            default -> builder.addStatement("return $L", call);
        }
        builder.nextControlFlow("catch ($T $N)", Throwable.class, ERROR)
                .addStatement("$N($N, $N)", THROW_IN_JVM, env, ERROR);
        switch (returns.kind()) {
            case VOID -> {
            }
            case PRIMITIVE -> builder.addStatement("return $L", returns.type().getKind() == TypeKind.BOOLEAN
                    ? CodeBlock.of("false")
                    : CodeBlock.of("($T) 0", returns.nativeType()));
            default -> builder.addStatement("return $T.zero()", WORD_FACTORY);
        }
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Raises {@code error} in the JVM as a {@code java.lang.RuntimeException} whose message holds the native stack
     * trace, using nothing but core JNI, so the JVM side never needs jniutils (which, unlike
     * {@code JNIExceptionWrapper}, keeps this working on Android). An exception already pending in the JVM, e.g.
     * thrown by an upcall, is left to propagate unchanged.
     */
    private static MethodSpec throwInJvm() {
        return MethodSpec.methodBuilder(THROW_IN_JVM)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(JNI_ENV, "env")
                .addParameter(Throwable.class, "error")
                .beginControlFlow("try")
                .beginControlFlow("if ($T.ExceptionCheck(env))", JNI_UTIL)
                .addStatement("return")
                .endControlFlow()
                // The whole native stack trace, causes included: wrappers like ExceptionInInitializerError carry no
                // message of their own, and the native frames are otherwise lost on the JVM side
                .addStatement("$T trace = new $T()", StringWriter.class, StringWriter.class)
                .addStatement("error.printStackTrace(new $T(trace))", PrintWriter.class)
                .addStatement("String message = trace.toString().stripTrailing()")
                .addStatement("$T type = $T.findClass(env, $S)", JCLASS, JNI_UTIL, "java/lang/RuntimeException")
                .addStatement("$T constructor", JMETHOD_ID)
                .beginControlFlow("try ($T name = $T.toCString($S); $T signature = $T.toCString($S))",
                        C_CHAR_POINTER_HOLDER, C_TYPE_CONVERSION, "<init>", C_CHAR_POINTER_HOLDER, C_TYPE_CONVERSION, "(Ljava/lang/String;)V")
                .addStatement("constructor = $T.GetMethodID(env, type, name.get(), signature.get())", JNI_UTIL)
                .endControlFlow()
                .addStatement("$T args = $T.get($T.class)", JVALUE, STACK_VALUE, JVALUE)
                .addStatement("args.setJObject($T.createHSString(env, message))", JNI_UTIL)
                .addStatement("$T.Throw(env, ($T) env.getFunctions().getNewObjectA().call(env, type, constructor, args))", JNI_UTIL, JTHROWABLE)
                .nextControlFlow("catch ($T secondary)", Throwable.class)
                // Nothing can be raised in the JVM; report rather than lose the original failure
                .addStatement("error.addSuppressed(secondary)")
                .addStatement("error.printStackTrace()")
                .endControlFlow()
                .build();
    }

    private void addHelpers(TypeSpec.Builder clazz, Set<Kind> params, Set<Kind> returns) {
        if (params.contains(Kind.STRING_ARRAY)) {
            clazz.addMethod(MethodSpec.methodBuilder(TO_STRING_ARRAY)
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(String[].class)
                    .addParameter(JNI_ENV, "env")
                    .addParameter(JOBJECT_ARRAY, "array")
                    .beginControlFlow("if (array.isNull())")
                    .addStatement("return null")
                    .endControlFlow()
                    .addStatement("String[] result = new String[$T.GetArrayLength(env, array)]", JNI_UTIL)
                    .beginControlFlow("for (int i = 0; i < result.length; i++)")
                    .addStatement("$T element = ($T) $T.GetObjectArrayElement(env, array, i)", JSTRING, JSTRING, JNI_UTIL)
                    .addStatement("result[i] = $T.createString(env, element)", JNI_UTIL)
                    .addStatement("$T.DeleteLocalRef(env, element)", JNI_UTIL)
                    .endControlFlow()
                    .addStatement("return result")
                    .build());
        }
        if (returns.contains(Kind.STRING_ARRAY)) {
            clazz.addMethod(MethodSpec.methodBuilder(TO_HS_STRING_ARRAY)
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(JOBJECT_ARRAY)
                    .addParameter(JNI_ENV, "env")
                    .addParameter(String[].class, "array")
                    .beginControlFlow("if (array == null)")
                    .addStatement("return $T.nullPointer()", WORD_FACTORY)
                    .endControlFlow()
                    .addStatement("$T stringClass = $T.findClass(env, $S)", JCLASS, JNI_UTIL, "java/lang/String")
                    .addStatement("$T result = $T.NewObjectArray(env, array.length, stringClass, $T.nullPointer())",
                            JOBJECT_ARRAY, JNI_UTIL, WORD_FACTORY)
                    .beginControlFlow("for (int i = 0; i < array.length; i++)")
                    .addStatement("$T element = $T.createHSString(env, array[i])", JSTRING, JNI_UTIL)
                    .addStatement("$T.SetObjectArrayElement(env, result, i, element)", JNI_UTIL)
                    .addStatement("$T.DeleteLocalRef(env, element)", JNI_UTIL)
                    .endControlFlow()
                    .addStatement("$T.DeleteLocalRef(env, stringClass)", JNI_UTIL)
                    .addStatement("return result")
                    .build());
        }
        if (params.contains(Kind.HANDLE)) {
            clazz.addMethod(MethodSpec.methodBuilder(FROM_HANDLE)
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(Object.class)
                    .addParameter(OBJECT_HANDLE, "handle")
                    .addStatement("return handle.equal($T.zero()) ? null : $T.getGlobal().get(handle)", WORD_FACTORY, OBJECT_HANDLES)
                    .build());
        }
        if (returns.contains(Kind.HANDLE)) {
            clazz.addMethod(MethodSpec.methodBuilder(TO_HANDLE)
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(OBJECT_HANDLE)
                    .addParameter(Object.class, "value")
                    .addStatement("return value == null ? $T.zero() : $T.getGlobal().create(value)", WORD_FACTORY, OBJECT_HANDLES)
                    .build());
        }
    }

    /** The library-wide isolate lifecycle and handle release entry points; generated once per compilation. */
    JavaFile generateSupport(String packageName, SupportSymbols symbols, Iterable<? extends Element> origins) {
        TypeSpec.Builder clazz = generatedClass(SUPPORT_CLASS)
                .addJavadoc("Isolate lifecycle and handle management shared by every NativeBind JNI entry point.\n");
        origins.forEach(clazz::addOriginatingElement);

        clazz.addMethod(MethodSpec.methodBuilder("createIsolate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.NATIVE)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT)
                        .addMember("name", "$S", symbols.createIsolate())
                        .addMember("builtin", "$T.CREATE_ISOLATE", C_ENTRY_POINT_BUILTIN)
                        .build())
                .returns(ISOLATE_THREAD)
                .addParameter(JNI_ENV, "env")
                .addParameter(JCLASS, "clazz")
                .build());
        clazz.addMethod(MethodSpec.methodBuilder("tearDownIsolate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.NATIVE)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT)
                        .addMember("name", "$S", symbols.tearDownIsolate())
                        .addMember("builtin", "$T.TEAR_DOWN_ISOLATE", C_ENTRY_POINT_BUILTIN)
                        .build())
                .returns(int.class)
                .addParameter(JNI_ENV, "env")
                .addParameter(JCLASS, "clazz")
                .addParameter(ParameterSpec.builder(ISOLATE_THREAD, "isolateThread").addAnnotation(ISOLATE_THREAD_CONTEXT).build())
                .build());
        clazz.addMethod(MethodSpec.methodBuilder("releaseHandle")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT).addMember("name", "$S", symbols.releaseHandle()).build())
                .addParameter(JNI_ENV, "env")
                .addParameter(JCLASS, "clazz")
                .addParameter(OBJECT_HANDLE, "handle")
                .addParameter(ParameterSpec.builder(ISOLATE_THREAD, "isolateThread").addAnnotation(ISOLATE_THREAD_CONTEXT).build())
                .beginControlFlow("if (handle.notEqual($T.zero()))", WORD_FACTORY)
                .addStatement("$T.getGlobal().destroy(handle)", OBJECT_HANDLES)
                .endControlFlow()
                .build());
        clazz.addMethod(MethodSpec.methodBuilder("attachThread")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.NATIVE)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT)
                        .addMember("name", "$S", symbols.attachThread())
                        .addMember("builtin", "$T.ATTACH_THREAD", C_ENTRY_POINT_BUILTIN)
                        .build())
                .returns(ISOLATE_THREAD)
                .addParameter(JNI_ENV, "env")
                .addParameter(JCLASS, "clazz")
                .addParameter(ParameterSpec.builder(ISOLATE, "isolate").addAnnotation(ISOLATE_CONTEXT).build())
                .build());
        clazz.addMethod(MethodSpec.methodBuilder("detachThread")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.NATIVE)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT)
                        .addMember("name", "$S", symbols.detachThread())
                        .addMember("builtin", "$T.DETACH_THREAD", C_ENTRY_POINT_BUILTIN)
                        .build())
                .returns(int.class)
                .addParameter(JNI_ENV, "env")
                .addParameter(JCLASS, "clazz")
                .addParameter(ParameterSpec.builder(ISOLATE_THREAD, "isolateThread").addAnnotation(ISOLATE_THREAD_CONTEXT).build())
                .build());
        clazz.addMethod(MethodSpec.methodBuilder("getIsolate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.NATIVE)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT)
                        .addMember("name", "$S", symbols.getIsolate())
                        .addMember("builtin", "$T.GET_ISOLATE", C_ENTRY_POINT_BUILTIN)
                        .build())
                .returns(ISOLATE)
                .addParameter(JNI_ENV, "env")
                .addParameter(JCLASS, "clazz")
                .addParameter(ParameterSpec.builder(ISOLATE_THREAD, "isolateThread").addAnnotation(ISOLATE_THREAD_CONTEXT).build())
                .build());

        for (Builtins.Builtin builtin : Builtins.ALL) {
            clazz.addMethod(builtin(builtin, symbols.builtins().get(builtin.name())));
            clazz.addMethod(builtin.impl());
        }
        clazz.addMethod(Builtins.describe());
        addHelpers(clazz, EnumSet.of(Kind.HANDLE), EnumSet.of(Kind.HANDLE));
        clazz.addMethod(throwInJvm());
        return JavaFile.builder(packageName, clazz.build()).skipJavaLangImports(true).build();
    }

    /** Wraps a builtin's logic in an entry point, the same way {@link #wrapper} wraps an annotated method. */
    private static MethodSpec builtin(Builtins.Builtin builtin, String symbol) {
        MethodSpec.Builder method = MethodSpec.methodBuilder(builtin.name())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(AnnotationSpec.builder(C_ENTRY_POINT).addMember("name", "$S", symbol).build())
                .returns(nativeType(builtin.returns()))
                .addParameter(JNI_ENV, "env")
                .addParameter(JCLASS, "clazz");
        List<CodeBlock> args = new ArrayList<>();
        for (Builtins.Param param : builtin.params()) {
            method.addParameter(nativeType(param.type()), param.name());
            args.add(switch (param.type()) {
                case HANDLE -> CodeBlock.of("$N($N)", FROM_HANDLE, param.name());
                case STRING -> CodeBlock.of("$T.createString(env, $N)", JNI_UTIL, param.name());
                default -> CodeBlock.of("$N", param.name());
            });
        }
        method.addParameter(ParameterSpec.builder(ISOLATE_THREAD, "isolateThread").addAnnotation(ISOLATE_THREAD_CONTEXT).build());

        CodeBlock call = CodeBlock.of("$N($L)", builtin.implName(), CodeBlock.join(args, ", "));
        method.beginControlFlow("try ($T $N = new $T($S, env))", JNI_METHOD_SCOPE, SCOPE, JNI_METHOD_SCOPE, "NativeBind." + builtin.name());
        method.addStatement("return $L", switch (builtin.returns()) {
            case HANDLE -> CodeBlock.of("$N($L)", TO_HANDLE, call);
            case STRING -> CodeBlock.of("$T.createHSString(env, $L)", JNI_UTIL, call);
            default -> call;
        });
        method.nextControlFlow("catch ($T $N)", Throwable.class, ERROR)
                .addStatement("$N(env, $N)", THROW_IN_JVM, ERROR);
        method.addStatement("return $L", switch (builtin.returns()) {
            case HANDLE, STRING -> CodeBlock.of("$T.zero()", WORD_FACTORY);
            case BOOLEAN -> CodeBlock.of("false");
            default -> CodeBlock.of("0");
        });
        return method.endControlFlow().build();
    }

    /**
     * The {@code NativeBridgeSupport} jniutils looks up on every call. Tracing is enabled at runtime with
     * {@code -Dnativebind.jni.trace=<level>}. It is a top-level class because it lives in the image heap, where a
     * nested class would drag the hosted-only {@code Feature} that encloses it into the image.
     */
    JavaFile generateBridgeSupport(String packageName, Iterable<? extends Element> origins) {
        TypeSpec.Builder support = generatedClass(BRIDGE_SUPPORT_CLASS, Modifier.PUBLIC)
                .addJavadoc("Default {@link $T} registered by {@code $L}.\n", NATIVE_BRIDGE_SUPPORT, FEATURE_CLASS)
                .addSuperinterface(NATIVE_BRIDGE_SUPPORT)
                .addMethod(MethodSpec.methodBuilder("getFeatureName")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(String.class)
                        .addStatement("return $S", "NativeBind")
                        .build())
                .addMethod(MethodSpec.methodBuilder("isTracingEnabled")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(boolean.class)
                        .addParameter(int.class, "level")
                        .addStatement("return level <= $T.getInteger($S, 0)", Integer.class, "nativebind.jni.trace")
                        .build())
                .addMethod(MethodSpec.methodBuilder("trace")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(String.class, "message")
                        .addStatement("$T.err.println($S + message)", System.class, "[NativeBind] ")
                        .build());
        origins.forEach(support::addOriginatingElement);
        return JavaFile.builder(packageName, support.build()).skipJavaLangImports(true).build();
    }

    /** Registers {@link #BRIDGE_SUPPORT_CLASS} as the {@code NativeBridgeSupport}, unless the application already registered one. */
    JavaFile generateFeature(String packageName, Iterable<? extends Element> origins) {
        // Features are instantiated reflectively by native-image, so the class needs a public constructor
        TypeSpec.Builder clazz = generatedClass(FEATURE_CLASS, Modifier.PUBLIC)
                .addJavadoc("Registers the {@link $T} that jniutils requires. Enabled through the generated "
                        + "{@code native-image.properties}.\n", NATIVE_BRIDGE_SUPPORT)
                .addSuperinterface(FEATURE)
                .addMethod(MethodSpec.methodBuilder("afterRegistration")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(FEATURE.nestedClass("AfterRegistrationAccess"), "access")
                        .beginControlFlow("if (!$T.contains($T.class))", IMAGE_SINGLETONS, NATIVE_BRIDGE_SUPPORT)
                        .addStatement("$T.add($T.class, new $T())", IMAGE_SINGLETONS, NATIVE_BRIDGE_SUPPORT,
                                ClassName.get(packageName, BRIDGE_SUPPORT_CLASS))
                        .endControlFlow()
                        .build());
        origins.forEach(clazz::addOriginatingElement);
        return JavaFile.builder(packageName, clazz.build()).skipJavaLangImports(true).build();
    }

    static String nativeImageProperties(String packageName) {
        String prefix = packageName.isEmpty() ? "" : packageName + ".";
        return "Args = --features=" + prefix + FEATURE_CLASS + " \\\n"
                + "       --initialize-at-build-time=" + prefix + BRIDGE_SUPPORT_CLASS + "\n";
    }

    /**
     * The JVM-side class declaring every native method bound to {@code binaryName}; with {@code support}, it also
     * gets the {@link #JVM_HELPERS} that manage each Java thread's isolate thread.
     */
    JavaFile generateJvmClass(String binaryName, List<JvmNative> natives, boolean support, Iterable<? extends Element> origins) {
        int dot = binaryName.lastIndexOf('.');
        String packageName = dot < 0 ? "" : binaryName.substring(0, dot);
        String simpleName = binaryName.substring(dot + 1);

        TypeSpec.Builder clazz = generatedClass(simpleName)
                .addJavadoc("Native methods implemented by a NativeBind native image. The library must be loaded "
                        + "(e.g. with {@link System#loadLibrary}) before any of them are called.\n");
        origins.forEach(clazz::addOriginatingElement);
        for (JvmNative jvmNative : natives) {
            MethodSpec.Builder method = MethodSpec.methodBuilder(jvmNative.name())
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.NATIVE)
                    .addJavadoc(jvmNative.javadoc().replace("$", "$$"))
                    .returns(jvmNative.returnType());
            for (int i = 0; i < jvmNative.paramTypes().size(); i++) {
                method.addParameter(jvmNative.paramTypes().get(i), jvmNative.paramNames().get(i));
            }
            clazz.addMethod(method.build());
        }
        if (support) {
            addThreadHelpers(clazz);
        }
        return JavaFile.builder(packageName, clazz.build()).skipJavaLangImports(true).build();
    }

    /**
     * Isolate threads are bound to the OS thread that created or attached them, so a Java thread may only pass its
     * own. These helpers let callers hold on to the isolate, which any thread may use, and look up the calling
     * thread's isolate thread from it, attaching on first use. The lookup is cached per thread; the epoch, bumped by
     * {@code closeIsolate}, keeps a cache from outliving its isolate when a later isolate reuses the same address.
     * Plain JDK code only, so the JVM side keeps working on Android.
     */
    private static void addThreadHelpers(TypeSpec.Builder clazz) {
        clazz.addField(FieldSpec.builder(AtomicLong.class, EPOCH, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", AtomicLong.class)
                .build());
        // {epoch, isolate, isolateThread} of the isolate the thread last used
        clazz.addField(FieldSpec.builder(ParameterizedTypeName.get(ThreadLocal.class, long[].class), CURRENT,
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.withInitial(() -> new long[3])", ThreadLocal.class)
                .build());

        clazz.addMethod(MethodSpec.methodBuilder("openIsolate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("Creates an isolate and returns it. Unlike an isolate thread, the isolate may be shared between "
                        + "threads; each one gets its own isolate thread from {@link #currentIsolateThread}.\n")
                .returns(long.class)
                .addStatement("long thread = createIsolate()")
                .beginControlFlow("if (thread == 0)")
                .addStatement("throw new $T($S)", IllegalStateException.class, "Could not create an isolate")
                .endControlFlow()
                .addStatement("long isolate = getIsolate(thread)")
                .addStatement("long[] current = $N.get()", CURRENT)
                .addStatement("current[0] = $N.get()", EPOCH)
                .addStatement("current[1] = isolate")
                .addStatement("current[2] = thread")
                .addStatement("return isolate")
                .build());
        clazz.addMethod(MethodSpec.methodBuilder("currentIsolateThread")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("Returns the calling thread's isolate thread in {@code isolate}, attaching the thread on first use. "
                        + "Pass it as the {@code isolateThread} argument of native methods called from this thread only: "
                        + "entering an isolate with another thread's isolate thread crashes the process.\n")
                .returns(long.class)
                .addParameter(long.class, "isolate")
                .addStatement("long[] current = $N.get()", CURRENT)
                .addStatement("long epoch = $N.get()", EPOCH)
                .beginControlFlow("if (current[2] == 0 || current[1] != isolate || current[0] != epoch)")
                .addStatement("long thread = attachThread(isolate)")
                .beginControlFlow("if (thread == 0)")
                .addStatement("throw new $T($S + $T.currentThread().getName() + $S + $T.toHexString(isolate))",
                        IllegalStateException.class, "Could not attach thread ", Thread.class, " to isolate 0x", Long.class)
                .endControlFlow()
                .addStatement("current[0] = epoch")
                .addStatement("current[1] = isolate")
                .addStatement("current[2] = thread")
                .endControlFlow()
                .addStatement("return current[2]")
                .build());
        clazz.addMethod(MethodSpec.methodBuilder("detachCurrentThread")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("Detaches the calling thread from {@code isolate}, releasing its isolate thread. Call it before a "
                        + "thread that used the isolate finishes; a later call to {@link #currentIsolateThread} attaches it again.\n")
                .addParameter(long.class, "isolate")
                .addStatement("long thread = currentIsolateThread(isolate)")
                .addStatement("$N.remove()", CURRENT)
                .beginControlFlow("if (detachThread(thread) != 0)")
                .addStatement("throw new $T($S + $T.toHexString(isolate))",
                        IllegalStateException.class, "Could not detach from isolate 0x", Long.class)
                .endControlFlow()
                .build());
        clazz.addMethod(MethodSpec.methodBuilder("closeIsolate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("Tears down {@code isolate} from the calling thread. No other thread may use it afterwards.\n")
                .addParameter(long.class, "isolate")
                .addStatement("long thread = currentIsolateThread(isolate)")
                .addStatement("$N.incrementAndGet()", EPOCH)
                .addStatement("$N.remove()", CURRENT)
                .beginControlFlow("if (tearDownIsolate(thread) != 0)")
                .addStatement("throw new $T($S + $T.toHexString(isolate))",
                        IllegalStateException.class, "Could not tear down isolate 0x", Long.class)
                .endControlFlow()
                .build());
    }

    /** Returns null when {@code existing} declares a matching {@code static native} method, else a description of the problem. */
    String verify(TypeElement existing, JvmNative expected) {
        for (Element member : existing.getEnclosedElements()) {
            if (member instanceof ExecutableElement method && method.getSimpleName().contentEquals(expected.name())
                    && descriptor(method).equals(expected.descriptor())) {
                Set<Modifier> modifiers = method.getModifiers();
                if (!modifiers.contains(Modifier.NATIVE) || !modifiers.contains(Modifier.STATIC)) {
                    return existing.getQualifiedName() + "." + expected.name() + " must be declared static native";
                }
                return null;
            }
        }
        return existing.getQualifiedName() + " has no static native method " + expected.name() + expected.descriptor();
    }

    /** Descriptors of the {@code native} methods {@code existing} already declares, by name. */
    Map<String, Set<String>> nativeDescriptors(TypeElement existing) {
        Map<String, Set<String>> result = new HashMap<>();
        for (Element member : existing.getEnclosedElements()) {
            if (member instanceof ExecutableElement method && method.getModifiers().contains(Modifier.NATIVE)) {
                result.computeIfAbsent(method.getSimpleName().toString(), k -> new HashSet<>()).add(descriptor(method));
            }
        }
        return result;
    }

    private String descriptor(ExecutableElement method) {
        StringBuilder sb = new StringBuilder("(");
        method.getParameters().forEach(p -> sb.append(descriptor(p.asType())));
        return sb.append(')').append(descriptor(method.getReturnType())).toString();
    }

    private String descriptor(TypeMirror type) {
        TypeMirror erased = types.erasure(type);
        if (erased.getKind().isPrimitive() || erased.getKind() == TypeKind.VOID) {
            return primitiveDescriptor(erased.getKind());
        }
        if (erased instanceof ArrayType array) {
            return "[" + descriptor(array.getComponentType());
        }
        TypeElement element = (TypeElement) ((DeclaredType) erased).asElement();
        return "L" + elements.getBinaryName(element).toString().replace('.', '/') + ";";
    }

    private static String descriptor(TypeName jvmType) {
        if (jvmType instanceof ArrayTypeName array) {
            return "[" + descriptor(array.componentType());
        }
        if (jvmType instanceof ParameterizedTypeName parameterized) {
            return descriptor(parameterized.rawType());
        }
        if (jvmType instanceof ClassName className) {
            return "L" + className.reflectionName().replace('.', '/') + ";";
        }
        return switch (jvmType.toString()) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "V";
        };
    }

    private static String primitiveDescriptor(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case CHAR -> "C";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            default -> throw new IllegalArgumentException("Not a primitive: " + kind);
        };
    }

    private static TypeSpec.Builder generatedClass(String simpleName) {
        return generatedClass(simpleName, Modifier.PRIVATE);
    }

    private static TypeSpec.Builder generatedClass(String simpleName, Modifier constructorVisibility) {
        return TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", NativeBindProcessor.class.getName())
                        .build())
                .addMethod(MethodSpec.constructorBuilder().addModifiers(constructorVisibility).build());
    }

    private static String unique(String name, Set<String> taken) {
        while (taken.contains(name)) {
            name += "_";
        }
        taken.add(name);
        return name;
    }
}

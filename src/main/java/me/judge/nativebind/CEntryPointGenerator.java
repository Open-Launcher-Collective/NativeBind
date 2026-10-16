package me.judge.nativebind;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
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

    /** How a Java value crosses the C boundary. */
    enum Mapping {
        VOID, PASS_THROUGH, STRING, HANDLE;

        TypeName cType(TypeMirror type) {
            return switch (this) {
                case VOID, PASS_THROUGH -> TypeName.get(type);
                case STRING -> C_CHAR_POINTER;
                case HANDLE -> OBJECT_HANDLE;
            };
        }
    }

    record Entry(ExecutableElement method, String symbol, TypeMirror exceptionHandler) {
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
    void checkListeners(ExecutableElement method) {
        method.getParameters().forEach(p -> listenerOf(p.asType()));
    }

    Mapping mappingOf(TypeMirror type) {
        TypeKind kind = type.getKind();
        if (kind == TypeKind.VOID) {
            return Mapping.VOID;
        }
        if (kind.isPrimitive() || types.isAssignable(type, wordBaseType)) {
            return Mapping.PASS_THROUGH;
        }
        if (types.isSameType(type, stringType)) {
            return Mapping.STRING;
        }
        return Mapping.HANDLE;
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
            addListeners(clazz, generated, listeners.values(), usedReturns);
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
        StringBuilder name = new StringBuilder(owner.getSimpleName());
        for (Element e = owner.getEnclosingElement(); e instanceof TypeElement t; e = t.getEnclosingElement()) {
            name.insert(0, t.getSimpleName() + "_");
        }
        return name.append(CLASS_SUFFIX).toString();
    }

    private MethodSpec wrapper(TypeElement owner, ClassName generated, Entry entry, String releaseHandleSymbol,
                               Set<Mapping> usedParams, Set<Mapping> usedReturns, Map<String, Listener> listeners) {
        ExecutableElement method = entry.method();
        TypeMirror returnType = Wrapped.returnType(method);

        Set<String> taken = new HashSet<>();
        for (VariableElement p : method.getParameters()) {
            taken.add(p.getSimpleName().toString());
        }
        String threadParam = unique("isolateThread", taken);
        String selfParam = Wrapped.hasReceiver(method) ? unique("self", taken) : null;

        Mapping returnMapping = mappingOf(returnType);
        usedReturns.add(returnMapping);

        String javaName = SourceVersion.isKeyword(entry.symbol()) ? entry.symbol() + "_" : entry.symbol();
        MethodSpec.Builder builder = MethodSpec.methodBuilder(javaName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(returnMapping.cType(returnType))
                .addParameter(ParameterSpec.builder(ISOLATE_THREAD, threadParam)
                        .addAnnotation(ISOLATE_THREAD_CONTEXT)
                        .build());
        if (selfParam != null) {
            builder.addParameter(OBJECT_HANDLE, selfParam);
            usedParams.add(Mapping.HANDLE);
        }

        List<CodeBlock> args = new ArrayList<>();
        List<String> listenerDocs = new ArrayList<>();
        for (VariableElement p : method.getParameters()) {
            TypeMirror type = p.asType();
            String name = p.getSimpleName().toString();
            Listener listener = listenerOf(type);
            if (listener != null) {
                // A Consumer arrives as a callback, the user data handed back to it, and an optional release hook
                String suffix = listener.suffix(types);
                listeners.putIfAbsent(suffix, listener);
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
            builder.addParameter(mapping.cType(type), name);
            args.add(switch (mapping) {
                case VOID, PASS_THROUGH -> CodeBlock.of("$N", name);
                case STRING -> CodeBlock.of("$N($N)", TO_JAVA_STRING, name);
                case HANDLE -> CodeBlock.of("($T) $N($N)", TypeName.get(types.erasure(type)), FROM_HANDLE, name);
            });
        }
        for (TypeMirror thrown : method.getThrownTypes()) {
            builder.addException(TypeName.get(thrown));
        }
        builder.addAnnotation(entryPointAnnotation(entry, returnMapping, releaseHandleSymbol, listenerDocs));

        CodeBlock call;
        if (Wrapped.isConstructor(method)) {
            call = CodeBlock.of("new $T($L)", ClassName.get(owner), CodeBlock.join(args, ", "));
        } else {
            CodeBlock target = selfParam == null
                    ? CodeBlock.of("$T", ClassName.get(owner))
                    : CodeBlock.of("(($T) $N($N))", TypeName.get(types.erasure(owner.asType())), FROM_HANDLE, selfParam);
            call = CodeBlock.of("$L.$N($L)", target, method.getSimpleName().toString(), CodeBlock.join(args, ", "));
        }

        switch (returnMapping) {
            case VOID -> builder.addStatement("$L", call);
            case PASS_THROUGH -> builder.addStatement("return $L", call);
            case STRING -> builder.addStatement("return $N($L)", TO_C_STRING, call);
            case HANDLE -> builder.addStatement("return $N($L)", TO_HANDLE, call);
        }
        return builder.build();
    }

    private AnnotationSpec entryPointAnnotation(Entry entry, Mapping returnMapping, String releaseHandleSymbol, List<String> listenerDocs) {
        AnnotationSpec.Builder annotation = AnnotationSpec.builder(C_ENTRY_POINT).addMember("name", "$S", entry.symbol());

        List<String> docs = documentation(entry.method(), returnMapping, releaseHandleSymbol, listenerDocs);
        if (!docs.isEmpty()) {
            CodeBlock lines = CodeBlock.join(docs.stream().map(line -> CodeBlock.of("$S", line)).toList(), ", ");
            annotation.addMember("documentation", docs.size() == 1 ? "$L" : "{$L}", lines);
        }
        if (entry.exceptionHandler() != null) {
            annotation.addMember("exceptionHandler", "$T.class", TypeName.get(entry.exceptionHandler()));
        }
        return annotation.build();
    }

    /** The method's Javadoc, one entry per line, followed by how listeners are called and ownership notes for the returned value. */
    private List<String> documentation(ExecutableElement method, Mapping returnMapping, String releaseHandleSymbol, List<String> listenerDocs) {
        List<String> lines = new ArrayList<>();
        String doc = elements.getDocComment(method);
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
    private void addListeners(TypeSpec.Builder clazz, ClassName generated, Collection<Listener> listeners, Set<Mapping> usedReturns) {
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

        for (Listener listener : listeners) {
            String suffix = listener.suffix(types);
            ClassName callback = generated.nestedClass(suffix + LISTENER);
            TypeName cValue = switch (listener.kind()) {
                case STRING -> C_CHAR_POINTER;
                case BOXED -> TypeName.get(types.getPrimitiveType(listener.primitive(types)));
                case HANDLE -> OBJECT_HANDLE;
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

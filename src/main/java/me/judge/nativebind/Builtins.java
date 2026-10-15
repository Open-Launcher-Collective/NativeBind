package me.judge.nativebind;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Entry points exported alongside the isolate and handle functions so that native callers can work with the
 * objects behind handles of commonly returned types ({@code CompletableFuture}, boxed primitives, strings,
 * collections, {@code Optional}). Each is described once here as plain Java; the C and JNI generators wrap that
 * logic in their own calling conventions.
 * <p>
 * A null handle is treated as a null object and yields a zero / null result. A handle to an object of the wrong
 * type throws, which is a fatal error from C and a {@code RuntimeException} on the JVM.
 */
final class Builtins {
    /** A builtin's parameter or return type, as seen by the generated Java logic. */
    enum Type {
        BOOLEAN(TypeName.BOOLEAN), INT(TypeName.INT), LONG(TypeName.LONG), DOUBLE(TypeName.DOUBLE),
        STRING(ClassName.get(String.class)), HANDLE(ClassName.get(Object.class));

        final TypeName javaType;

        Type(TypeName javaType) {
            this.javaType = javaType;
        }
    }

    record Param(Type type, String name) {
    }

    /** {@code body} computes the result from parameters of their {@link Type#javaType}. */
    record Builtin(String name, String doc, Type returns, List<Param> params, CodeBlock body) {
        /** The private method holding the logic, called by the generated entry point. */
        String implName() {
            return "nativeBind$" + name;
        }

        MethodSpec impl() {
            MethodSpec.Builder method = MethodSpec.methodBuilder(implName())
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(returns.javaType)
                    .addCode(body);
            params.forEach(p -> method.addParameter(p.type().javaType, p.name()));
            return method.build();
        }
    }

    static final String DESCRIBE = "nativeBind$describe";

    private static final TypeName FUTURE = ParameterizedTypeName.get(ClassName.get(Future.class), WildcardTypeName.subtypeOf(Object.class));

    static final List<Builtin> ALL = List.of(
            new Builtin("futureIsDone",
                    "Whether the Future behind the handle has completed, normally, exceptionally or by cancellation.",
                    Type.BOOLEAN, List.of(new Param(Type.HANDLE, "future")),
                    CodeBlock.builder()
                            .addStatement("return future != null && (($T) future).isDone()", FUTURE)
                            .build()),
            new Builtin("futureAwait",
                    "Waits up to timeoutMillis (forever when negative) for the Future behind the handle to complete; returns whether it has.",
                    Type.BOOLEAN, List.of(new Param(Type.HANDLE, "future"), new Param(Type.LONG, "timeoutMillis")),
                    CodeBlock.builder()
                            .beginControlFlow("if (future == null)")
                            .addStatement("return false")
                            .endControlFlow()
                            .addStatement("$T f = ($T) future", FUTURE, FUTURE)
                            .beginControlFlow("try")
                            .beginControlFlow("if (timeoutMillis < 0)")
                            .addStatement("f.get()")
                            .nextControlFlow("else")
                            .addStatement("f.get(timeoutMillis, $T.MILLISECONDS)", TimeUnit.class)
                            .endControlFlow()
                            .nextControlFlow("catch ($T e)", InterruptedException.class)
                            .addStatement("$T.currentThread().interrupt()", Thread.class)
                            .nextControlFlow("catch ($T | $T | $T e)", ExecutionException.class, CancellationException.class, TimeoutException.class)
                            .add("// Failure and timeout are both visible through isDone() and futureError\n")
                            .endControlFlow()
                            .addStatement("return f.isDone()")
                            .build()),
            new Builtin("futureGet",
                    "Waits for the Future behind the handle and returns a handle to its result. Returns a null handle if the result is null or the future failed or was cancelled; futureError tells those apart.",
                    Type.HANDLE, List.of(new Param(Type.HANDLE, "future")),
                    CodeBlock.builder()
                            .beginControlFlow("if (future == null)")
                            .addStatement("return null")
                            .endControlFlow()
                            .beginControlFlow("try")
                            .addStatement("return (($T) future).get()", FUTURE)
                            .nextControlFlow("catch ($T e)", InterruptedException.class)
                            .addStatement("$T.currentThread().interrupt()", Thread.class)
                            .addStatement("return null")
                            .nextControlFlow("catch ($T | $T e)", ExecutionException.class, CancellationException.class)
                            .addStatement("return null")
                            .endControlFlow()
                            .build()),
            new Builtin("futureError",
                    "Describes why the completed Future behind the handle failed (\"<exception class>: <message>\"), or returns null if it succeeded or has not completed.",
                    Type.STRING, List.of(new Param(Type.HANDLE, "future")),
                    CodeBlock.builder()
                            .beginControlFlow("if (future == null || !(($T) future).isDone())", FUTURE)
                            .addStatement("return null")
                            .endControlFlow()
                            .beginControlFlow("try")
                            .addStatement("(($T) future).get()", FUTURE)
                            .addStatement("return null")
                            .nextControlFlow("catch ($T e)", ExecutionException.class)
                            .addStatement("return $N(e.getCause() == null ? e : e.getCause())", DESCRIBE)
                            .nextControlFlow("catch ($T | $T e)", CancellationException.class, InterruptedException.class)
                            .addStatement("return $N(e)", DESCRIBE)
                            .endControlFlow()
                            .build()),
            new Builtin("futureCancel",
                    "Cancels the Future behind the handle, interrupting it if running; returns whether it was cancelled.",
                    Type.BOOLEAN, List.of(new Param(Type.HANDLE, "future")),
                    CodeBlock.builder()
                            .addStatement("return future != null && (($T) future).cancel(true)", FUTURE)
                            .build()),
            new Builtin("handleToString",
                    "Returns the toString() of the object behind the handle, or null for a null handle.",
                    Type.STRING, List.of(new Param(Type.HANDLE, "value")),
                    CodeBlock.builder()
                            .addStatement("return value == null ? null : value.toString()")
                            .build()),
            new Builtin("stringToHandle",
                    "Returns a handle to a Java String copied from the given string, for passing to functions that take objects.",
                    Type.HANDLE, List.of(new Param(Type.STRING, "value")),
                    CodeBlock.builder()
                            .addStatement("return value")
                            .build()),
            new Builtin("unboxBoolean",
                    "Returns the value of the Boolean behind the handle; false for a null handle.",
                    Type.BOOLEAN, List.of(new Param(Type.HANDLE, "value")),
                    CodeBlock.builder()
                            .addStatement("return value != null && ($T) value", Boolean.class)
                            .build()),
            new Builtin("unboxLong",
                    "Returns the value of the Number (Integer, Long, ...) behind the handle as a long; 0 for a null handle.",
                    Type.LONG, List.of(new Param(Type.HANDLE, "value")),
                    CodeBlock.builder()
                            .addStatement("return value == null ? 0 : (($T) value).longValue()", Number.class)
                            .build()),
            new Builtin("unboxDouble",
                    "Returns the value of the Number (Double, Float, ...) behind the handle as a double; 0 for a null handle.",
                    Type.DOUBLE, List.of(new Param(Type.HANDLE, "value")),
                    CodeBlock.builder()
                            .addStatement("return value == null ? 0 : (($T) value).doubleValue()", Number.class)
                            .build()),
            new Builtin("collectionSize",
                    "Returns the number of elements in the Collection, Map or array behind the handle; 0 for a null handle.",
                    Type.INT, List.of(new Param(Type.HANDLE, "collection")),
                    CodeBlock.builder()
                            .beginControlFlow("if (collection == null)")
                            .addStatement("return 0")
                            .endControlFlow()
                            .beginControlFlow("if (collection instanceof $T<?> c)", Collection.class)
                            .addStatement("return c.size()")
                            .endControlFlow()
                            .beginControlFlow("if (collection instanceof $T<?, ?> m)", Map.class)
                            .addStatement("return m.size()")
                            .endControlFlow()
                            .addStatement("return $T.getLength(collection)", Array.class)
                            .build()),
            new Builtin("listGet",
                    "Returns a handle to the element at index in the List or array behind the handle (primitive elements are boxed).",
                    Type.HANDLE, List.of(new Param(Type.HANDLE, "list"), new Param(Type.INT, "index")),
                    CodeBlock.builder()
                            .beginControlFlow("if (list instanceof $T<?> l)", List.class)
                            .addStatement("return l.get(index)")
                            .endControlFlow()
                            .addStatement("return $T.get(list, index)", Array.class)
                            .build()),
            new Builtin("mapKeys",
                    "Returns a handle to a List of the keys of the Map behind the handle, to be read with listGet.",
                    Type.HANDLE, List.of(new Param(Type.HANDLE, "map")),
                    CodeBlock.builder()
                            .addStatement("return map == null ? null : new $T<>((($T<?, ?>) map).keySet())", ArrayList.class, Map.class)
                            .build()),
            new Builtin("mapGet",
                    "Returns a handle to the value the Map behind the handle associates with the key behind the key handle, or a null handle if there is none.",
                    Type.HANDLE, List.of(new Param(Type.HANDLE, "map"), new Param(Type.HANDLE, "key")),
                    CodeBlock.builder()
                            .addStatement("return map == null ? null : (($T<?, ?>) map).get(key)", Map.class)
                            .build()),
            new Builtin("optionalGet",
                    "Returns a handle to the value of the Optional behind the handle, or a null handle if it is empty.",
                    Type.HANDLE, List.of(new Param(Type.HANDLE, "optional")),
                    CodeBlock.builder()
                            .addStatement("return optional == null ? null : (($T<?>) optional).orElse(null)", Optional.class)
                            .build()));

    private Builtins() {
    }

    /** Formats a failure the way exceptions are reported to the JVM: {@code "<exception class>: <message>"}. */
    static MethodSpec describe() {
        return MethodSpec.methodBuilder(DESCRIBE)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(Throwable.class, "error")
                .addStatement("return error.getMessage() == null ? error.getClass().getName() : error.getClass().getName() + $S + error.getMessage()", ": ")
                .build();
    }
}

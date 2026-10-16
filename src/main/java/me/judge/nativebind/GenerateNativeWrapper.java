package me.judge.nativebind;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a GraalVM native-image {@code @CEntryPoint} that wraps the annotated method.
 * <p>
 * The wrapper lives in {@code <EnclosingClass>CEntryPoints}, in the same package as the annotated
 * method's class. Its exported symbol is {@code prefix + Capitalized(methodName)}, e.g. a method
 * {@code attemptLogin} with prefix {@code judgeLib} becomes {@code judgeLibAttemptLogin}. A prefix
 * ending in {@code _} is joined verbatim instead ({@code judgelib_} gives {@code judgelib_attemptLogin}).
 * <p>
 * Parameter and return types are mapped as follows:
 * <ul>
 *     <li>primitives and {@code org.graalvm.word.WordBase} subtypes are passed through unchanged</li>
 *     <li>{@link String} becomes {@code CCharPointer}; a returned string is {@code malloc}-ed and must be
 *     released by the caller with {@code free()}</li>
 *     <li>any other reference type becomes an {@code ObjectHandle} from {@code ObjectHandles.getGlobal()}</li>
 *     <li>for instance methods, the receiver is passed as a leading {@code ObjectHandle} parameter</li>
 * </ul>
 * If the enclosing class is annotated with {@code @CHeader}, it is copied onto the generated class.
 * <p>
 * Exactly one {@code NativeBindCEntryPoints} class is generated per compilation, however many classes use this
 * annotation. It exports {@code <prefix>CreateIsolate}, {@code <prefix>TearDownIsolate} and
 * {@code <prefix>ReleaseHandle}. Its prefix is the {@code nativebind.prefix} option, or the one prefix shared by
 * every annotated method (mixed prefixes require the option). Its package is the {@code nativebind.package}
 * option, or the common package of every annotated class. It gets a {@code @CHeader} only if all annotated
 * classes agree on one.
 * <p>
 * The same class exports functions for working with handles to commonly returned types, each named
 * {@code <prefix><Name>} and taking the {@code IsolateThread} first:
 * <ul>
 *     <li>{@code CompletableFuture} / {@code Future}: {@code futureIsDone}, {@code futureAwait(future, timeoutMillis)},
 *     {@code futureGet}, {@code futureError}, {@code futureCancel}, and {@code futureOnComplete(future, callback,
 *     userData)}, which calls {@code void callback(graal_isolatethread_t *, void *userData)} on completion</li>
 *     <li>values: {@code handleToString}, {@code stringToHandle}, {@code unboxBoolean}, {@code unboxLong},
 *     {@code unboxDouble}</li>
 *     <li>containers: {@code collectionSize}, {@code listGet}, {@code mapKeys}, {@code mapGet}, {@code optionalGet}</li>
 * </ul>
 */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateNativeWrapper {
    /**
     * Prefix for the exported C symbol. Must be a valid C identifier fragment ({@code [A-Za-z_][A-Za-z0-9_]*}).
     * When empty, falls back to the {@code nativebind.prefix} processor option ({@code -Anativebind.prefix=...}).
     */
    String prefix() default "";

    /**
     * Replaces the method name as the base of the exported symbol (the prefix is still applied).
     * Required to disambiguate overloaded methods.
     */
    String name() default "";

    /**
     * Passed through to {@code CEntryPoint.exceptionHandler}. {@code void.class} keeps GraalVM's default
     * (a fatal error on any uncaught exception).
     */
    Class<?> exceptionHandler() default void.class;
}

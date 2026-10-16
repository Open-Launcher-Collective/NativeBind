package me.judge.nativebind;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a GraalVM native-image {@code @CEntryPoint} that a JVM can bind to a {@code native} method, wrapping
 * the annotated method.
 * <p>
 * The wrappers live in {@code <EnclosingClass>JNIEntryPoints}, in the same package as the annotated method's class.
 * Each one is exported under the JNI name for {@code static native} method {@code methodName} of
 * {@link #jniClass()}, e.g. {@code Java_org_angelauramc_judgelib_Entrypoints_attemptLogin}. Names are mangled as the
 * JNI specification requires ({@code _} becomes {@code _1}, {@code $} becomes {@code _00024}, ...), and overloaded
 * methods are exported under JNI long names ({@code ..._attemptLogin__Ljava_lang_String_2J}) so overloads work as
 * they do in Java.
 * <p>
 * Every JVM-side native method takes the {@code IsolateThread} (from {@code createIsolate()}) as its last
 * {@code long} parameter. Parameter and return types are mapped as follows (native side / JVM side):
 * <ul>
 *     <li>primitives are passed through unchanged</li>
 *     <li>{@link String} / {@code String[]} and primitive arrays are copied between heaps with {@code JNIUtil}</li>
 *     <li>{@code JNI.JObject} subtypes are passed through as JNI references ({@code JString} / {@code String},
 *     {@code JClass} / {@code Class}, other {@code JObject}s / {@code Object}, ...), so the wrapped method can make
 *     JNI calls on them</li>
 *     <li>a {@code JNI.JNIEnv} parameter receives the caller's environment and does not appear on the JVM side</li>
 *     <li>other {@code WordBase} types (pointers, {@code ObjectHandle}) / {@code long}</li>
 *     <li>any other reference type / a {@code long} {@code ObjectHandle} from {@code ObjectHandles.getGlobal()}</li>
 *     <li>for instance methods, the receiver is passed as a leading {@code long} handle</li>
 * </ul>
 * Each call runs inside a {@code JNIMethodScope}. Any exception is rethrown in the JVM as a
 * {@code java.lang.RuntimeException} whose message is {@code "<exception class>: <message>"}; an exception already
 * pending in the JVM (e.g. from an upcall) propagates unchanged.
 * <p>
 * {@code org.graalvm.sdk:jniutils} is used only inside the native image. Nothing generated for the JVM side
 * references it, so the JVM side (including Android) must not, and does not need to, depend on it.
 * <p>
 * NativeBind generates each JVM-side class, declaring all of its {@code static native} methods with the original
 * Javadoc. With {@code -Anativebind.jni.generateClass=false} it instead checks a class you wrote yourself against
 * the entry points.
 * <p>
 * Exactly one {@code NativeBindJNIEntryPoints} class is generated per compilation, however many classes use this
 * annotation. It backs {@code createIsolate()}, {@code tearDownIsolate(long)} and {@code releaseHandle(long, long)}
 * on the JVM class named by the {@code nativebind.jni.class} option, or on the one class every annotated method
 * uses (mixed classes require the option). Alongside it, NativeBind generates a {@code Feature} that registers the
 * {@code NativeBridgeSupport} jniutils needs, and a {@code native-image.properties} that enables it.
 * <p>
 * The same class also backs native methods for working with handles to commonly returned types:
 * {@code futureIsDone}, {@code futureAwait}, {@code futureGet}, {@code futureError}, {@code futureCancel},
 * {@code handleToString}, {@code stringToHandle}, {@code unboxBoolean}, {@code unboxLong}, {@code unboxDouble},
 * {@code collectionSize}, {@code listGet}, {@code mapKeys}, {@code mapGet} and {@code optionalGet}. A hand-written
 * JVM class only needs to declare the ones it uses.
 */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateJNIWrapper {
    /**
     * Binary name of the JVM-side class declaring the {@code native} method, e.g.
     * {@code org.angelauramc.judgelib.Entrypoints}. When empty, falls back to the {@code nativebind.jni.class}
     * processor option ({@code -Anativebind.jni.class=...}).
     */
    String jniClass() default "";

    /** Name of the JVM-side native method. Defaults to the annotated method's name. */
    String name() default "";
}

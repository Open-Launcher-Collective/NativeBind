import me.judge.nativebind.NativeBindProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MethodTest {
    /** Kept after the run so the generated sources can be inspected: target/nativebind-tests/<test method>/generated */
    private Path out;

    @BeforeEach
    void cleanOutput(TestInfo test) throws IOException {
        out = Path.of("target", "nativebind-tests", test.getTestMethod().orElseThrow().getName());
        if (Files.exists(out)) {
            try (var paths = Files.walk(out)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private record Result(boolean success, String errors, Path generated, Path classes) {
        /** The generated source with whitespace collapsed, so assertions don't depend on line wrapping. */
        String source(String relativePath) throws IOException {
            return Files.readString(generated.resolve(relativePath))
                    .replaceAll("\\s+", " ")
                    .replace("( ", "(")
                    .replace(" )", ")");
        }
    }

    private Result compile(List<String> options, String className, String source) throws IOException {
        return compile(options, Map.of(className, source));
    }

    private Result compile(List<String> options, Map<String, String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path classes = Files.createDirectories(out.resolve("classes"));
        Path generated = Files.createDirectories(out.resolve("generated"));
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            files.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
            List<JavaFileObject> units = new ArrayList<>();
            sources.forEach((className, source) -> units.add(new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            }));
            List<String> args = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path")));
            args.addAll(options);
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics, args, null, units);
            task.setProcessors(List.of(new NativeBindProcessor()));
            boolean success = task.call();
            String errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(null))
                    .collect(Collectors.joining("\n"));
            return new Result(success, errors, generated, classes);
        }
    }

    @Test
    public void generatesEntryPointsMatchingHandWrittenStyle() throws IOException {
        Result result = compile(List.of("-Anativebind.prefix=judgeLib"), "demo.Api", """
                package demo;

                import java.util.List;
import java.util.Map;
                import me.judge.nativebind.GenerateNativeWrapper;
                import org.graalvm.nativeimage.c.CHeader;
                import org.graalvm.nativeimage.c.type.CCharPointer;

                @CHeader(Api.Header.class)
                public class Api {
                    public static final class Header implements CHeader.Header {
                        public String name() { return "demo"; }
                    }

                    /**
                     * Logs in as the given user.
                     */
                    @GenerateNativeWrapper
                    public static List<String> attemptLogin(String username) throws java.io.IOException { return List.of(username); }

                    @GenerateNativeWrapper
                    static String getAccountUsername(List<String> account, int index) { return account.get(index); }

                    @GenerateNativeWrapper(prefix = "demo_", name = "raw")
                    public static boolean passThrough(CCharPointer pointer, long value) { return pointer.isNull(); }

                    @GenerateNativeWrapper
                    public void instanceCall$v2() {}
                }
                """);
        assertTrue(result.success(), result.errors());

        String source = result.source("demo/ApiCEntryPoints.java");
        assertTrue(source.contains("@CHeader(Api.Header.class)"), source);
        assertTrue(source.contains("@CEntryPoint(name = \"judgeLibAttemptLogin\""), source);
        assertTrue(source.contains("\"Logs in as the given user.\""), source);
        assertTrue(source.contains("public static ObjectHandle judgeLibAttemptLogin(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, CCharPointer username) throws IOException"), source);
        assertTrue(source.contains("return nativeBind$toHandle(Api.attemptLogin(nativeBind$toJavaString(username)));"), source);
        assertTrue(source.contains("public static CCharPointer judgeLibGetAccountUsername(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle account, int index)"), source);
        assertTrue(source.contains("return nativeBind$toCString(Api.getAccountUsername((List) nativeBind$fromHandle(account), index));"), source);
        assertTrue(source.contains("public static boolean demo_raw(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, CCharPointer pointer, long value)"), source);
        assertTrue(source.contains("public static void judgeLibInstanceCall_v2(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle self)"), source);
        assertTrue(source.contains("((Api) nativeBind$fromHandle(self)).instanceCall$v2();"), source);
        assertTrue(source.contains("\"The returned handle keeps its object alive until it is passed to judgeLibReleaseHandle().\""), source);

        String support = result.source("demo/NativeBindCEntryPoints.java");
        assertTrue(support.contains("@CHeader(Api.Header.class)"), support);
        assertTrue(support.contains("@CEntryPoint(name = \"judgeLibCreateIsolate\", builtin = CEntryPoint.Builtin.CREATE_ISOLATE"), support);
        assertTrue(support.contains("public static native IsolateThread createIsolate();"), support);
        assertTrue(support.contains("@CEntryPoint(name = \"judgeLibTearDownIsolate\", builtin = CEntryPoint.Builtin.TEAR_DOWN_ISOLATE"), support);
        assertTrue(support.contains("public static native int tearDownIsolate(IsolateThread thread);"), support);
        assertTrue(support.contains("public static void releaseHandle(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle handle)"), support);
        assertTrue(support.contains("ObjectHandles.getGlobal().destroy(handle);"), support);
    }

    @Test
    public void wrapsConstructorsAsFactories() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.class=demo.jvm.Entrypoints"), "demo.Account", """
                package demo;

                import me.judge.nativebind.GenerateJNIWrapper;
                import me.judge.nativebind.GenerateNativeWrapper;

                public class Account {
                    /** Creates an account. */
                    @GenerateNativeWrapper(prefix = "lib")
                    @GenerateJNIWrapper
                    public Account(String username, int id) {}

                    @GenerateNativeWrapper(prefix = "lib", name = "guestAccount")
                    @GenerateJNIWrapper
                    Account() throws java.io.IOException {}
                }
                """);
        assertTrue(result.success(), result.errors());

        // Constructors default to "new" + the class name and hand back a handle to the new object
        String c = result.source("demo/AccountCEntryPoints.java");
        assertTrue(c.contains("@CEntryPoint(name = \"libNewAccount\""), c);
        assertTrue(c.contains("\"Creates an account.\""), c);
        assertTrue(c.contains("public static ObjectHandle libNewAccount(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, CCharPointer username, int id)"), c);
        assertTrue(c.contains("return nativeBind$toHandle(new Account(nativeBind$toJavaString(username), id));"), c);
        assertTrue(c.contains("public static ObjectHandle libGuestAccount(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread) throws IOException"), c);
        assertTrue(c.contains("return nativeBind$toHandle(new Account());"), c);
        assertFalse(c.contains("<init>"), c);
        assertFalse(c.contains("ObjectHandle self"), c);

        String jvm = result.source("demo/jvm/Entrypoints.java");
        assertTrue(jvm.contains("Creates an account."), jvm);
        assertTrue(jvm.contains("public static native long newAccount(String username, int id, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native long newAccount(long isolateThread);"), jvm);
        assertFalse(jvm.contains("<init>"), jvm);

        String jni = result.source("demo/AccountJNIEntryPoints.java");
        // Overloaded, so the long names carrying the argument descriptors are used
        assertTrue(jni.contains("name = \"Java_demo_jvm_Entrypoints_newAccount__Ljava_lang_String_2IJ\""), jni);
        assertTrue(jni.contains("name = \"Java_demo_jvm_Entrypoints_newAccount__J\""), jni);
        assertTrue(jni.contains("try (JNIMethodScope nativeBind$scope = new JNIMethodScope(\"demo.Account.<init>\", env))"), jni);
        assertTrue(jni.contains("return nativeBind$toHandle(new Account(JNIUtil.createString(env, username), id));"), jni);
        assertTrue(jni.contains("return nativeBind$toHandle(new Account());"), jni);
        assertFalse(jni.contains("ObjectHandle self"), jni);
    }

    @Test
    public void wrapsFieldsAsGettersAndSetters() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.class=demo.jvm.Entrypoints"), "demo.Account", """
                package demo;

                import java.util.List;
                import me.judge.nativebind.GenerateJNIWrapper;
                import me.judge.nativebind.GenerateNativeWrapper;

                public class Account {
                    /** The account's display name. */
                    @GenerateNativeWrapper(prefix = "lib")
                    @GenerateJNIWrapper
                    public String username;

                    @GenerateNativeWrapper(prefix = "lib", name = "count")
                    @GenerateJNIWrapper(name = "count")
                    static int instances;

                    @GenerateNativeWrapper(prefix = "lib")
                    @GenerateJNIWrapper
                    public final List<String> roles = List.of();
                }
                """);
        assertTrue(result.success(), result.errors());

        String c = result.source("demo/AccountCEntryPoints.java");
        assertTrue(c.contains("@CEntryPoint(name = \"libGetUsername\", documentation = {\"The account's display name.\", \"The returned string is allocated with malloc"), c);
        assertTrue(c.contains("public static CCharPointer libGetUsername(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle self)"), c);
        assertTrue(c.contains("return nativeBind$toCString(((Account) nativeBind$fromHandle(self)).username);"), c);
        assertTrue(c.contains("public static void libSetUsername(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle self, CCharPointer username)"), c);
        assertTrue(c.contains("((Account) nativeBind$fromHandle(self)).username = nativeBind$toJavaString(username);"), c);
        assertTrue(c.contains("public static int libGetCount(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread)"), c);
        assertTrue(c.contains("return Account.instances;"), c);
        assertTrue(c.contains("public static void libSetCount(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, int instances)"), c);
        assertTrue(c.contains("Account.instances = instances;"), c);
        assertTrue(c.contains("public static ObjectHandle libGetRoles(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle self)"), c);
        // Final fields only get a getter
        assertFalse(c.contains("SetRoles"), c);

        String jvm = result.source("demo/jvm/Entrypoints.java");
        assertTrue(jvm.contains("The account's display name."), jvm);
        assertTrue(jvm.contains("Reads {@code Account.username} in the native image."), jvm);
        assertTrue(jvm.contains("Assigns {@code Account.username} in the native image."), jvm);
        assertTrue(jvm.contains("public static native String getUsername(long self, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native void setUsername(long self, String username, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native int getCount(long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native void setCount(int instances, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native long getRoles(long self, long isolateThread);"), jvm);
        assertFalse(jvm.contains("setRoles"), jvm);

        String jni = result.source("demo/AccountJNIEntryPoints.java");
        assertTrue(jni.contains("name = \"Java_demo_jvm_Entrypoints_getUsername\""), jni);
        assertTrue(jni.contains("try (JNIMethodScope nativeBind$scope = new JNIMethodScope(\"demo.Account.getUsername\", env))"), jni);
        assertTrue(jni.contains("return JNIUtil.createHSString(env, ((Account) nativeBind$fromHandle(self)).username);"), jni);
        assertTrue(jni.contains("((Account) nativeBind$fromHandle(self)).username = JNIUtil.createString(env, username);"), jni);
        assertTrue(jni.contains("return Account.instances;"), jni);
        assertTrue(jni.contains("Account.instances = instances;"), jni);
        assertTrue(jni.contains("return nativeBind$toHandle(((Account) nativeBind$fromHandle(self)).roles);"), jni);
    }

    @Test
    public void rejectsFieldsThatCannotBeReached() throws IOException {
        Result result = compile(List.of(), "demo.Api", """
                package demo;

                import me.judge.nativebind.GenerateNativeWrapper;

                public class Api {
                    @GenerateNativeWrapper(prefix = "lib")
                    private int hidden;

                    @GenerateNativeWrapper(prefix = "lib")
                    int value;

                    @GenerateNativeWrapper(prefix = "lib")
                    public int getValue() { return value; }
                }
                """);
        assertFalse(result.success());
        String errors = result.errors();
        assertTrue(errors.contains("@GenerateNativeWrapper members must not be private"), errors);
        assertTrue(errors.contains("symbol \"libGetValue\" is already exported by demo.Api.value (getter)"), errors);
        assertEquals(2, errors.lines().count(), errors);
    }

    @Test
    public void rejectsConstructorsThatCannotBeCalled() throws IOException {
        Result result = compile(List.of(), "demo.Outer", """
                package demo;

                import me.judge.nativebind.GenerateNativeWrapper;

                public class Outer {
                    public abstract static class Shape {
                        @GenerateNativeWrapper(prefix = "lib")
                        public Shape() {}
                    }

                    public class Inner {
                        @GenerateNativeWrapper(prefix = "lib")
                        public Inner() {}
                    }

                    public static class Nested {
                        @GenerateNativeWrapper(prefix = "lib")
                        public Nested() {}
                    }
                }
                """);
        assertFalse(result.success());
        String errors = result.errors();
        assertTrue(errors.contains("constructors must belong to a class that can be instantiated"), errors);
        assertTrue(errors.contains("constructors must not belong to an inner (non-static) class"), errors);
        assertEquals(2, errors.lines().count(), errors);
    }

    @Test
    public void passesEnumsAsCEnums() throws IOException {
        Result result = compile(List.of(), Map.of(
                "demo.Status", """
                        package demo;

                        enum Status { OK, inProgress, FAILED }
                        """,
                "demo.Api", """
                        package demo;

                        import me.judge.nativebind.GenerateNativeWrapper;
                        import org.graalvm.nativeimage.c.CContext;
                        import org.graalvm.nativeimage.c.constant.CEnum;

                        public class Api {
                            public static final class Directives implements CContext.Directives {}

                            @CContext(Directives.class)
                            @CEnum("level_t")
                            public enum Level { LOW, HIGH }

                            public enum Mode { FAST, SAFE }

                            @GenerateNativeWrapper(prefix = "lib")
                            public static Status retry(Status previous, Mode mode) { return previous; }

                            @GenerateNativeWrapper(prefix = "lib")
                            public static Level level(Level level) { return level; }

                            @GenerateNativeWrapper(prefix = "lib")
                            public static Status status;
                        }
                        """));
        assertTrue(result.success(), result.errors());

        String c = result.source("demo/ApiCEntryPoints.java");
        assertTrue(c.contains("public static StatusCEnum libRetry(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, StatusCEnum previous, Api_ModeCEnum mode)"), c);
        assertTrue(c.contains("return StatusCEnum.fromJava(Api.retry(StatusCEnum.toJava(previous), Api_ModeCEnum.toJava(mode)));"), c);
        assertTrue(c.contains("\"A previous outside its enumeration reaches the Java code as null.\""), c);
        assertTrue(c.contains("\"A null result from the Java code cannot be represented and fails the call.\""), c);
        // Enums that are already @CEnum are left to native-image
        assertTrue(c.contains("public static Api.Level libLevel(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, Api.Level level)"), c);
        assertTrue(c.contains("return Api.level(level);"), c);
        assertTrue(c.contains("Api.status = StatusCEnum.toJava(status);"), c);
        assertFalse(Files.exists(result.generated().resolve("demo/Api_LevelCEnum.java")));

        String status = result.source("demo/StatusCEnum.java");
        assertTrue(status.contains("@CContext(StatusCEnum.Directives.class) @CEnum(\"libStatus\") public enum StatusCEnum"), status);
        assertTrue(status.contains("@CEnumConstant(\"LIB_STATUS_OK\") OK, @CEnumConstant(\"LIB_STATUS_IN_PROGRESS\") inProgress, @CEnumConstant(\"LIB_STATUS_FAILED\") FAILED;"), status);
        assertTrue(status.contains("@CEnumValue public native int toC();"), status);
        assertTrue(status.contains("@CEnumLookup public static native StatusCEnum fromC(int value);"), status);
        assertTrue(status.contains("public static Status toJava(StatusCEnum value) { return value == null ? null : JAVA[value.ordinal()]; }"), status);
        assertTrue(status.contains("public static StatusCEnum fromJava(Status value) { return value == null ? null : MIRRORS[value.ordinal()]; }"), status);
        assertTrue(status.contains("public static final class Directives implements CContext.Directives"), status);
        assertTrue(result.source("demo/Api_ModeCEnum.java").contains("@CEnum(\"libApi_Mode\")"));

        // The mirrors, and the enums their static initializers read, are initialized at build time by the C feature
        String feature = result.source("demo/NativeBindCFeature.java");
        assertTrue(feature.contains("public final class NativeBindCFeature implements Feature { private final List<Class<?>> mirrors = new ArrayList<>(); public NativeBindCFeature() { }"), feature);
        assertTrue(feature.contains("Enumeration<URL> lists = access.getApplicationClassLoader().getResources(\"META-INF/nativebind/demo/build-time-classes\");"), feature);
        assertTrue(feature.contains("RuntimeClassInitialization.initializeAtBuildTime(type);"), feature);
        assertEquals("Args = --features=demo.NativeBindCFeature\n",
                Files.readString(result.classes().resolve("META-INF/native-image/nativebind/demo/c/native-image.properties")));
        // Once the image is written, the definitions go beside its headers, which include those they use
        assertTrue(feature.contains("public void afterImageWrite(Feature.AfterImageWriteAccess access)"), feature);
        assertTrue(feature.contains("headers.put(mirror.getAnnotation(CEnum.class).value(), (String) mirror.getField(\"HEADER\").get(null));"), feature);
        assertTrue(feature.contains("Files.writeString(directory.resolve(header.getKey() + \".h\"), header.getValue());"), feature);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
            Method includeEnums = loader.loadClass("demo.NativeBindCFeature").getDeclaredMethod("includeEnums", String.class, Collection.class);
            includeEnums.setAccessible(true);
            String imageHeader = """
                    #ifndef __DEMO_H
                    #define __DEMO_H

                    #include <graal_isolate.h>

                    libStatus libRetry(graal_isolatethread_t*, libStatus, libApi_Mode);

                    #endif
                    """;
            String included = (String) includeEnums.invoke(null, imageHeader, List.of("libStatus", "libApi_Mode", "libUnused"));
            assertEquals("""
                    #ifndef __DEMO_H
                    #define __DEMO_H
                    #include "libStatus.h"
                    #include "libApi_Mode.h"

                    #include <graal_isolate.h>

                    libStatus libRetry(graal_isolatethread_t*, libStatus, libApi_Mode);

                    #endif
                    """, included);
            // Running again (or on a header that uses none of them) leaves it as it is
            assertEquals(included, includeEnums.invoke(null, included, List.of("libStatus", "libApi_Mode")));
            assertEquals("#include <graal_isolate.h>\n", includeEnums.invoke(null, "#include <graal_isolate.h>\n", List.of("libStatus")));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        // @CEnum enums are the user's own, so they are left alone
        assertEquals("demo.StatusCEnum\ndemo.Status\ndemo.Api_ModeCEnum\ndemo.Api$Mode\n",
                Files.readString(result.classes().resolve("META-INF/nativebind/demo/build-time-classes")));

        String header = Files.readString(result.classes().resolve("META-INF/nativebind/include/libStatus.h"));
        assertEquals("""
                #ifndef NATIVEBIND_LIB_STATUS_H
                #define NATIVEBIND_LIB_STATUS_H

                /* Mirrors demo.Status */
                typedef enum libStatus {
                    LIB_STATUS_OK = 0,
                    LIB_STATUS_IN_PROGRESS = 1,
                    LIB_STATUS_FAILED = 2
                } libStatus;

                #endif
                """, header);
    }

    @Test
    public void passesEnumsToTheJvmAsMocks() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.class=demo.jvm.Entrypoints"), Map.of(
                "demo.Status", """
                        package demo;

                        /** How a request went. */
                        public enum Status { OK, inProgress, FAILED }
                        """,
                "demo.Api", """
                        package demo;

                        import me.judge.nativebind.GenerateJNIWrapper;
                        import org.graalvm.nativeimage.c.CContext;
                        import org.graalvm.nativeimage.c.constant.CEnum;

                        public class Api {
                            public static final class Directives implements CContext.Directives {}

                            @CContext(Directives.class)
                            @CEnum
                            public enum Level { LOW, HIGH }

                            @GenerateJNIWrapper
                            public static Status retry(Status previous, Level level) { return previous; }

                            @GenerateJNIWrapper
                            public static Status status;
                        }
                        """));
        assertTrue(result.success(), result.errors());

        String jvm = result.source("demo/jvm/Entrypoints.java");
        assertTrue(jvm.contains("public static native Status retry(Status previous, Api_Level level, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native void setStatus(Status status, long isolateThread);"), jvm);
        // Mocks hold the constants only, in the same order, so that ordinals match
        assertTrue(jvm.contains("How a request went. * <p> * Stands in for {@code demo.Status}, which native methods pass as the constant with the same ordinal. */ public enum Status { OK, inProgress, FAILED }"), jvm);
        assertTrue(jvm.contains("public enum Api_Level { LOW, HIGH }"), jvm);
        assertFalse(jvm.contains("import demo."), jvm);
        assertFalse(jvm.contains("org.graalvm"), jvm);

        String jni = result.source("demo/ApiJNIEntryPoints.java");
        assertTrue(jni.contains("public static JNI.JObject Java_demo_jvm_Entrypoints_retry(JNI.JNIEnv env, JNI.JClass clazz, JNI.JObject previous, JNI.JObject level, @CEntryPoint.IsolateThreadContext IsolateThread isolateThread)"), jni);
        assertTrue(jni.contains("return nativeBind$enumConstant(env, \"demo/jvm/Entrypoints$Status\", nativeBind$toOrdinal(Api.retry((Status) nativeBind$fromOrdinal(Status.values(), nativeBind$ordinal(env, previous)), (Api.Level) nativeBind$fromOrdinal(Api.Level.values(), nativeBind$ordinal(env, level)))));"), jni);
        assertTrue(jni.contains("Api.status = (Status) nativeBind$fromOrdinal(Status.values(), nativeBind$ordinal(env, status));"), jni);
        assertTrue(jni.contains("return env.getFunctions().getCallIntMethodA().call(env, value, ordinal, WordFactory.nullPointer());"), jni);
        assertTrue(jni.contains("CTypeConversion.toCString(\"()[L\" + type + \";\")"), jni);
        assertTrue(jni.contains("JNI.JObject constant = JNIUtil.GetObjectArrayElement(env, constants, ordinal);"), jni);
    }

    @Test
    public void verifiesHandWrittenEnumMocks() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.generateClass=false"), Map.of(
                "demo.Natives", """
                        package demo;

                        public class Natives {
                            public enum Status { OK, FAILED, inProgress }

                            public static native long createIsolate();
                            public static native int tearDownIsolate(long isolateThread);
                            public static native void releaseHandle(long handle, long isolateThread);
                            public static native Status status(long isolateThread);
                            public static native void mode(Object mode, long isolateThread);
                        }
                        """,
                "demo.Status", """
                        package demo;

                        public enum Status { OK, inProgress, FAILED }
                        """,
                "demo.Api", """
                        package demo;

                        public class Api {
                            public enum Mode { FAST }

                            @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives")
                            public static demo.Status status() { return null; }

                            @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives")
                            public static void mode(Mode mode) {}
                        }
                        """));
        assertFalse(result.success());
        String errors = result.errors();
        assertTrue(errors.contains("demo.Natives has no static native method mode(Ldemo/Natives$Api_Mode;J)V"), errors);
        assertTrue(errors.contains("demo.Natives.Status must declare the constants of demo.Status in the same order: [OK, inProgress, FAILED]"), errors);
        assertTrue(errors.contains("demo.Natives must declare enum Api_Mode with the constants of demo.Api.Mode in the same order: [FAST]"), errors);
        assertEquals(3, errors.lines().count(), errors);
    }

    @Test
    public void rejectsEnumsThatCannotBePassedToC() throws IOException {
        Result result = compile(List.of(), "demo.Api", """
                package demo;

                import me.judge.nativebind.GenerateNativeWrapper;

                public class Api {
                    private enum Hidden { A }

                    public enum Empty {}

                    public enum Clash { A_B, aB }

                    @GenerateNativeWrapper(prefix = "lib")
                    static void hidden(Hidden value) {}

                    @GenerateNativeWrapper(prefix = "lib")
                    public static Empty empty() { return null; }

                    @GenerateNativeWrapper(prefix = "lib")
                    public static void clash(Clash value) {}

                    @GenerateNativeWrapper(prefix = "lib")
                    public static void clashAgain(Clash value) {}
                }
                """);
        assertFalse(result.success());
        String errors = result.errors();
        assertTrue(errors.contains("enum demo.Api.Hidden cannot be passed to native code: demo.Api.Hidden is private"), errors);
        assertTrue(errors.contains("enum demo.Api.Empty cannot be passed to C: a C enumeration needs at least one constant"), errors);
        assertTrue(errors.contains("enum demo.Api.Clash cannot be passed to C: more than one constant would be named LIB_API_CLASH_A_B"), errors);
        // Each enum is only reported once, and its wrappers are left out rather than failing to compile
        assertEquals(3, errors.lines().count(), errors);
    }

    @Test
    public void generatesBuiltinsForCommonTypes() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.class=demo.jvm.Entrypoints"), "demo.Api", """
                package demo;

                import java.util.concurrent.CompletableFuture;
                import me.judge.nativebind.GenerateJNIWrapper;
                import me.judge.nativebind.GenerateNativeWrapper;

                public class Api {
                    @GenerateNativeWrapper(prefix = "lib")
                    @GenerateJNIWrapper
                    public static CompletableFuture<String> fetch() { return CompletableFuture.completedFuture("x"); }
                }
                """);
        assertTrue(result.success(), result.errors());

        String c = result.source("demo/NativeBindCEntryPoints.java");
        assertTrue(c.contains("@CEntryPoint(name = \"libFutureIsDone\""), c);
        assertTrue(c.contains("public static boolean futureIsDone(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle future)"), c);
        assertTrue(c.contains("public static boolean futureAwait(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle future, long timeoutMillis)"), c);
        assertTrue(c.contains("return nativeBind$toHandle(nativeBind$futureGet(nativeBind$fromHandle(future)));"), c);
        assertTrue(c.contains("public static CCharPointer futureError(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle future)"), c);
        assertTrue(c.contains("public static ObjectHandle stringToHandle(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, CCharPointer value)"), c);
        assertTrue(c.contains("public static long unboxLong(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle value)"), c);
        assertTrue(c.contains("public static ObjectHandle listGet(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle list, int index)"), c);
        assertTrue(c.contains("\"The returned handle keeps its object alive until it is passed to libReleaseHandle().\""), c);
        assertTrue(c.contains("@CEntryPoint(name = \"libFutureOnComplete\""), c);
        assertTrue(c.contains("public static void futureOnComplete(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle future, FutureCallback callback, VoidPointer userData)"), c);
        assertTrue(c.contains("public interface FutureCallback extends CFunctionPointer { @InvokeCFunctionPointer void invoke(IsolateThread thread, VoidPointer userData);"), c);

        String jvm = result.source("demo/jvm/Entrypoints.java");
        assertTrue(jvm.contains("public static native boolean futureIsDone(long future, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native long futureGet(long future, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native String futureError(long future, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native long stringToHandle(String value, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native double unboxDouble(long value, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native long mapGet(long map, long key, long isolateThread);"), jvm);
        assertFalse(jvm.contains("futureOnComplete"), jvm);

        String jni = result.source("demo/NativeBindJNIEntryPoints.java");
        assertTrue(jni.contains("@CEntryPoint(name = \"Java_demo_jvm_Entrypoints_futureGet\")"), jni);
        assertTrue(jni.contains("public static JNI.JString futureError(JNI.JNIEnv env, JNI.JClass clazz, ObjectHandle future, @CEntryPoint.IsolateThreadContext IsolateThread isolateThread)"), jni);
        assertTrue(jni.contains("return JNIUtil.createHSString(env, nativeBind$futureError(nativeBind$fromHandle(future)));"), jni);
        assertTrue(jni.contains("catch (Throwable nativeBind$error) { nativeBind$throwInJvm(env, nativeBind$error); return false; }"), jni);
    }

    @Test
    public void wrapsConsumersAsCCallbacks() throws IOException {
        Result result = compile(List.of(), "demo.Events", """
                package demo;

                import java.util.function.Consumer;
                import me.judge.nativebind.GenerateNativeWrapper;

                public class Events {
                    /** Reports status changes. */
                    @GenerateNativeWrapper(prefix = "lib")
                    public static void onStatus(Consumer<String> listener) {}

                    @GenerateNativeWrapper(prefix = "lib")
                    public static void onProgress(Consumer<? super Integer> listener, String listenerData) {}

                    @GenerateNativeWrapper(prefix = "lib")
                    public void onChange(Consumer<Events> changed, Consumer<Boolean> ready) {}
                }
                """);
        assertTrue(result.success(), result.errors());

        String c = result.source("demo/EventsCEntryPoints.java");
        // Each Consumer becomes a callback, the user data handed back to it, and an optional release hook
        assertTrue(c.contains("public static void libOnStatus(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, StringListener listener, VoidPointer listenerData, ListenerFree listenerFree)"), c);
        assertTrue(c.contains("Events.onStatus((Consumer) nativeBind$toStringListener(listener, listenerData, listenerFree));"), c);
        assertTrue(c.contains("\"Reports status changes.\", \"listener(thread, value, listenerData) is called on every notification"), c);
        assertTrue(c.contains("\"The string value is only valid until listener returns.\""), c);
        assertTrue(c.contains("\"If listenerFree is not NULL, it is called with listenerData once the Java code no longer holds the listener, which may be never.\""), c);
        // Generated names step around the method's own parameters
        assertTrue(c.contains("public static void libOnProgress(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, IntListener listener, VoidPointer listenerData_, ListenerFree listenerFree, CCharPointer listenerData)"), c);
        assertTrue(c.contains("public static void libOnChange(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, ObjectHandle self, HandleListener changed, VoidPointer changedData, ListenerFree changedFree, BooleanListener ready, VoidPointer readyData, ListenerFree readyFree)"), c);
        assertTrue(c.contains("\"The handle value is only valid until changed returns, and must not be released.\""), c);
        assertTrue(c.contains("\"A null value arrives as 0.\""), c);
        assertTrue(c.contains("\"A null value arrives as false.\""), c);

        assertTrue(c.contains("public interface StringListener extends CFunctionPointer { @InvokeCFunctionPointer void invoke(IsolateThread thread, CCharPointer value, VoidPointer userData);"), c);
        assertTrue(c.contains("public interface IntListener extends CFunctionPointer { @InvokeCFunctionPointer void invoke(IsolateThread thread, int value, VoidPointer userData);"), c);
        assertTrue(c.contains("public interface HandleListener extends CFunctionPointer { @InvokeCFunctionPointer void invoke(IsolateThread thread, ObjectHandle value, VoidPointer userData);"), c);
        assertTrue(c.contains("public interface ListenerFree extends CFunctionPointer { @InvokeCFunctionPointer void invoke(VoidPointer userData);"), c);

        // Strings and handles are lent for the duration of the call only
        assertTrue(c.contains("private static Consumer<String> nativeBind$toStringListener(StringListener listener, VoidPointer userData, ListenerFree free)"), c);
        assertTrue(c.contains("CCharPointer cValue = nativeBind$toCString(value); try { WordFactory.<StringListener>pointer(listenerAddress).invoke(CurrentIsolate.getCurrentThread(), cValue, WordFactory.pointer(userDataAddress)); } finally { if (cValue.isNonNull()) { UnmanagedMemory.free(cValue); } }"), c);
        assertTrue(c.contains("ObjectHandle handle = nativeBind$toHandle(value); try {"), c);
        assertTrue(c.contains("ObjectHandles.getGlobal().destroy(handle);"), c);
        assertTrue(c.contains(".invoke(CurrentIsolate.getCurrentThread(), value == null ? (int) 0 : value, WordFactory.pointer(userDataAddress))"), c);
        assertTrue(c.contains(".invoke(CurrentIsolate.getCurrentThread(), value != null && value, WordFactory.pointer(userDataAddress))"), c);
        // The user data is released through the hook once Java drops the listener
        assertTrue(c.contains("NativeBindCleaner.CLEANER.register(listener, () -> WordFactory.<ListenerFree>pointer(freeAddress).invoke(WordFactory.pointer(userDataAddress)));"), c);
        assertTrue(c.contains("private static final class NativeBindCleaner { static final Cleaner CLEANER = Cleaner.create();"), c);
    }

    @Test
    public void wrapsConsumersAsJvmFunctionalInterfaces() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.class=demo.jvm.Entrypoints"), "demo.Events", """
                package demo;

                import java.util.function.Consumer;
                import me.judge.nativebind.GenerateJNIWrapper;

                public class Events {
                    @GenerateJNIWrapper
                    public static void onStatus(Consumer<String> listener) {}

                    @GenerateJNIWrapper
                    public static void onProgress(Consumer<Integer> listener) {}

                    @GenerateJNIWrapper
                    public static void onChange(Consumer<Events> listener) {}
                }
                """);
        assertTrue(result.success(), result.errors());

        String jvm = result.source("demo/jvm/Entrypoints.java");
        assertTrue(jvm.contains("public static native void onStatus(Consumer<String> listener, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native void onProgress(Consumer<Integer> listener, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native void onChange(LongConsumer listener, long isolateThread);"), jvm);
        assertTrue(jvm.contains("{@code listener} is called on whichever thread the native code notifies it from"), jvm);
        assertTrue(jvm.contains("The handle it receives is only valid during the call, and must not be released."), jvm);
        assertFalse(jvm.contains("org.graalvm"), jvm);

        String jni = result.source("demo/EventsJNIEntryPoints.java");
        assertTrue(jni.contains("public static void Java_demo_jvm_Entrypoints_onStatus(JNI.JNIEnv env, JNI.JClass clazz, JNI.JObject listener, @CEntryPoint.IsolateThreadContext IsolateThread isolateThread)"), jni);
        assertTrue(jni.contains("Events.onStatus((Consumer) nativeBind$toStringListener(env, listener));"), jni);
        // The JVM object outlives the call through a global reference, released once the Consumer is unreachable
        assertTrue(jni.contains("long reference = JNIUtil.NewGlobalRef(env, listener, \"NativeBind listener\").rawValue();"), jni);
        assertTrue(jni.contains("JNIUtil.DeleteGlobalRef(current, WordFactory.pointer(listener));"), jni);
        assertTrue(jni.contains("long method = nativeBind$listenerMethod(env, \"java/util/function/Consumer\", \"(Ljava/lang/Object;)V\").rawValue();"), jni);
        assertTrue(jni.contains("long method = nativeBind$listenerMethod(env, \"java/util/function/LongConsumer\", \"(J)V\").rawValue();"), jni);
        // Only threads the JVM reports as detached are attached, and only for the call. The version is one Android's
        // ART accepts: with jniutils' JNI_VERSION_10, ART fails GetEnv, and a running JVM thread would be detached
        assertTrue(jni.contains("int status = javaVM.getFunctions().getGetEnv().call(javaVM, envPointer, 0x00010006);"), jni);
        assertTrue(jni.contains("boolean attached = status == JNI.JNI_EDETACHED;"), jni);
        assertTrue(jni.contains("attachArgs.setVersion(0x00010006);"), jni);
        assertTrue(jni.contains("status = javaVM.getFunctions().getAttachCurrentThreadAsDaemon().call(javaVM, envPointer, attachArgs);"), jni);
        assertTrue(jni.contains("if (status != JNI.JNI_OK) {"), jni);
        assertFalse(jni.contains("JNIUtil.GetEnv"), jni);
        assertFalse(jni.contains("JNIUtil.attachCurrentThread"), jni);
        assertTrue(jni.contains("if (attached) { JNIUtil.DetachCurrentThread(javaVM); }"), jni);
        assertTrue(jni.contains("args.setJObject(JNIUtil.createHSString(current, value));"), jni);
        assertTrue(jni.contains("primitive.setInt(value); args.setJObject(nativeBind$box(current, \"java/lang/Integer\", \"(I)Ljava/lang/Integer;\", primitive));"), jni);
        assertTrue(jni.contains("args.setLong(handle.rawValue());"), jni);
        // What the listener throws is reported and cleared rather than left pending
        assertTrue(jni.contains("nativeBind$warnIfThrown(current);"), jni);
        assertTrue(jni.contains("JNIUtil.ExceptionDescribe(env); JNIUtil.ExceptionClear(env);"), jni);
    }

    @Test
    public void wrapsEnumConsumers() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.class=demo.jvm.Entrypoints"), Map.of(
                "demo.Status", """
                        package demo;

                        public enum Status { OK, FAILED }
                        """,
                "demo.Events", """
                        package demo;

                        import java.util.function.Consumer;
                        import me.judge.nativebind.GenerateJNIWrapper;
                        import me.judge.nativebind.GenerateNativeWrapper;
                        import org.graalvm.nativeimage.c.CContext;
                        import org.graalvm.nativeimage.c.constant.CEnum;

                        public class Events {
                            public static final class Directives implements CContext.Directives {}

                            @CContext(Directives.class)
                            @CEnum
                            public enum Level { LOW, HIGH }

                            @GenerateNativeWrapper(prefix = "lib")
                            @GenerateJNIWrapper
                            public static void onStatus(Consumer<Status> listener) {}

                            @GenerateNativeWrapper(prefix = "lib")
                            public static void onLevel(Consumer<? super Level> listener) {}
                        }
                        """));
        assertTrue(result.success(), result.errors());

        String c = result.source("demo/EventsCEntryPoints.java");
        assertTrue(c.contains("public static void libOnStatus(@CEntryPoint.IsolateThreadContext IsolateThread isolateThread, StatusEnumListener listener, VoidPointer listenerData, ListenerFree listenerFree)"), c);
        assertTrue(c.contains("\"A null value has no C enumeration constant, so notifying it throws a NullPointerException in the Java code.\""), c);
        // A mirrored enum's callback takes the @CEnum mirror; a @CEnum enum is passed as it is
        assertTrue(c.contains("public interface StatusEnumListener extends CFunctionPointer { @InvokeCFunctionPointer void invoke(IsolateThread thread, StatusCEnum value, VoidPointer userData);"), c);
        assertTrue(c.contains("public interface Events_LevelEnumListener extends CFunctionPointer { @InvokeCFunctionPointer void invoke(IsolateThread thread, Events.Level value, VoidPointer userData);"), c);
        assertTrue(c.contains("private static Consumer<Status> nativeBind$toStatusEnumListener(StatusEnumListener listener, VoidPointer userData, ListenerFree free)"), c);
        assertTrue(c.contains("if (value == null) { throw new NullPointerException(\"null cannot be passed to a C listener of demo.Status\"); } WordFactory.<StatusEnumListener>pointer(listenerAddress).invoke(CurrentIsolate.getCurrentThread(), StatusCEnum.fromJava(value), WordFactory.pointer(userDataAddress));"), c);
        assertTrue(c.contains(".invoke(CurrentIsolate.getCurrentThread(), value, WordFactory.pointer(userDataAddress));"), c);
        // Listener enums are mirrored like parameters
        assertTrue(Files.exists(result.generated().resolve("demo/StatusCEnum.java")));

        String jvm = result.source("demo/jvm/Entrypoints.java");
        assertTrue(jvm.contains("public static native void onStatus(Consumer<Status> listener, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public enum Status { OK, FAILED }"), jvm);

        String jni = result.source("demo/EventsJNIEntryPoints.java");
        assertTrue(jni.contains("Events.onStatus((Consumer) nativeBind$toStatusEnumListener(env, listener, \"demo/jvm/Entrypoints$Status\"));"), jni);
        // The mock is found on the calling thread, since a thread attached from native code cannot see application classes
        assertTrue(jni.contains("private static Consumer<Status> nativeBind$toStatusEnumListener(JNI.JNIEnv env, JNI.JObject listener, String type) { if (listener.isNull()) { return null; } JNI.JClass found = JNIUtil.findClass(env, type);"), jni);
        assertTrue(jni.contains("long enumType = JNIUtil.NewGlobalRef(env, found, \"NativeBind mock enum\").rawValue();"), jni);
        assertTrue(jni.contains("args.setJObject(nativeBind$enumConstantOf(current, WordFactory.pointer(enumType), type, nativeBind$toOrdinal(value)));"), jni);
        assertTrue(jni.contains("nativeBind$releaseWhenUnreachable(consumer, vm, reference); nativeBind$releaseWhenUnreachable(consumer, vm, enumType); return consumer;"), jni);
    }

    @Test
    public void rejectsMockClashesFromListeners() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.class=demo.jvm.Entrypoints"), Map.of(
                "demo.Status", "package demo; public enum Status { OK }",
                "demo.other.Status", "package demo.other; public enum Status { ON }",
                "demo.Events", """
                        package demo;

                        import java.util.function.Consumer;
                        import me.judge.nativebind.GenerateJNIWrapper;

                        public class Events {
                            @GenerateJNIWrapper
                            public static void onStatus(Consumer<Status> listener) {}

                            @GenerateJNIWrapper
                            public static void onPower(Consumer<demo.other.Status> listener) {}
                        }
                        """));
        assertFalse(result.success());
        assertEquals("demo.jvm.Entrypoints cannot mock both demo.Status and demo.other.Status as enum Status; bind one of them to another JVM class", result.errors());
    }

    @Test
    public void rejectsConsumersOfWords() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.class=demo.Natives"), "demo.Api", """
                package demo;

                import java.util.function.Consumer;
                import org.graalvm.nativeimage.c.type.CCharPointer;

                public class Api {
                    @me.judge.nativebind.GenerateNativeWrapper(prefix = "lib")
                    public static void c(Consumer<CCharPointer> listener) {}

                    @me.judge.nativebind.GenerateJNIWrapper
                    public static void jni(Consumer<CCharPointer> listener) {}
                }
                """);
        assertFalse(result.success());
        String errors = result.errors();
        assertEquals(2, errors.lines().filter(l -> l.contains("Consumer<org.graalvm.nativeimage.c.type.CCharPointer> cannot be wrapped: word values cannot be boxed")).count(), errors);
    }

    @Test
    public void generatesOneSupportClassForManyWrappedClasses() throws IOException {
        Result result = compile(List.of(), Map.of(
                "demo.a.First", """
                        package demo.a;

                        public class First {
                            @me.judge.nativebind.GenerateNativeWrapper(prefix = "lib")
                            public static void one() {}
                        }
                        """,
                "demo.b.Second", """
                        package demo.b;

                        public class Second {
                            @me.judge.nativebind.GenerateNativeWrapper(prefix = "lib")
                            public static void two() {}
                        }
                        """));
        assertTrue(result.success(), result.errors());
        assertTrue(Files.exists(result.generated().resolve("demo/a/FirstCEntryPoints.java")));
        assertTrue(Files.exists(result.generated().resolve("demo/b/SecondCEntryPoints.java")));
        try (var all = Files.walk(result.generated())) {
            List<Path> support = all.filter(p -> p.getFileName().toString().equals("NativeBindCEntryPoints.java")).toList();
            assertEquals(List.of(result.generated().resolve("demo/NativeBindCEntryPoints.java")), support);
        }
        assertTrue(result.source("demo/NativeBindCEntryPoints.java").contains("name = \"libCreateIsolate\""));
    }

    @Test
    public void supportPackageCanBeOverridden() throws IOException {
        Result result = compile(List.of("-Anativebind.package=demo.natives"), "demo.Api", """
                package demo;

                public class Api {
                    @me.judge.nativebind.GenerateNativeWrapper(prefix = "lib_")
                    public static void run() {}
                }
                """);
        assertTrue(result.success(), result.errors());
        assertTrue(result.source("demo/natives/NativeBindCEntryPoints.java").contains("name = \"lib_releaseHandle\""));
    }

    @Test
    public void mixedPrefixesNeedAnExplicitSupportPrefix() throws IOException {
        String source = """
                package demo;

                public class Api {
                    @me.judge.nativebind.GenerateNativeWrapper(prefix = "a")
                    public static void one() {}

                    @me.judge.nativebind.GenerateNativeWrapper(prefix = "b")
                    public static void two() {}
                }
                """;
        Result mixed = compile(List.of(), "demo.Api", source);
        assertFalse(mixed.success());
        assertTrue(mixed.errors().contains("different prefixes [a, b]"), mixed.errors());

        Result resolved = compile(List.of("-Anativebind.prefix=shared"), "demo.Api", source);
        assertTrue(resolved.success(), resolved.errors());
        assertTrue(resolved.source("demo/NativeBindCEntryPoints.java").contains("name = \"sharedReleaseHandle\""));
    }

    @Test
    public void wrappersCannotShadowSupportSymbols() throws IOException {
        Result result = compile(List.of(), "demo.Api", """
                package demo;

                public class Api {
                    @me.judge.nativebind.GenerateNativeWrapper(prefix = "lib")
                    public static void releaseHandle() {}
                }
                """);
        assertFalse(result.success());
        assertTrue(result.errors().contains("\"libReleaseHandle\" is already exported by demo.NativeBindCEntryPoints"), result.errors());
    }

    @Test
    public void rejectsDuplicateSymbols() throws IOException {
        Result result = compile(List.of(), "demo.Overloads", """
                package demo;

                import me.judge.nativebind.GenerateNativeWrapper;

                public class Overloads {
                    @GenerateNativeWrapper(prefix = "lib")
                    public static void run(int a) {}

                    @GenerateNativeWrapper(prefix = "lib")
                    public static void run(long a) {}
                }
                """);
        assertFalse(result.success());
        assertTrue(result.errors().contains("symbol \"libRun\" is already exported"), result.errors());
    }

    @Test
    public void rejectsInvalidNames() throws IOException {
        Result result = compile(List.of(), "demo.Invalid", """
                package demo;

                import me.judge.nativebind.GenerateNativeWrapper;

                public class Invalid {
                    @GenerateNativeWrapper(prefix = "9lib")
                    public static void a() {}

                    @GenerateNativeWrapper(name = "has-dash")
                    public static void b() {}

                    @GenerateNativeWrapper
                    public static void register() {}

                    @GenerateNativeWrapper
                    private static void hidden() {}
                }
                """);
        assertFalse(result.success());
        String errors = result.errors();
        assertTrue(errors.contains("prefix \"9lib\" is not a valid C identifier prefix"), errors);
        assertTrue(errors.contains("name \"has-dash\" may only contain"), errors);
        assertTrue(errors.contains("\"register\" is not a valid C identifier"), errors);
        assertTrue(errors.contains("must not be private"), errors);
        assertEquals(4, errors.lines().count(), errors);
    }

    @Test
    public void generatesJNIEntryPointsAndJvmClass() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.class=demo.jvm.Entrypoints"), "demo.Api", """
                package demo;

                import java.util.List;
                import me.judge.nativebind.GenerateJNIWrapper;
                import org.graalvm.jniutils.JNI;

                public class Api {
                    /** Says hello. */
                    @GenerateJNIWrapper
                    public static String greet(String name) { return "Hello " + name; }

                    @GenerateJNIWrapper
                    public static int sum(int[] values) { return values.length; }

                    @GenerateJNIWrapper
                    public static int sum(int a, int b) { return a + b; }

                    @GenerateJNIWrapper
                    public static List<String> accounts() { return List.of(); }

                    @GenerateJNIWrapper
                    public static String[] split(String value) { return value.split(" "); }

                    @GenerateJNIWrapper
                    public static void callback(JNI.JNIEnv env, JNI.JObject target, JNI.JClass type) {}

                    @GenerateJNIWrapper
                    public static boolean get_name$x() { return true; }

                    @GenerateJNIWrapper
                    public int size() { return 0; }
                }
                """);
        assertTrue(result.success(), result.errors());

        String jvm = result.source("demo/jvm/Entrypoints.java");
        assertTrue(jvm.contains("public static native long createIsolate();"), jvm);
        assertTrue(jvm.contains("public static native int tearDownIsolate(long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native void releaseHandle(long handle, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native long attachThread(long isolate);"), jvm);
        assertTrue(jvm.contains("public static native int detachThread(long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native long getIsolate(long isolateThread);"), jvm);
        // Each Java thread resolves its own isolate thread instead of sharing the creating thread's
        assertTrue(jvm.contains("public static long openIsolate()"), jvm);
        assertTrue(jvm.contains("public static long currentIsolateThread(long isolate)"), jvm);
        assertTrue(jvm.contains("long thread = attachThread(isolate);"), jvm);
        assertTrue(jvm.contains("public static void detachCurrentThread(long isolate)"), jvm);
        assertTrue(jvm.contains("public static void closeIsolate(long isolate)"), jvm);
        assertTrue(jvm.contains("Says hello."), jvm);
        assertTrue(jvm.contains("public static native String greet(String name, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native int sum(int[] values, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native int sum(int a, int b, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native long accounts(long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native String[] split(String value, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native void callback(Object target, Class<?> type, long isolateThread);"), jvm);
        assertTrue(jvm.contains("public static native int size(long self, long isolateThread);"), jvm);

        // The JVM side (e.g. Android) must not depend on jniutils or any other GraalVM API
        assertFalse(jvm.contains("org.graalvm"), jvm);

        String wrappers = result.source("demo/ApiJNIEntryPoints.java");
        assertFalse(wrappers.contains("JNIExceptionWrapper"), wrappers);
        // Not overloaded: short names, with '_' and '$' escaped
        assertTrue(wrappers.contains("name = \"Java_demo_jvm_Entrypoints_greet\""), wrappers);
        assertTrue(wrappers.contains("name = \"Java_demo_jvm_Entrypoints_get_1name_00024x\""), wrappers);
        // Overloaded: long names carrying the JVM-side argument descriptors
        assertTrue(wrappers.contains("name = \"Java_demo_jvm_Entrypoints_sum___3IJ\""), wrappers);
        assertTrue(wrappers.contains("name = \"Java_demo_jvm_Entrypoints_sum__IIJ\""), wrappers);
        assertTrue(wrappers.contains("public static JNI.JString Java_demo_jvm_Entrypoints_greet(JNI.JNIEnv env, JNI.JClass clazz, JNI.JString name, @CEntryPoint.IsolateThreadContext IsolateThread isolateThread)"), wrappers);
        assertTrue(wrappers.contains("try (JNIMethodScope nativeBind$scope = new JNIMethodScope(\"demo.Api.greet\", env))"), wrappers);
        assertTrue(wrappers.contains("return JNIUtil.createHSString(env, Api.greet(JNIUtil.createString(env, name)));"), wrappers);
        assertTrue(wrappers.contains("catch (Throwable nativeBind$error) { nativeBind$throwInJvm(env, nativeBind$error); return WordFactory.zero(); }"), wrappers);
        assertTrue(wrappers.contains("return Api.sum(JNIUtil.createArray(env, values));"), wrappers);
        assertTrue(wrappers.contains("return (int) 0;"), wrappers);
        assertTrue(wrappers.contains("return nativeBind$toHSStringArray(env, Api.split(JNIUtil.createString(env, value)));"), wrappers);
        // The wrapped method's own "env" parameter pushes the generated one aside
        assertTrue(wrappers.contains("Api.callback(env_, target, type);"), wrappers);
        assertTrue(wrappers.contains("return ((Api) nativeBind$fromHandle(self)).size();"), wrappers);

        String support = result.source("demo/NativeBindJNIEntryPoints.java");
        assertTrue(support.contains("@CEntryPoint(name = \"Java_demo_jvm_Entrypoints_createIsolate\", builtin = CEntryPoint.Builtin.CREATE_ISOLATE)"), support);
        assertTrue(support.contains("@CEntryPoint(name = \"Java_demo_jvm_Entrypoints_tearDownIsolate\", builtin = CEntryPoint.Builtin.TEAR_DOWN_ISOLATE)"), support);
        assertTrue(support.contains("@CEntryPoint(name = \"Java_demo_jvm_Entrypoints_releaseHandle\")"), support);
        assertTrue(support.contains("@CEntryPoint(name = \"Java_demo_jvm_Entrypoints_attachThread\", builtin = CEntryPoint.Builtin.ATTACH_THREAD)"), support);
        assertTrue(support.contains("public static native IsolateThread attachThread(JNI.JNIEnv env, JNI.JClass clazz, @CEntryPoint.IsolateContext Isolate isolate);"), support);
        assertTrue(support.contains("@CEntryPoint(name = \"Java_demo_jvm_Entrypoints_detachThread\", builtin = CEntryPoint.Builtin.DETACH_THREAD)"), support);
        assertTrue(support.contains("@CEntryPoint(name = \"Java_demo_jvm_Entrypoints_getIsolate\", builtin = CEntryPoint.Builtin.GET_ISOLATE)"), support);
        // Errors reach the JVM with their native stack trace and causes, not just the outermost message
        assertTrue(support.contains("error.printStackTrace(new PrintWriter(trace));"), support);

        assertTrue(result.source("demo/NativeBindJNIFeature.java").contains("ImageSingletons.add(NativeBridgeSupport.class, new NativeBindJNIBridgeSupport());"));
        String properties = Files.readString(result.classes().resolve("META-INF/native-image/nativebind/demo/native-image.properties"));
        assertTrue(properties.contains("--features=demo.NativeBindJNIFeature"), properties);
        assertTrue(properties.contains("--initialize-at-build-time=demo.NativeBindJNIBridgeSupport"), properties);
    }

    @Test
    public void jniAndCSupportAreGeneratedOnceEach() throws IOException {
        Result result = compile(List.of(), Map.of(
                "demo.a.First", """
                        package demo.a;

                        public class First {
                            @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives")
                            public static void one() {}

                            @me.judge.nativebind.GenerateNativeWrapper(prefix = "lib")
                            public static void cOne() {}
                        }
                        """,
                "demo.b.Second", """
                        package demo.b;

                        public class Second {
                            @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives")
                            public static void two() {}

                            @me.judge.nativebind.GenerateNativeWrapper(prefix = "lib")
                            public static void cTwo() {}
                        }
                        """));
        assertTrue(result.success(), result.errors());
        try (var all = Files.walk(result.generated())) {
            List<String> names = all.map(p -> result.generated().relativize(p).toString()).filter(p -> p.endsWith(".java")).sorted().toList();
            assertEquals(List.of(
                    "demo/NativeBindCEntryPoints.java",
                    "demo/NativeBindCFeature.java",
                    "demo/NativeBindJNIBridgeSupport.java",
                    "demo/NativeBindJNIEntryPoints.java",
                    "demo/NativeBindJNIFeature.java",
                    "demo/Natives.java",
                    "demo/a/FirstCEntryPoints.java",
                    "demo/a/FirstJNIEntryPoints.java",
                    "demo/b/SecondCEntryPoints.java",
                    "demo/b/SecondJNIEntryPoints.java"), names);
        }
    }

    @Test
    public void rejectsNativesShadowingThreadHelpers() throws IOException {
        Result result = compile(List.of(), "demo.Api", """
                package demo;

                public class Api {
                    @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives")
                    public static long currentIsolateThread() { return 0; }

                    @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives", name = "threadId")
                    public static long isolateThread() { return 0; }
                }
                """);
        assertFalse(result.success());
        assertEquals("demo.Natives.currentIsolateThread is reserved for isolate thread management; "
                + "set @GenerateJNIWrapper(name = ...) to rename it", result.errors());
    }

    @Test
    public void verifiesHandWrittenJvmClass() throws IOException {
        Result result = compile(List.of("-Anativebind.jni.generateClass=false"), Map.of(
                "demo.Natives", """
                        package demo;

                        public class Natives {
                            public static native long createIsolate();
                            public static native int tearDownIsolate(long isolateThread);
                            public static native void releaseHandle(long handle, long isolateThread);
                            public static native String greet(String name, long isolateThread);
                            public static native int count(long isolateThread);
                        }
                        """,
                "demo.Api", """
                        package demo;

                        public class Api {
                            @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives")
                            public static String greet(String name) { return name; }

                            @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives")
                            public static long count() { return 0; }
                        }
                        """));
        assertFalse(result.success());
        assertEquals("demo.Natives has no static native method count(J)J", result.errors());
        assertFalse(Files.exists(result.generated().resolve("demo/Natives.java")));
    }

    @Test
    public void rejectsInvalidJNIDeclarations() throws IOException {
        Result result = compile(List.of(), "demo.Api", """
                package demo;

                import org.graalvm.jniutils.JNI;

                public class Api {
                    @me.judge.nativebind.GenerateJNIWrapper
                    public static void noClass() {}

                    @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.bad-name")
                    public static void badClass() {}

                    @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives")
                    public static JNI.JNIEnv env() { return null; }

                    @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives")
                    public static void same(Object a) {}

                    @me.judge.nativebind.GenerateJNIWrapper(jniClass = "demo.Natives")
                    public static void same(String[][] a) {}
                }
                """);
        assertFalse(result.success());
        String errors = result.errors();
        assertTrue(errors.contains("set @GenerateJNIWrapper(jniClass = ...) or -Anativebind.jni.class"), errors);
        assertTrue(errors.contains("\"demo.bad-name\" is not a valid class binary name"), errors);
        assertTrue(errors.contains("JNIEnv cannot be returned to the JVM"), errors);
        assertTrue(errors.contains("demo.Natives.same(JJ)V is declared more than once"), errors);
        assertEquals(4, errors.lines().count(), errors);
    }
}

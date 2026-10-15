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

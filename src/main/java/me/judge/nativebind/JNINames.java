package me.judge.nativebind;

import javax.lang.model.SourceVersion;

/**
 * Native method names as specified by the JNI "Resolving Native Method Names" rules.
 */
final class JNINames {
    private JNINames() {
    }

    /** Whether {@code binaryName} looks like a class binary name such as {@code a.b.Outer$Inner}. */
    static boolean isValidBinaryName(String binaryName) {
        if (binaryName.isEmpty()) {
            return false;
        }
        for (String segment : binaryName.split("[.$]", -1)) {
            if (!SourceVersion.isIdentifier(segment) || SourceVersion.isKeyword(segment)) {
                return false;
            }
        }
        return true;
    }

    /** {@code Java_<class>_<method>}, used when the method is not overloaded. */
    static String shortName(String binaryClassName, String methodName) {
        return "Java_" + mangle(binaryClassName.replace('.', '/')) + "_" + mangle(methodName);
    }

    /** {@code Java_<class>_<method>__<argument descriptors>}, used for overloaded methods. */
    static String longName(String binaryClassName, String methodName, String argumentDescriptors) {
        return shortName(binaryClassName, methodName) + "__" + mangle(argumentDescriptors);
    }

    /**
     * Escapes a class name, method name or descriptor: {@code /} separates components as {@code _}, the escape
     * character {@code _} becomes {@code _1}, {@code ;} {@code _2}, {@code [} {@code _3}, and every other character
     * outside {@code [A-Za-z0-9]} becomes {@code _0xxxx} (its UTF-16 code unit in lowercase hex).
     */
    static String mangle(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c == '/') {
                sb.append('_');
            } else if (c == '_') {
                sb.append("_1");
            } else if (c == ';') {
                sb.append("_2");
            } else if (c == '[') {
                sb.append("_3");
            } else {
                sb.append(String.format("_0%04x", (int) c));
            }
        }
        return sb.toString();
    }
}

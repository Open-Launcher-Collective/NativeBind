package me.judge.nativebind;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules for producing exported symbol names that are valid C identifiers.
 */
final class CNames {
    private static final Pattern IDENTIFIER_FRAGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Set<String> KEYWORDS = Set.of(
            "alignas", "alignof", "auto", "bool", "break", "case", "char", "const", "constexpr", "continue",
            "default", "do", "double", "else", "enum", "extern", "false", "float", "for", "goto", "if",
            "inline", "int", "long", "nullptr", "register", "restrict", "return", "short", "signed", "sizeof",
            "static", "static_assert", "struct", "switch", "thread_local", "true", "typedef", "typeof",
            "typeof_unqual", "union", "unsigned", "void", "volatile", "while",
            "_Alignas", "_Alignof", "_Atomic", "_BitInt", "_Bool", "_Complex", "_Decimal128", "_Decimal32",
            "_Decimal64", "_Generic", "_Imaginary", "_Noreturn", "_Static_assert", "_Thread_local");

    private CNames() {
    }

    /** Whether {@code s} is empty or usable verbatim as a C identifier (or the start of one). */
    static boolean isValidPrefix(String s) {
        return s.isEmpty() || IDENTIFIER_FRAGMENT.matcher(s).matches();
    }

    static boolean isValidIdentifier(String s) {
        return IDENTIFIER_FRAGMENT.matcher(s).matches() && !KEYWORDS.contains(s);
    }

    /** Identifiers beginning with {@code __} or {@code _} + uppercase are reserved for the C implementation. */
    static boolean isReserved(String s) {
        return s.length() >= 2 && s.charAt(0) == '_' && (s.charAt(1) == '_' || Character.isUpperCase(s.charAt(1)));
    }

    /**
     * Maps a Java identifier onto the C identifier character set: {@code $} becomes {@code _}, and any other
     * character outside {@code [A-Za-z0-9_]} becomes {@code _uXXXX} (its UTF-16 code unit in hex).
     */
    static String sanitize(String javaIdentifier) {
        StringBuilder sb = new StringBuilder(javaIdentifier.length());
        for (int i = 0; i < javaIdentifier.length(); i++) {
            char c = javaIdentifier.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else if (c == '$') {
                sb.append('_');
            } else {
                sb.append(String.format("_u%04x", (int) c));
            }
        }
        return sb.toString();
    }

    /** {@code judgeLibStatus} becomes {@code JUDGE_LIB_STATUS}: an underscore before each lowercase-to-uppercase step, then uppercased. */
    static String upperSnake(String identifier) {
        StringBuilder sb = new StringBuilder(identifier.length() + 8);
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                char previous = identifier.charAt(i - 1);
                if (Character.isLowerCase(previous) || Character.isDigit(previous)) {
                    sb.append('_');
                }
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /**
     * Joins a prefix and base name. An empty prefix leaves the base untouched, a prefix ending in {@code _} is
     * concatenated as-is, and otherwise the base is capitalized to continue the prefix in camel case.
     */
    static String join(String prefix, String base) {
        if (prefix.isEmpty() || prefix.endsWith("_") || base.isEmpty()) {
            return prefix + base;
        }
        return prefix + Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }
}

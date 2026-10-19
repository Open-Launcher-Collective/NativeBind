package me.judge.nativebind;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.ref.Cleaner;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A {@link Consumer} parameter, through which wrapped code notifies native callers of changes. Both generators hand
 * the wrapped method a {@code Consumer} that forwards each value to the caller's callback, any number of times and
 * from any thread. Values are only lent to the callback: strings and handles are released once it returns.
 *
 * @param valueType the boxed type for {@link Kind#BOXED}, else the erased type the consumer accepts
 */
record Listener(Kind kind, TypeMirror valueType) {
    static final ClassName CONSUMER = ClassName.get(Consumer.class);
    static final String CLEANER_HOLDER = "NativeBindCleaner";

    /** {@code ENUM} values cross as the C enumeration (C) or the mock enum (JNI) their parameters would. */
    enum Kind {
        STRING, BOXED, HANDLE, ENUM
    }

    /**
     * The listener a parameter of {@code type} declares, or null if it is not a {@code Consumer}. Throws
     * {@link IllegalArgumentException} for values that cannot reach native code.
     */
    static Listener of(TypeMirror type, Types types, Elements elements, TypeMirror wordBase) {
        TypeMirror consumer = types.erasure(elements.getTypeElement(Consumer.class.getName()).asType());
        if (!(type instanceof DeclaredType declared) || !types.isSameType(types.erasure(type), consumer)) {
            return null;
        }
        TypeMirror object = elements.getTypeElement(Object.class.getName()).asType();
        TypeMirror value = declared.getTypeArguments().isEmpty() ? object : declared.getTypeArguments().get(0);
        if (value instanceof WildcardType wildcard) {
            value = wildcard.getSuperBound() != null ? wildcard.getSuperBound()
                    : wildcard.getExtendsBound() != null ? wildcard.getExtendsBound()
                    : object;
        }
        value = types.erasure(value);

        if (types.isSameType(value, elements.getTypeElement(String.class.getName()).asType())) {
            return new Listener(Kind.STRING, value);
        }
        try {
            types.unboxedType(value);
            return new Listener(Kind.BOXED, value);
        } catch (IllegalArgumentException notBoxed) {
            // Any other object is lent to the callback as a handle
        }
        if (types.asElement(value) instanceof TypeElement element && element.getKind() == ElementKind.ENUM) {
            return new Listener(Kind.ENUM, value);
        }
        if (types.isAssignable(value, wordBase)) {
            throw new IllegalArgumentException("Consumer<" + value + "> cannot be wrapped: word values cannot be boxed");
        }
        return new Listener(Kind.HANDLE, value);
    }

    /**
     * Adds {@code listener} to {@code listeners}, keyed by its {@link #suffix}, and returns that key. Two enums
     * sharing a suffix would also share a C name and a mock, which the processor rejects.
     */
    static String register(Map<String, Listener> listeners, Listener listener, Types types) {
        String suffix = listener.suffix(types);
        listeners.putIfAbsent(suffix, listener);
        return suffix;
    }

    /** The primitive a {@link Kind#BOXED} value unboxes to. */
    TypeKind primitive(Types types) {
        return types.unboxedType(valueType).getKind();
    }

    /** The enum an {@link Kind#ENUM} listener receives. */
    TypeElement enumType() {
        return (TypeElement) ((DeclaredType) valueType).asElement();
    }

    /** What the generated {@code Consumer} accepts. */
    TypeName javaType() {
        return switch (kind) {
            case STRING -> ClassName.get(String.class);
            case BOXED, ENUM -> TypeName.get(valueType);
            case HANDLE -> ClassName.get(Object.class);
        };
    }

    /**
     * Distinguishes the generated callback types: {@code String}, {@code Int}, {@code Boolean}, ..., {@code Handle},
     * or {@code Outer_StatusEnum} for an enum.
     */
    String suffix(Types types) {
        return switch (kind) {
            case STRING -> "String";
            case BOXED -> {
                String name = primitive(types).name();
                yield name.charAt(0) + name.substring(1).toLowerCase();
            }
            case HANDLE -> "Handle";
            case ENUM -> CEntryPointGenerator.flatName(enumType(), "Enum");
        };
    }

    /** {@code value == null ? 0 : value}, or {@code false} for booleans, so a null boxed value still reaches native code. */
    static CodeBlock unboxedOrZero(TypeKind primitive, String value) {
        return primitive == TypeKind.BOOLEAN
                ? CodeBlock.of("$N != null && $N", value, value)
                : CodeBlock.of("$N == null ? ($L) 0 : $N", value, primitive.name().toLowerCase(), value);
    }

    /**
     * A nested class holding the {@code Cleaner} that releases what native code lent to a listener once its
     * {@code Consumer} is unreachable. Being a holder, it is created on first use, so its thread starts at run time
     * rather than in the image builder.
     */
    static TypeSpec cleanerHolder() {
        return TypeSpec.classBuilder(CLEANER_HOLDER)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addField(FieldSpec.builder(Cleaner.class, "CLEANER", Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.create()", Cleaner.class)
                        .build())
                .build();
    }
}

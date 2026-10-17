package me.judge.nativebind;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.List;

/**
 * A member an entry point calls: a method, a constructor, or one accessor of a field. Constructors are exposed as
 * static factories returning the new object, named {@code new<Owner>} unless the annotation names them. A field is
 * exposed as a getter {@code get<Field>} and, unless it is final, a setter {@code set<Field>} taking the new value;
 * a name given by the annotation replaces the field name after the {@code get} / {@code set}.
 */
record Wrapped(Element element, Kind kind) {
    enum Kind {
        METHOD, CONSTRUCTOR, GETTER, SETTER
    }

    /** The members an annotated element is wrapped as: itself, or a field's getter and (unless final) setter. */
    static List<Wrapped> of(Element element) {
        if (element instanceof ExecutableElement) {
            return List.of(new Wrapped(element, element.getKind() == ElementKind.CONSTRUCTOR ? Kind.CONSTRUCTOR : Kind.METHOD));
        }
        Wrapped getter = new Wrapped(element, Kind.GETTER);
        return element.getModifiers().contains(Modifier.FINAL) ? List.of(getter) : List.of(getter, new Wrapped(element, Kind.SETTER));
    }

    TypeElement owner() {
        return (TypeElement) element.getEnclosingElement();
    }

    boolean isConstructor() {
        return kind == Kind.CONSTRUCTOR;
    }

    private boolean isAccessor() {
        return kind == Kind.GETTER || kind == Kind.SETTER;
    }

    /** Whether the call needs a {@code self} handle to the receiver. */
    boolean hasReceiver() {
        return !isConstructor() && !element.getModifiers().contains(Modifier.STATIC);
    }

    /** The values the call takes; a setter takes the field's new value, under the field's name. */
    List<? extends VariableElement> parameters() {
        return switch (kind) {
            case METHOD, CONSTRUCTOR -> ((ExecutableElement) element).getParameters();
            case GETTER -> List.of();
            case SETTER -> List.of((VariableElement) element);
        };
    }

    List<? extends TypeMirror> thrownTypes() {
        return isAccessor() ? List.of() : ((ExecutableElement) element).getThrownTypes();
    }

    TypeMirror returnType(Types types) {
        return switch (kind) {
            case METHOD -> ((ExecutableElement) element).getReturnType();
            case CONSTRUCTOR -> owner().asType();
            case GETTER -> element.asType();
            case SETTER -> types.getNoType(TypeKind.VOID);
        };
    }

    String defaultName() {
        return name(isConstructor() ? "" : element.getSimpleName().toString());
    }

    /** The name for an annotation's {@code name}, which only replaces the field name in an accessor's. */
    String name(String override) {
        return switch (kind) {
            case METHOD -> override;
            case CONSTRUCTOR -> override.isEmpty() ? "new" + owner().getSimpleName() : override;
            case GETTER -> "get" + capitalize(override);
            case SETTER -> "set" + capitalize(override);
        };
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** How the wrapped member reads in documentation, e.g. {@code Api.run}, {@code new Api} or {@code Api.count}. */
    String displayName() {
        String owner = owner().getSimpleName().toString();
        return isConstructor() ? "new " + owner : owner + "." + element.getSimpleName();
    }

    /** What the member is called in diagnostics, e.g. {@code demo.Api.run(int)} or {@code demo.Api.count (setter)}. */
    String description() {
        String member = owner() + "." + element;
        return isAccessor() ? member + " (" + kind.name().toLowerCase() + ")" : member;
    }

    /** The name a {@code JNIMethodScope} reports, e.g. {@code demo.Api.run}, {@code demo.Api.<init>} or {@code demo.Api.getCount}. */
    String scopeName() {
        return owner().getQualifiedName() + "." + (isAccessor() ? defaultName() : element.getSimpleName());
    }

    /** Calls the member on {@code receiver} (a handle's object, or null for a static member) with {@code args}. */
    CodeBlock call(CodeBlock receiver, List<CodeBlock> args) {
        CodeBlock target = receiver == null ? CodeBlock.of("$T", ClassName.get(owner())) : receiver;
        String name = element.getSimpleName().toString();
        return switch (kind) {
            case METHOD -> CodeBlock.of("$L.$N($L)", target, name, CodeBlock.join(args, ", "));
            case CONSTRUCTOR -> CodeBlock.of("new $T($L)", ClassName.get(owner()), CodeBlock.join(args, ", "));
            case GETTER -> CodeBlock.of("$L.$N", target, name);
            case SETTER -> CodeBlock.of("$L.$N = $L", target, name, args.get(0));
        };
    }
}

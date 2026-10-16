package me.judge.nativebind;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeMirror;

/**
 * How a wrapped method or constructor is called. Constructors are exposed as static factories returning the new
 * object, named {@code new<Owner>} unless the annotation names them.
 */
final class Wrapped {
    private Wrapped() {
    }

    static boolean isConstructor(ExecutableElement method) {
        return method.getKind() == ElementKind.CONSTRUCTOR;
    }

    /** Whether the call needs a {@code self} handle to the receiver. */
    static boolean hasReceiver(ExecutableElement method) {
        return !isConstructor(method) && !method.getModifiers().contains(Modifier.STATIC);
    }

    static TypeMirror returnType(ExecutableElement method) {
        return isConstructor(method) ? method.getEnclosingElement().asType() : method.getReturnType();
    }

    static String defaultName(ExecutableElement method) {
        return isConstructor(method)
                ? "new" + method.getEnclosingElement().getSimpleName()
                : method.getSimpleName().toString();
    }

    /** How the wrapped member reads in documentation, e.g. {@code Api.run} or {@code new Api}. */
    static String displayName(ExecutableElement method) {
        String owner = method.getEnclosingElement().getSimpleName().toString();
        return isConstructor(method) ? "new " + owner : owner + "." + method.getSimpleName();
    }
}

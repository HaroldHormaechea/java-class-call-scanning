package com.hhg.callgraph.model;

import java.util.Objects;

public final class MethodReference {

    private final String className;
    private final String methodName;
    private final String descriptor;

    public MethodReference(String className, String methodName, String descriptor) {
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getDescriptor() {
        return descriptor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MethodReference)) return false;
        MethodReference that = (MethodReference) o;
        return Objects.equals(className, that.className)
                && Objects.equals(methodName, that.methodName)
                && Objects.equals(descriptor, that.descriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, descriptor);
    }

    /** Display-friendly form: dotted class name, e.g. {@code com.hhg.service.Foo#bar}. */
    public String toDisplayString() {
        return className.replace('/', '.') + "#" + methodName;
    }

    @Override
    public String toString() {
        return className + "#" + methodName + " " + descriptor;
    }
}

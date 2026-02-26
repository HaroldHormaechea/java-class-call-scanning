package com.hhg.callgraph.model;

public record FieldReference(String className, String fieldName, String descriptor) {

    /** Display-friendly form: dotted class name, e.g. {@code com.hhg.entity.Product#price}. */
    public String toDisplayString() {
        return className.replace('/', '.') + "#" + fieldName;
    }

    @Override
    public String toString() {
        return className + "#" + fieldName + " " + descriptor;
    }
}

package com.hhg.callgraph.daemon;

import com.hhg.callgraph.model.FieldReference;
import com.hhg.callgraph.model.MethodReference;

/**
 * JVM-descriptor FQN encoder / parser used on the wire (TCP + MCP).
 *
 * <p>Method form: {@code pkg.Cls#name(Ldesc;)Ret} — class is dotted; descriptor keeps
 * internal slashes. {@code <init>} and {@code <clinit>} are queryable by their reserved
 * names. Field form: {@code pkg.Cls#name:Ldesc;}.
 *
 * <p>Internal {@link MethodReference}/{@link FieldReference} keep {@code className} in
 * slash-form; this class is the boundary translator.
 */
public final class Fqn {

    private Fqn() {}

    // -----------------------------------------------------------------------
    // Encode
    // -----------------------------------------------------------------------

    public static String methodToFqn(MethodReference ref) {
        return ref.getClassName().replace('/', '.')
                + "#" + ref.getMethodName()
                + ref.getDescriptor();
    }

    public static String fieldToFqn(FieldReference ref) {
        return ref.className().replace('/', '.')
                + "#" + ref.fieldName()
                + ":" + ref.descriptor();
    }

    // -----------------------------------------------------------------------
    // Parse
    // -----------------------------------------------------------------------

    /** Parses a method FQN. The class name is converted to internal slash-form. */
    public static MethodReference parseMethod(String fqn) {
        if (fqn == null) throw new BadFqnException("method FQN is null");
        int hash = fqn.indexOf('#');
        if (hash <= 0 || hash >= fqn.length() - 1) {
            throw new BadFqnException("missing '#' separator in method FQN: " + fqn);
        }
        int paren = fqn.indexOf('(', hash + 1);
        if (paren <= hash + 1) {
            throw new BadFqnException("missing '(' descriptor opening in method FQN: " + fqn);
        }
        String dottedClass = fqn.substring(0, hash);
        String methodName  = fqn.substring(hash + 1, paren);
        String descriptor  = fqn.substring(paren);

        if (descriptor.indexOf(')') < 0) {
            throw new BadFqnException("missing ')' descriptor closing in method FQN: " + fqn);
        }
        if (dottedClass.isEmpty() || methodName.isEmpty()) {
            throw new BadFqnException("empty class or method name in: " + fqn);
        }
        return new MethodReference(dottedClass.replace('.', '/'), methodName, descriptor);
    }

    /** Parses a field FQN. The class name is converted to internal slash-form. */
    public static FieldReference parseField(String fqn) {
        if (fqn == null) throw new BadFqnException("field FQN is null");
        int hash = fqn.indexOf('#');
        if (hash <= 0 || hash >= fqn.length() - 1) {
            throw new BadFqnException("missing '#' separator in field FQN: " + fqn);
        }
        int colon = fqn.indexOf(':', hash + 1);
        if (colon <= hash + 1 || colon >= fqn.length() - 1) {
            throw new BadFqnException("missing ':' descriptor separator in field FQN: " + fqn);
        }
        String dottedClass = fqn.substring(0, hash);
        String fieldName   = fqn.substring(hash + 1, colon);
        String descriptor  = fqn.substring(colon + 1);

        if (dottedClass.isEmpty() || fieldName.isEmpty() || descriptor.isEmpty()) {
            throw new BadFqnException("empty class, field, or descriptor in: " + fqn);
        }
        return new FieldReference(dottedClass.replace('.', '/'), fieldName, descriptor);
    }

    /** Parses a dotted class name into internal slash-form (no validation beyond non-empty). */
    public static String parseDottedClass(String dotted) {
        if (dotted == null || dotted.isEmpty()) {
            throw new BadFqnException("dotted class name is null or empty");
        }
        return dotted.replace('.', '/');
    }

    /** Thrown when an FQN cannot be parsed. Carries the bad input in its message. */
    public static final class BadFqnException extends RuntimeException {
        public BadFqnException(String message) {
            super(message);
        }
    }
}

package com.hhg.callgraph.daemon;

import com.hhg.callgraph.model.FieldReference;
import com.hhg.callgraph.model.MethodReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers AC #10 — JVM-descriptor FQN form on both input and output, with {@code <init>}
 * and {@code <clinit>} queryable by their reserved names.
 */
@DisplayName("Fqn — JVM descriptor encode/parse + <init>/<clinit>")
class FqnTest {

    @Nested
    @DisplayName("Method FQN round-trip")
    class MethodRoundTrip {

        @Test
        @DisplayName("dotted class encode, slash-form on internal reference")
        void plainMethod() {
            MethodReference ref = new MethodReference(
                    "com/acme/Foo", "bar", "(Ljava/lang/String;)V");
            String encoded = Fqn.methodToFqn(ref);
            assertEquals("com.acme.Foo#bar(Ljava/lang/String;)V", encoded);

            MethodReference back = Fqn.parseMethod(encoded);
            assertEquals(ref, back);
        }

        @Test
        @DisplayName("<init> survives round-trip")
        void constructorRoundTrip() {
            MethodReference ref = new MethodReference(
                    "com/acme/Foo", "<init>", "(I)V");
            String encoded = Fqn.methodToFqn(ref);
            assertEquals("com.acme.Foo#<init>(I)V", encoded);
            assertEquals(ref, Fqn.parseMethod(encoded));
        }

        @Test
        @DisplayName("<clinit> survives round-trip")
        void clinitRoundTrip() {
            MethodReference ref = new MethodReference(
                    "com/acme/Foo", "<clinit>", "()V");
            String encoded = Fqn.methodToFqn(ref);
            assertEquals("com.acme.Foo#<clinit>()V", encoded);
            assertEquals(ref, Fqn.parseMethod(encoded));
        }

        @Test
        @DisplayName("nested class with $ in name round-trips")
        void nestedClassRoundTrip() {
            MethodReference ref = new MethodReference(
                    "com/acme/Foo$Inner", "doIt", "()I");
            String encoded = Fqn.methodToFqn(ref);
            assertEquals("com.acme.Foo$Inner#doIt()I", encoded);
            assertEquals(ref, Fqn.parseMethod(encoded));
        }

        @Test
        @DisplayName("complex descriptor with multiple args + return-type")
        void complexDescriptorRoundTrip() {
            MethodReference ref = new MethodReference(
                    "com/acme/Bar", "compute",
                    "(Ljava/util/List;ILjava/lang/String;)Ljava/util/Map;");
            String encoded = Fqn.methodToFqn(ref);
            assertEquals(ref, Fqn.parseMethod(encoded));
        }
    }

    @Nested
    @DisplayName("Field FQN round-trip")
    class FieldRoundTrip {

        @Test
        @DisplayName("plain field encode/parse")
        void plainField() {
            FieldReference ref = new FieldReference(
                    "com/acme/Foo", "bar", "Ljava/lang/String;");
            String encoded = Fqn.fieldToFqn(ref);
            assertEquals("com.acme.Foo#bar:Ljava/lang/String;", encoded);

            FieldReference back = Fqn.parseField(encoded);
            assertEquals(ref, back);
        }

        @Test
        @DisplayName("primitive descriptor")
        void primitiveField() {
            FieldReference ref = new FieldReference("com/acme/Foo", "count", "I");
            String encoded = Fqn.fieldToFqn(ref);
            assertEquals("com.acme.Foo#count:I", encoded);
            assertEquals(ref, Fqn.parseField(encoded));
        }
    }

    @Nested
    @DisplayName("Malformed inputs")
    class Malformed {

        @Test
        @DisplayName("null method FQN throws BadFqn")
        void nullMethod() {
            assertThrows(Fqn.BadFqnException.class, () -> Fqn.parseMethod(null));
        }

        @Test
        @DisplayName("missing '#' throws BadFqn")
        void noHash() {
            assertThrows(Fqn.BadFqnException.class,
                    () -> Fqn.parseMethod("com.acme.Foobar()V"));
        }

        @Test
        @DisplayName("missing '(' descriptor opening throws BadFqn")
        void noParen() {
            assertThrows(Fqn.BadFqnException.class,
                    () -> Fqn.parseMethod("com.acme.Foo#barV"));
        }

        @Test
        @DisplayName("missing ')' descriptor closing throws BadFqn")
        void noCloseParen() {
            assertThrows(Fqn.BadFqnException.class,
                    () -> Fqn.parseMethod("com.acme.Foo#bar(I"));
        }

        @Test
        @DisplayName("empty class throws BadFqn")
        void emptyClass() {
            assertThrows(Fqn.BadFqnException.class,
                    () -> Fqn.parseMethod("#bar()V"));
        }

        @Test
        @DisplayName("empty method throws BadFqn")
        void emptyMethod() {
            assertThrows(Fqn.BadFqnException.class,
                    () -> Fqn.parseMethod("com.acme.Foo#()V"));
        }

        @Test
        @DisplayName("null field FQN throws BadFqn")
        void nullField() {
            assertThrows(Fqn.BadFqnException.class, () -> Fqn.parseField(null));
        }

        @Test
        @DisplayName("field FQN missing colon throws BadFqn")
        void fieldNoColon() {
            assertThrows(Fqn.BadFqnException.class,
                    () -> Fqn.parseField("com.acme.Foo#barLjava/lang/String;"));
        }

        @Test
        @DisplayName("field FQN with empty descriptor throws BadFqn")
        void fieldEmptyDescriptor() {
            assertThrows(Fqn.BadFqnException.class,
                    () -> Fqn.parseField("com.acme.Foo#bar:"));
        }

        @Test
        @DisplayName("parseDottedClass converts dots → slashes")
        void parseDottedClass() {
            assertEquals("com/acme/Foo", Fqn.parseDottedClass("com.acme.Foo"));
        }

        @Test
        @DisplayName("parseDottedClass null/empty throws BadFqn")
        void parseDottedClassEmpty() {
            assertThrows(Fqn.BadFqnException.class, () -> Fqn.parseDottedClass(null));
            assertThrows(Fqn.BadFqnException.class, () -> Fqn.parseDottedClass(""));
        }
    }
}

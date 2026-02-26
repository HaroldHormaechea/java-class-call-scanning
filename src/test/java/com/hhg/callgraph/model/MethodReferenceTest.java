package com.hhg.callgraph.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MethodReferenceTest {

    @Test
    @DisplayName("Constructor stores all fields correctly")
    void constructorStoresFields() {
        MethodReference ref = new MethodReference("com/example/MyClass", "myMethod", "(I)V");

        assertEquals("com/example/MyClass", ref.getClassName());
        assertEquals("myMethod", ref.getMethodName());
        assertEquals("(I)V", ref.getDescriptor());
    }

    @Test
    @DisplayName("Two references with same fields are equal")
    void equalWhenSameFields() {
        MethodReference a = new MethodReference("com/example/Foo", "bar", "()V");
        MethodReference b = new MethodReference("com/example/Foo", "bar", "()V");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("Different class name means not equal")
    void notEqualWhenDifferentClassName() {
        MethodReference a = new MethodReference("com/example/Foo", "bar", "()V");
        MethodReference b = new MethodReference("com/example/Baz", "bar", "()V");

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("Different method name means not equal")
    void notEqualWhenDifferentMethodName() {
        MethodReference a = new MethodReference("com/example/Foo", "bar", "()V");
        MethodReference b = new MethodReference("com/example/Foo", "qux", "()V");

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("Different descriptor means not equal -- distinguishes overloads")
    void notEqualWhenDifferentDescriptor() {
        MethodReference noArgs = new MethodReference("com/example/Foo", "bar", "()V");
        MethodReference withInt = new MethodReference("com/example/Foo", "bar", "(I)V");

        assertNotEquals(noArgs, withInt);
    }

    @Test
    @DisplayName("Not equal to null")
    void notEqualToNull() {
        MethodReference ref = new MethodReference("com/example/Foo", "bar", "()V");

        assertNotEquals(null, ref);
    }

    @Test
    @DisplayName("Not equal to object of different type")
    void notEqualToDifferentType() {
        MethodReference ref = new MethodReference("com/example/Foo", "bar", "()V");

        assertNotEquals("a string", ref);
    }

    @Test
    @DisplayName("Equal to itself (reflexive)")
    void reflexiveEquality() {
        MethodReference ref = new MethodReference("com/example/Foo", "bar", "()V");

        assertEquals(ref, ref);
    }

    @Test
    @DisplayName("Equality is symmetric")
    void symmetricEquality() {
        MethodReference a = new MethodReference("com/example/Foo", "bar", "()V");
        MethodReference b = new MethodReference("com/example/Foo", "bar", "()V");

        assertEquals(a, b);
        assertEquals(b, a);
    }

    @Test
    @DisplayName("Equality is transitive")
    void transitiveEquality() {
        MethodReference a = new MethodReference("com/example/Foo", "bar", "()V");
        MethodReference b = new MethodReference("com/example/Foo", "bar", "()V");
        MethodReference c = new MethodReference("com/example/Foo", "bar", "()V");

        assertEquals(a, b);
        assertEquals(b, c);
        assertEquals(a, c);
    }

    @Test
    @DisplayName("Equal objects produce the same hashCode consistently")
    void consistentHashCode() {
        MethodReference a = new MethodReference("com/example/Foo", "bar", "(I)V");
        MethodReference b = new MethodReference("com/example/Foo", "bar", "(I)V");

        assertEquals(a.hashCode(), b.hashCode());
        // Call multiple times to verify consistency
        assertEquals(a.hashCode(), a.hashCode());
    }

    @Test
    @DisplayName("Works correctly as HashSet element")
    void worksInHashSet() {
        MethodReference a = new MethodReference("com/example/Foo", "bar", "()V");
        MethodReference b = new MethodReference("com/example/Foo", "bar", "()V");
        MethodReference c = new MethodReference("com/example/Foo", "baz", "()V");

        Set<MethodReference> set = new HashSet<>();
        set.add(a);
        set.add(b); // duplicate, should not increase size
        set.add(c);

        assertEquals(2, set.size());
        assertTrue(set.contains(a));
        assertTrue(set.contains(b));
        assertTrue(set.contains(c));
    }

    @Test
    @DisplayName("toString follows format: className#methodName descriptor")
    void toStringFormat() {
        MethodReference ref = new MethodReference(
                "com/hhg/main/targets/TargetClass1",
                "methodInClass1",
                "()V"
        );

        assertEquals("com/hhg/main/targets/TargetClass1#methodInClass1 ()V", ref.toString());
    }

    @Test
    @DisplayName("toString handles constructor <init>")
    void toStringWithConstructor() {
        MethodReference ref = new MethodReference(
                "com/example/Foo",
                "<init>",
                "()V"
        );

        assertEquals("com/example/Foo#<init> ()V", ref.toString());
    }

    @Test
    @DisplayName("toString handles static initializer <clinit>")
    void toStringWithStaticInit() {
        MethodReference ref = new MethodReference(
                "com/example/Foo",
                "<clinit>",
                "()V"
        );

        assertEquals("com/example/Foo#<clinit> ()V", ref.toString());
    }
}

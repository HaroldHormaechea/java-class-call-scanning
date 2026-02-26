package com.hhg.callgraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SourceLocation")
class SourceLocationTest {

    @Test
    @DisplayName("Stores and exposes fields correctly")
    void fieldStorage() {
        SourceLocation loc = new SourceLocation("Foo.java", 7, 9);
        assertEquals("Foo.java", loc.sourceFile());
        assertEquals(7, loc.startLine());
        assertEquals(9, loc.endLine());
    }

    @Test
    @DisplayName("containsLine: true when line is within range")
    void containsLineWithinRange() {
        SourceLocation loc = new SourceLocation("Foo.java", 7, 9);
        assertTrue(loc.containsLine(8));
    }

    @Test
    @DisplayName("containsLine: true at exact startLine")
    void containsLineAtStart() {
        SourceLocation loc = new SourceLocation("Foo.java", 7, 9);
        assertTrue(loc.containsLine(7));
    }

    @Test
    @DisplayName("containsLine: true at exact endLine")
    void containsLineAtEnd() {
        SourceLocation loc = new SourceLocation("Foo.java", 7, 9);
        assertTrue(loc.containsLine(9));
    }

    @Test
    @DisplayName("containsLine: false before startLine")
    void containsLineBeforeStart() {
        SourceLocation loc = new SourceLocation("Foo.java", 7, 9);
        assertFalse(loc.containsLine(6));
    }

    @Test
    @DisplayName("containsLine: false after endLine")
    void containsLineAfterEnd() {
        SourceLocation loc = new SourceLocation("Foo.java", 7, 9);
        assertFalse(loc.containsLine(10));
    }

    @Test
    @DisplayName("containsLine: always false when isUnknown")
    void containsLineWhenUnknown() {
        SourceLocation loc = new SourceLocation("Foo.java", -1, -1);
        assertFalse(loc.containsLine(1));
        assertFalse(loc.containsLine(100));
        assertFalse(loc.containsLine(-1));
    }

    @Test
    @DisplayName("isUnknown: true when sentinel -1/-1")
    void isUnknownTrue() {
        SourceLocation loc = new SourceLocation("Foo.java", -1, -1);
        assertTrue(loc.isUnknown());
    }

    @Test
    @DisplayName("isUnknown: false when line info is present")
    void isUnknownFalse() {
        SourceLocation loc = new SourceLocation("Foo.java", 7, 9);
        assertFalse(loc.isUnknown());
    }

    @Test
    @DisplayName("equals and hashCode: two equal instances match")
    void equalsAndHashCode() {
        SourceLocation a = new SourceLocation("Foo.java", 7, 9);
        SourceLocation b = new SourceLocation("Foo.java", 7, 9);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("toString: known location formats as 'file:start-end'")
    void toStringKnown() {
        SourceLocation loc = new SourceLocation("Foo.java", 7, 9);
        assertEquals("Foo.java:7-9", loc.toString());
    }

    @Test
    @DisplayName("toString: unknown location formats as 'file:(unknown)'")
    void toStringUnknown() {
        SourceLocation loc = new SourceLocation("Foo.java", -1, -1);
        assertEquals("Foo.java:(unknown)", loc.toString());
    }

    @Test
    @DisplayName("Constructor rejects startLine > endLine")
    void constructorRejectsInvalidRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new SourceLocation("Foo.java", 10, 5));
    }

    @Test
    @DisplayName("Constructor accepts -1/-1 sentinel without throwing")
    void constructorAcceptsSentinel() {
        assertDoesNotThrow(() -> new SourceLocation("Foo.java", -1, -1));
    }

    @Test
    @DisplayName("Single-line method (startLine == endLine) is valid")
    void singleLineMethodIsValid() {
        assertDoesNotThrow(() -> new SourceLocation("Foo.java", 5, 5));
        SourceLocation loc = new SourceLocation("Foo.java", 5, 5);
        assertTrue(loc.containsLine(5));
        assertFalse(loc.containsLine(4));
        assertFalse(loc.containsLine(6));
    }
}

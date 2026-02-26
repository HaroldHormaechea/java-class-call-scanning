package com.hhg.callgraph.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SourceIndex")
class SourceIndexTest {

    private SourceIndex index;
    private MethodReference methodA;
    private MethodReference methodB;
    private MethodReference methodC;

    @BeforeEach
    void setUp() {
        index = new SourceIndex();
        methodA = new MethodReference("com/example/Foo", "doSomething", "()V");
        methodB = new MethodReference("com/example/Bar", "doOther", "()V");
        methodC = new MethodReference("com/example/Foo", "doThird", "()V");
    }

    @Test
    @DisplayName("getLocation returns stored location for known method")
    void getLocationReturnsStoredLocation() {
        SourceLocation loc = new SourceLocation("Foo.java", 10, 15);
        index.add(methodA, loc);

        Optional<SourceLocation> result = index.getLocation(methodA);

        assertTrue(result.isPresent());
        assertEquals(loc, result.get());
    }

    @Test
    @DisplayName("getLocation returns empty for unknown method")
    void getLocationReturnsEmptyForUnknown() {
        Optional<SourceLocation> result = index.getLocation(methodA);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("size() tracks additions correctly")
    void sizeTracksAdditions() {
        assertEquals(0, index.size());
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));
        assertEquals(1, index.size());
        index.add(methodB, new SourceLocation("Bar.java", 5, 8));
        assertEquals(2, index.size());
    }

    @Test
    @DisplayName("findMethodsAt: line at startLine finds method")
    void findMethodsAtStartLine() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("Foo.java", 10);

        assertTrue(result.contains(methodA));
    }

    @Test
    @DisplayName("findMethodsAt: line at endLine finds method")
    void findMethodsAtEndLine() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("Foo.java", 15);

        assertTrue(result.contains(methodA));
    }

    @Test
    @DisplayName("findMethodsAt: line within range finds method")
    void findMethodsAtWithinRange() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("Foo.java", 12);

        assertTrue(result.contains(methodA));
    }

    @Test
    @DisplayName("findMethodsAt: line before startLine returns empty")
    void findMethodsAtBeforeStart() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("Foo.java", 9);

        assertFalse(result.contains(methodA));
    }

    @Test
    @DisplayName("findMethodsAt: line after endLine returns empty")
    void findMethodsAtAfterEnd() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("Foo.java", 16);

        assertFalse(result.contains(methodA));
    }

    @Test
    @DisplayName("findMethodsAt: multiple methods in same file, only matching ones returned")
    void findMethodsAtReturnsOnlyMatching() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));
        index.add(methodC, new SourceLocation("Foo.java", 20, 25));

        Set<MethodReference> result = index.findMethodsAt("Foo.java", 12);

        assertTrue(result.contains(methodA));
        assertFalse(result.contains(methodC));
    }

    @Test
    @DisplayName("findMethodsAt: method with unknown location never matches")
    void findMethodsAtUnknownLocationNeverMatches() {
        index.add(methodA, new SourceLocation("Foo.java", -1, -1));

        Set<MethodReference> result = index.findMethodsAt("Foo.java", 1);

        assertFalse(result.contains(methodA));
    }

    @Test
    @DisplayName("Path normalization: full relative path matches filename-only stored")
    void pathNormalizationFullPath() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("src/main/java/com/example/Foo.java", 12);

        assertTrue(result.contains(methodA));
    }

    @Test
    @DisplayName("Path normalization: backslash path matches filename-only stored")
    void pathNormalizationBackslash() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("src\\main\\Foo.java", 12);

        assertTrue(result.contains(methodA));
    }

    @Test
    @DisplayName("Path normalization: already-simple filename matches")
    void pathNormalizationSimple() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("Foo.java", 12);

        assertTrue(result.contains(methodA));
    }

    @Test
    @DisplayName("Package-qualified suffix match: diff path with source root prefix matches correctly")
    void packageQualifiedSuffixMatch() {
        // methodA class is "com/example/Foo" → packageRelative = "com/example/Foo.java"
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("src/main/java/com/example/Foo.java", 12);

        assertTrue(result.contains(methodA));
    }

    @Test
    @DisplayName("Package-qualified suffix match: does NOT match same-named file in different package")
    void packageQualifiedSuffixMatchRejectsDifferentPackage() {
        // methodA is "com/example/Foo", methodB is "com/example/Bar"
        // We add a third method in a different package with the same file name
        MethodReference otherFoo = new MethodReference("com/other/Foo", "method", "()V");
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));  // com/example/Foo
        index.add(otherFoo, new SourceLocation("Foo.java", 5, 9));   // com/other/Foo

        // Query for com/example/Foo.java should only match methodA, not otherFoo
        Set<MethodReference> result = index.findMethodsAt("src/main/java/com/example/Foo.java", 12);

        assertTrue(result.contains(methodA), "com/example/Foo should match");
        assertFalse(result.contains(otherFoo), "com/other/Foo should NOT match com/example/Foo.java");
    }

    @Test
    @DisplayName("Exact package-relative path (no root prefix) matches")
    void exactPackageRelativePathMatches() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("com/example/Foo.java", 12);

        assertTrue(result.contains(methodA));
    }

    @Test
    @DisplayName("findMethodsAt result is unmodifiable")
    void resultIsUnmodifiable() {
        index.add(methodA, new SourceLocation("Foo.java", 10, 15));

        Set<MethodReference> result = index.findMethodsAt("Foo.java", 12);

        assertThrows(UnsupportedOperationException.class,
                () -> result.add(methodB));
    }
}

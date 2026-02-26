package com.hhg.callgraph.diff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GitDiffParser")
class GitDiffParserTest {

    private GitDiffParser parser;

    @BeforeEach
    void setUp() {
        parser = new GitDiffParser();
    }

    @Nested
    @DisplayName("Empty and trivial input")
    class EmptyInput {

        @Test
        @DisplayName("Empty string returns empty list")
        void emptyStringReturnsEmptyList() {
            List<DiffEntry> result = parser.parse("");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Null-safe: whitespace-only string returns empty list")
        void whitespaceOnlyReturnsEmptyList() {
            List<DiffEntry> result = parser.parse("   \n\n  ");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Single file, single hunk")
    class SingleFileSingleHunk {

        private static final String DIFF = """
                diff --git a/src/main/java/com/example/Foo.java b/src/main/java/com/example/Foo.java
                index abc1234..def5678 100644
                --- a/src/main/java/com/example/Foo.java
                +++ b/src/main/java/com/example/Foo.java
                @@ -10,6 +10,8 @@ public class Foo {
                     public void bar() {
                         System.out.println("hello");
                +        int x = 1;
                +        int y = 2;
                     }
                 }
                """;

        @Test
        @DisplayName("Produces exactly one DiffEntry")
        void producesOneDiffEntry() {
            List<DiffEntry> result = parser.parse(DIFF);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("File path is correctly extracted (b/ prefix stripped)")
        void filePathExtracted() {
            List<DiffEntry> result = parser.parse(DIFF);
            assertEquals("src/main/java/com/example/Foo.java", result.get(0).filePath());
        }

        @Test
        @DisplayName("Changed lines contain only the added lines")
        void changedLinesCorrect() {
            List<DiffEntry> result = parser.parse(DIFF);
            Set<Integer> changed = result.get(0).changedLines();
            assertTrue(changed.contains(12), "Line 12 should be changed (first +)");
            assertTrue(changed.contains(13), "Line 13 should be changed (second +)");
            assertEquals(2, changed.size(), "Only 2 added lines");
        }
    }

    @Nested
    @DisplayName("File path stripping")
    class FilePathStripping {

        @Test
        @DisplayName("+++ b/src/main/java/Foo.java strips 'b/' prefix")
        void stripsBPrefix() {
            String diff = """
                    diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
                    --- a/src/main/java/Foo.java
                    +++ b/src/main/java/Foo.java
                    @@ -1,3 +1,4 @@
                     class Foo {
                    +    int x;
                     }
                    """;
            List<DiffEntry> result = parser.parse(diff);
            assertEquals(1, result.size());
            assertEquals("src/main/java/Foo.java", result.get(0).filePath());
        }
    }

    @Nested
    @DisplayName("Multiple files")
    class MultipleFiles {

        private static final String DIFF = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,3 +1,4 @@
                 class Foo {
                +    int x;
                 }
                diff --git a/Bar.java b/Bar.java
                --- a/Bar.java
                +++ b/Bar.java
                @@ -5,3 +5,4 @@
                 class Bar {
                +    int y;
                 }
                """;

        @Test
        @DisplayName("Multiple files in one diff produce multiple DiffEntry objects")
        void multipleFilesProduceMultipleEntries() {
            List<DiffEntry> result = parser.parse(DIFF);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("Each DiffEntry has the correct file path")
        void eachEntryHasCorrectPath() {
            List<DiffEntry> result = parser.parse(DIFF);
            assertEquals("Foo.java", result.get(0).filePath());
            assertEquals("Bar.java", result.get(1).filePath());
        }

        @Test
        @DisplayName("Each DiffEntry has the correct changed lines")
        void eachEntryHasCorrectChangedLines() {
            List<DiffEntry> result = parser.parse(DIFF);
            assertTrue(result.get(0).changedLines().contains(2));
            assertTrue(result.get(1).changedLines().contains(6));
        }
    }

    @Nested
    @DisplayName("Line classification")
    class LineClassification {

        @Test
        @DisplayName("Deleted lines (prefixed with -) are not counted as changed")
        void deletedLinesNotCounted() {
            String diff = """
                    diff --git a/Foo.java b/Foo.java
                    --- a/Foo.java
                    +++ b/Foo.java
                    @@ -1,4 +1,3 @@
                     class Foo {
                    -    int removed;
                         int kept;
                     }
                    """;
            List<DiffEntry> result = parser.parse(diff);
            assertEquals(1, result.size());
            assertTrue(result.get(0).changedLines().isEmpty(),
                    "Deleted lines should not appear in changedLines");
        }

        @Test
        @DisplayName("Context lines (no prefix) are not counted as changed")
        void contextLinesNotCounted() {
            String diff = """
                    diff --git a/Foo.java b/Foo.java
                    --- a/Foo.java
                    +++ b/Foo.java
                    @@ -1,3 +1,4 @@
                     class Foo {
                    +    int added;
                         int context;
                     }
                    """;
            List<DiffEntry> result = parser.parse(diff);
            Set<Integer> changed = result.get(0).changedLines();
            assertEquals(1, changed.size(), "Only 1 added line, context lines excluded");
            assertTrue(changed.contains(2));
        }

        @Test
        @DisplayName("+++ header line itself is not counted as an addition")
        void plusPlusPlusHeaderNotCounted() {
            String diff = """
                    diff --git a/Foo.java b/Foo.java
                    --- a/Foo.java
                    +++ b/Foo.java
                    @@ -1,3 +1,3 @@
                     class Foo {
                     }
                    """;
            List<DiffEntry> result = parser.parse(diff);
            assertEquals(1, result.size());
            assertTrue(result.get(0).changedLines().isEmpty(),
                    "+++ header should not be counted as an added line");
        }
    }

    @Nested
    @DisplayName("Hunk offset handling")
    class HunkOffsetHandling {

        @Test
        @DisplayName("@@ -10,3 +20,4 @@ additions start at line 20")
        void hunkOffsetApplied() {
            String diff = """
                    diff --git a/Foo.java b/Foo.java
                    --- a/Foo.java
                    +++ b/Foo.java
                    @@ -10,3 +20,4 @@ some context
                     existing line
                    +added line
                     another existing
                    """;
            List<DiffEntry> result = parser.parse(diff);
            Set<Integer> changed = result.get(0).changedLines();
            assertTrue(changed.contains(21),
                    "Added line should be at line 21 (hunk starts at 20, one context line, then addition)");
            assertEquals(1, changed.size());
        }
    }

    @Nested
    @DisplayName("Multiple hunks in same file")
    class MultipleHunks {

        @Test
        @DisplayName("Multiple hunks in same file are merged into one DiffEntry")
        void multipleHunksMerged() {
            String diff = """
                    diff --git a/Foo.java b/Foo.java
                    --- a/Foo.java
                    +++ b/Foo.java
                    @@ -1,3 +1,4 @@
                     class Foo {
                    +    int first;
                         void bar() {}
                    @@ -10,3 +11,4 @@
                         void baz() {
                    +        int second;
                         }
                    """;
            List<DiffEntry> result = parser.parse(diff);
            assertEquals(1, result.size(), "Multiple hunks in same file should produce one DiffEntry");
            Set<Integer> changed = result.get(0).changedLines();
            assertTrue(changed.contains(2), "First hunk addition at line 2");
            assertTrue(changed.contains(12), "Second hunk addition at line 12");
            assertEquals(2, changed.size());
        }
    }
}

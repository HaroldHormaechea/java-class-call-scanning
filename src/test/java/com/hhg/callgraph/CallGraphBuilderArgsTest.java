package com.hhg.callgraph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CallGraphBuilder — CLI arg parsing")
class CallGraphBuilderArgsTest {

    private CallGraphBuilder.CliArgs parse(String... args) {
        return CallGraphBuilder.parseArgs(args);
    }

    @Nested
    @DisplayName("Happy paths")
    class HappyPaths {

        @Test
        @DisplayName("--compiled alone sets mode to summary and export format to console")
        void compiledAlone() {
            var cli = parse("--compiled", "build/classes/java/test");
            assertNotNull(cli);
            assertEquals(List.of(Path.of("build/classes/java/test")), cli.compiled());
            assertNull(cli.sources());
            assertEquals("summary", cli.mode());
            assertNull(cli.modeArg());
            assertNull(cli.modeArgStr());
            assertEquals("console", cli.exportFormat());
        }

        @Test
        @DisplayName("--export-format json is accepted")
        void exportFormatJson() {
            var cli = parse("--compiled", "classes", "--export-format", "json");
            assertNotNull(cli);
            assertEquals("json", cli.exportFormat());
        }

        @Test
        @DisplayName("--export-format console is accepted")
        void exportFormatConsole() {
            var cli = parse("--compiled", "classes", "--export-format", "console");
            assertNotNull(cli);
            assertEquals("console", cli.exportFormat());
        }

        @Test
        @DisplayName("--compiled + --diff")
        void compiledAndDiff() {
            var cli = parse("--compiled", "classes", "--diff", "changes.patch");
            assertNotNull(cli);
            assertEquals(List.of(Path.of("classes")), cli.compiled());
            assertEquals("diff", cli.mode());
            assertEquals(Path.of("changes.patch"), cli.modeArg());
        }

        @Test
        @DisplayName("--compiled + --diff-stdin")
        void compiledAndDiffStdin() {
            var cli = parse("--compiled", "classes", "--diff-stdin");
            assertNotNull(cli);
            assertEquals("diff-stdin", cli.mode());
        }

        @Test
        @DisplayName("--compiled + --print-hierarchy")
        void compiledAndPrintHierarchy() {
            var cli = parse("--compiled", "classes", "--print-hierarchy", "com.example.Foo#bar");
            assertNotNull(cli);
            assertEquals("print-hierarchy", cli.mode());
            assertEquals("com.example.Foo#bar", cli.modeArgStr());
        }

        @Test
        @DisplayName("--sources is accepted alongside --compiled")
        void sourcesAccepted() {
            var cli = parse("--compiled", "classes", "--sources", "src/main/java");
            assertNotNull(cli);
            assertEquals(Path.of("src/main/java"), cli.sources());
        }

        @Test
        @DisplayName("flags can appear in any order")
        void flagOrderIndependent() {
            var cli = parse("--diff", "my.patch", "--compiled", "out");
            assertNotNull(cli);
            assertEquals(List.of(Path.of("out")), cli.compiled());
            assertEquals("diff", cli.mode());
        }
    }

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("empty args returns null")
        void emptyArgsReturnsNull() {
            assertNull(parse());
        }

        @Test
        @DisplayName("missing --compiled returns null")
        void missingCompiledReturnsNull() {
            assertNull(parse("--diff", "changes.patch"));
        }

        @Test
        @DisplayName("unknown flag returns null")
        void unknownFlagReturnsNull() {
            assertNull(parse("--compiled", "classes", "--unknown-flag", "value"));
        }

        @Test
        @DisplayName("--compiled with no argument returns null")
        void compiledMissingValueReturnsNull() {
            assertNull(parse("--compiled"));
        }

        @Test
        @DisplayName("--diff with no argument returns null")
        void diffMissingValueReturnsNull() {
            assertNull(parse("--compiled", "classes", "--diff"));
        }

        @Test
        @DisplayName("--print-hierarchy with no argument returns null")
        void hierarchyMissingValueReturnsNull() {
            assertNull(parse("--compiled", "classes", "--print-hierarchy"));
        }

        @Test
        @DisplayName("--export-format with unknown value returns null")
        void exportFormatUnknownValueReturnsNull() {
            assertNull(parse("--compiled", "classes", "--export-format", "xml"));
        }

        @Test
        @DisplayName("--export-format with no argument returns null")
        void exportFormatMissingValueReturnsNull() {
            assertNull(parse("--compiled", "classes", "--export-format"));
        }
    }
}

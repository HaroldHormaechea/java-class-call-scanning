package com.hhg.callgraph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers AC #21 — the existing one-shot pipeline entry point continues to work.
 *
 * <p>This test re-asserts the canonical flag-combinations previously covered by
 * {@link CallGraphBuilderArgsTest} (which itself remains green untouched per the
 * developer's change summary). The redundancy is deliberate: it gives us a single,
 * dedicated test that fails loudly if any of the UC01 changes accidentally regress
 * legacy CLI behaviour.
 *
 * <p>Per the use-case clarifications, the three-clause top-level dispatcher must:
 * <ol>
 *   <li>route to legacy {@code CliArgs} when the first arg starts with {@code --};</li>
 *   <li>route to {@link com.hhg.callgraph.cli.DaemonCli} on a bare recognised token;</li>
 *   <li>print a "did you mean?" error for an unknown bare token.</li>
 * </ol>
 *
 * <p>The legacy path is exercised here via {@link CallGraphBuilder#parseArgs(String[])}.
 */
@DisplayName("Legacy pipeline (AC #21) — --compiled / --diff / --print-hierarchy still work")
class LegacyPipelineUnchangedTest {

    @Test
    @DisplayName("--compiled alone → summary mode")
    void compiledOnly() {
        var cli = CallGraphBuilder.parseArgs(
                new String[]{"--compiled", "build/classes/java/test"});
        assertNotNull(cli);
        assertEquals(List.of(Path.of("build/classes/java/test")), cli.compiled());
        assertEquals("summary", cli.mode());
        assertNull(cli.sources());
        assertEquals("console", cli.exportFormat());
    }

    @Test
    @DisplayName("--compiled + --diff <file> → diff mode")
    void compiledAndDiff() {
        var cli = CallGraphBuilder.parseArgs(
                new String[]{"--compiled", "out", "--diff", "changes.patch"});
        assertNotNull(cli);
        assertEquals("diff", cli.mode());
        assertEquals(Path.of("changes.patch"), cli.modeArg());
    }

    @Test
    @DisplayName("--compiled + --diff-stdin → diff-stdin mode")
    void compiledAndDiffStdin() {
        var cli = CallGraphBuilder.parseArgs(
                new String[]{"--compiled", "out", "--diff-stdin"});
        assertNotNull(cli);
        assertEquals("diff-stdin", cli.mode());
    }

    @Test
    @DisplayName("--compiled + --print-hierarchy <ref> → print-hierarchy mode")
    void compiledAndPrintHierarchy() {
        var cli = CallGraphBuilder.parseArgs(
                new String[]{"--compiled", "out", "--print-hierarchy", "com.example.Foo#bar"});
        assertNotNull(cli);
        assertEquals("print-hierarchy", cli.mode());
        assertEquals("com.example.Foo#bar", cli.modeArgStr());
    }

    @Test
    @DisplayName("--compiled + --sources + --export-format json")
    void compiledSourcesJson() {
        var cli = CallGraphBuilder.parseArgs(new String[]{
                "--compiled", "out",
                "--sources", "src/main/java",
                "--export-format", "json"});
        assertNotNull(cli);
        assertEquals(Path.of("src/main/java"), cli.sources());
        assertEquals("json", cli.exportFormat());
    }

    @Test
    @DisplayName("--compiled may be passed multiple times")
    void multipleCompiled() {
        var cli = CallGraphBuilder.parseArgs(new String[]{
                "--compiled", "a", "--compiled", "b"});
        assertNotNull(cli);
        assertEquals(List.of(Path.of("a"), Path.of("b")), cli.compiled());
    }

    @Test
    @DisplayName("flags may appear in any order")
    void flagOrderIndependent() {
        var cli = CallGraphBuilder.parseArgs(new String[]{
                "--diff", "x.patch", "--compiled", "out"});
        assertNotNull(cli);
        assertEquals("diff", cli.mode());
        assertEquals(Path.of("out"), cli.compiled().get(0));
    }

    @Test
    @DisplayName("missing --compiled remains an error (returns null)")
    void missingCompiledIsError() {
        assertNull(CallGraphBuilder.parseArgs(new String[]{"--diff", "x.patch"}));
    }

    @Test
    @DisplayName("unknown legacy flag remains an error (returns null)")
    void unknownLegacyFlagIsError() {
        assertNull(CallGraphBuilder.parseArgs(new String[]{
                "--compiled", "out", "--frobnicate"}));
    }
}

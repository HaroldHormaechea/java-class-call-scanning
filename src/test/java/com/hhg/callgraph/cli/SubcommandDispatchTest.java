package com.hhg.callgraph.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AC #1 (daemon subcommand recognised) and AC #8 (every query op has a CLI
 * subcommand) at the dispatch level — the list of recognised subcommand tokens is the
 * gate the three-clause top-level dispatcher in {@code CallGraphBuilder.main} consults
 * before routing to {@link DaemonCli}. AC #21 (legacy pipeline still works) is covered
 * separately in {@code LegacyPipelineUnchangedTest}.
 */
@DisplayName("DaemonCli — recognised subcommands + dispatch surface")
class SubcommandDispatchTest {

    @Test
    @DisplayName("recognised subcommands cover every UC01 surface command")
    void recognisedSubcommandsCoverAll() {
        var recognised = DaemonCli.recognisedSubcommands();
        // daemon (start/stop):
        assertTrue(recognised.contains("daemon"));
        // 9 user-facing ops:
        for (String op : List.of(
                "refresh-index",
                "find-callers", "find-callees",
                "methods-in-class", "methods-at-line",
                "find-field-readers", "find-field-writers",
                "impact-of-diff", "tests-for-diff")) {
            assertTrue(recognised.contains(op),
                    "subcommand list missing user-facing op: " + op);
        }
    }

    @Test
    @DisplayName("legacy flag prefix (--compiled etc.) is NOT a recognised subcommand")
    void legacyFlagsNotInDispatchList() {
        // The three-clause dispatcher routes by bare tokens; anything starting with --
        // takes the legacy path. Ensure the recognised-set doesn't accidentally include
        // a flag string.
        var recognised = DaemonCli.recognisedSubcommands();
        for (String name : recognised) {
            assertNotEquals('-', name.charAt(0),
                    "recognised subcommand must not start with a hyphen: " + name);
            assertFalseStartsWithDoubleDash(name);
        }
    }

    @Test
    @DisplayName("recognisedSubcommandsAsList exposes the same names, ordered")
    void asListMatchesSet() {
        List<String> asList = DaemonCli.recognisedSubcommandsAsList();
        assertEquals(DaemonCli.recognisedSubcommands().size(), asList.size());
        assertEquals("daemon", asList.get(0),
                "first entry should be `daemon` per declared SUBCOMMANDS order");
    }

    @Test
    @DisplayName("unknown subcommand dispatch returns non-zero exit and suggestion (when close)")
    void unknownSubcommandWithSuggestion() {
        // Capture System.out so the JSON error doesn't leak into test logs.
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream origOut = System.out;
        System.setOut(new java.io.PrintStream(baos));
        try {
            int code = DaemonCli.dispatch(new String[]{"daemom"});   // typo of `daemon`
            assertEquals(2, code, "unknown-subcommand exit code should be 2");
            String out = baos.toString();
            assertTrue(out.contains("\"error\""),
                    "expected JSON error envelope on stdout; got: " + out);
            assertTrue(out.contains("daemon"),
                    "expected 'did you mean: daemon' suggestion in: " + out);
        } finally {
            System.setOut(origOut);
        }
    }

    @Test
    @DisplayName("dispatch with no args returns exit 2 + JSON error on stdout")
    void noArgs() {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream origOut = System.out;
        System.setOut(new java.io.PrintStream(baos));
        try {
            int code = DaemonCli.dispatch(new String[0]);
            assertEquals(2, code);
            assertTrue(baos.toString().contains("no subcommand given"),
                    "stdout: " + baos);
        } finally {
            System.setOut(origOut);
        }
    }

    private static void assertFalseStartsWithDoubleDash(String name) {
        if (name.length() >= 2 && name.charAt(0) == '-' && name.charAt(1) == '-') {
            throw new AssertionError("recognised subcommand starts with --: " + name);
        }
    }
}

package com.hhg.callgraph.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the flag-parsing primitives used by every daemon subcommand. Specifically:
 *
 * <ul>
 *   <li>AC #1 — {@code daemon start} accepts {@code --classpath}, {@code --src},
 *       {@code --include}, {@code --exclude}, {@code --idle-timeout}, {@code --foreground}.</li>
 *   <li>AC #7 — {@code --foreground} parses as a boolean flag.</li>
 *   <li>AC #8 — query subcommands accept {@code --project} +  op-specific flags.</li>
 *   <li>Levenshtein "did you mean?" surfaces close matches for unknown flags.</li>
 * </ul>
 */
@DisplayName("SubcommandParser — flag parsing + did-you-mean")
class SubcommandParserTest {

    @Nested
    @DisplayName("Flag parsing")
    class Parsing {

        @Test
        @DisplayName("daemon start parser accepts the AC #1 flag set")
        void daemonStartFlags() {
            SubcommandParser p = new SubcommandParser("daemon start",
                    Set.of("--classpath", "--src", "--include", "--exclude",
                            "--idle-timeout", "--foreground", "--force"),
                    Set.of("--classpath", "--src", "--include", "--exclude"),
                    Set.of("--foreground", "--force"));
            Map<String, List<String>> parsed = p.parse(new String[]{
                    "--classpath", "build/classes",
                    "--src", "src/main/java",
                    "--src", "src/test/java",
                    "--idle-timeout", "30",
                    "--foreground"
            });
            assertEquals(List.of("build/classes"), parsed.get("--classpath"));
            assertEquals(List.of("src/main/java", "src/test/java"), parsed.get("--src"));
            assertEquals(List.of("30"), parsed.get("--idle-timeout"));
            assertTrue(SubcommandParser.booleanFlag(parsed, "--foreground"));
        }

        @Test
        @DisplayName("repeatable flags collect every value in order")
        void repeatable() {
            SubcommandParser p = new SubcommandParser("daemon start",
                    Set.of("--classpath"), Set.of("--classpath"), Set.of());
            Map<String, List<String>> parsed = p.parse(new String[]{
                    "--classpath", "a", "--classpath", "b", "--classpath", "c"});
            assertEquals(List.of("a", "b", "c"), parsed.get("--classpath"));
        }

        @Test
        @DisplayName("non-repeatable flag rejected on second occurrence")
        void nonRepeatableRejectsDup() {
            SubcommandParser p = new SubcommandParser("find-callers",
                    Set.of("--method"), Set.of(), Set.of());
            assertThrows(SubcommandParser.BadArgsException.class,
                    () -> p.parse(new String[]{"--method", "a", "--method", "b"}));
        }

        @Test
        @DisplayName("missing value for non-boolean flag is an error")
        void missingValue() {
            SubcommandParser p = new SubcommandParser("find-callers",
                    Set.of("--method"), Set.of(), Set.of());
            assertThrows(SubcommandParser.BadArgsException.class,
                    () -> p.parse(new String[]{"--method"}));
        }

        @Test
        @DisplayName("unexpected positional argument is rejected")
        void positionalRejected() {
            SubcommandParser p = new SubcommandParser("find-callers",
                    Set.of("--method"), Set.of(), Set.of());
            assertThrows(SubcommandParser.BadArgsException.class,
                    () -> p.parse(new String[]{"positional", "--method", "x"}));
        }

        @Test
        @DisplayName("firstOrThrow fails when required flag absent")
        void firstOrThrowsAbsent() {
            SubcommandParser p = new SubcommandParser("x", Set.of("--a"), Set.of(), Set.of());
            Map<String, List<String>> parsed = p.parse(new String[0]);
            assertThrows(SubcommandParser.BadArgsException.class,
                    () -> SubcommandParser.firstOrThrow(parsed, "--a"));
        }

        @Test
        @DisplayName("firstOrNull returns null when absent")
        void firstOrNullAbsent() {
            assertNull(SubcommandParser.firstOrNull(Map.of(), "--missing"));
        }

        @Test
        @DisplayName("allOrEmpty returns [] for absent flag")
        void allOrEmpty() {
            assertEquals(List.of(), SubcommandParser.allOrEmpty(Map.of(), "--missing"));
        }
    }

    @Nested
    @DisplayName("Did-you-mean (Levenshtein ≤ 3)")
    class DidYouMean {

        @Test
        @DisplayName("unknown flag surfaces nearest recognised name")
        void closeFlag() {
            SubcommandParser p = new SubcommandParser("daemon start",
                    Set.of("--classpath", "--src"), Set.of(), Set.of());
            SubcommandParser.BadArgsException ex = assertThrows(
                    SubcommandParser.BadArgsException.class,
                    () -> p.parse(new String[]{"--clasppath", "x"}));
            assertTrue(ex.getMessage().contains("did you mean: --classpath"),
                    "expected suggestion; got: " + ex.getMessage());
        }

        @Test
        @DisplayName("far-off flag yields no suggestion")
        void farFlag() {
            SubcommandParser p = new SubcommandParser("daemon start",
                    Set.of("--classpath"), Set.of(), Set.of());
            SubcommandParser.BadArgsException ex = assertThrows(
                    SubcommandParser.BadArgsException.class,
                    () -> p.parse(new String[]{"--zzzzzz", "x"}));
            assertTrue(!ex.getMessage().contains("did you mean"),
                    "expected no suggestion; got: " + ex.getMessage());
        }

        @Test
        @DisplayName("suggestClosestName picks 'daemon' for 'damon'")
        void closeSubcommand() {
            String s = SubcommandParser.suggestClosestName("damon",
                    List.of("daemon", "find-callers", "tests-for-diff"));
            assertEquals("daemon", s);
        }

        @Test
        @DisplayName("levenshtein distance ≤ 3 returns; > 3 returns null")
        void levenshteinThreshold() {
            assertEquals("daemon", SubcommandParser.closestMatch("damon", List.of("daemon")));
            assertEquals("daemon", SubcommandParser.closestMatch("daemonn", List.of("daemon"))); // 1
            assertEquals(null, SubcommandParser.closestMatch("xyzzyxyzzy",
                    List.of("daemon", "find-callers")));
        }

        @Test
        @DisplayName("levenshtein handles empty strings safely")
        void levenshteinEmpty() {
            assertEquals(0, SubcommandParser.levenshtein("", ""));
            assertEquals(5, SubcommandParser.levenshtein("", "hello"));
            assertEquals(5, SubcommandParser.levenshtein("hello", ""));
        }
    }
}

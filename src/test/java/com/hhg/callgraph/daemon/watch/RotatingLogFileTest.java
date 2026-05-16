package com.hhg.callgraph.daemon.watch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC04 AC #17(j) + AC #13 — the rotating log file rolls past the size threshold,
 * keeps {@code maxFiles} rotated copies, and old entries remain tailable after
 * rotation.
 *
 * <p>This is a unit test against {@link RotatingLogFile} directly — no
 * {@link FileWatcher} is involved. We use a tiny size threshold (a few hundred bytes)
 * to keep the test fast, then verify the rotation contract holds: {@code <base>.log}
 * is the freshest, {@code .log.1} is the most recent rotated copy, and the oldest
 * is pruned on rotation. UC04 §13 names 10 MB × 5 files in production; the rotation
 * algorithm is size-agnostic and exercising it at a small size proves the same
 * contract without writing megabytes of test data.
 */
@DisplayName("RotatingLogFile — UC04 AC #13 / AC #17(j) size-based rotation")
class RotatingLogFileTest {

    @Test
    @DisplayName("first writes go to <base>.log and no rotated files are created early")
    void noRotationBeforeThreshold(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("watch.log");
        RotatingLogFile rl = new RotatingLogFile(base, /*maxSize=*/ 10_000L, /*maxFiles=*/ 3);
        rl.write("first line");
        rl.write("second line");

        assertTrue(Files.exists(base));
        assertFalse(Files.exists(base.resolveSibling("watch.log.1")));
        String content = Files.readString(base, StandardCharsets.UTF_8);
        assertTrue(content.contains("first line"));
        assertTrue(content.contains("second line"));
    }

    @Test
    @DisplayName("writing past max size rolls to .log.1 and starts a fresh <base>.log")
    void rotatesPastThreshold(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("watch.log");
        // 60-byte budget: a ~50-char first line + newline fits (~51 bytes); a second
        // ~20-char line + newline pushes total beyond 60 → rotation triggers.
        RotatingLogFile rl = new RotatingLogFile(base, /*maxSize=*/ 60L, /*maxFiles=*/ 5);

        rl.write("first-line-marker-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"); // ~50 chars
        // This write would push total past 60 → rotation must fire before append.
        rl.write("post-rotation-line-1");

        assertTrue(Files.exists(base.resolveSibling("watch.log.1")),
                "first rotation must produce a .log.1");
        String rotated = Files.readString(base.resolveSibling("watch.log.1"),
                StandardCharsets.UTF_8);
        assertTrue(rotated.contains("first-line-marker"),
                "rotated file should hold the previous content");

        String current = Files.readString(base, StandardCharsets.UTF_8);
        assertTrue(current.contains("post-rotation-line-1"),
                "current file should be a fresh stream starting at the rotation point");
        assertFalse(current.contains("first-line-marker"),
                "current file must not contain post-rotation content from before the roll");
    }

    @Test
    @DisplayName("repeated rotation shifts files and prunes past maxFiles")
    void prunesOldestOnceLimitReached(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("watch.log");
        RotatingLogFile rl = new RotatingLogFile(base, /*maxSize=*/ 50L, /*maxFiles=*/ 2);

        // Drive several rotations.
        rl.write("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"); // 1
        rl.write("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"); // 2 → rotates
        rl.write("ccccccccccccccccccccccccccccccccccccccccccccc"); // 3 → rotates
        rl.write("ddddddddddddddddddddddddddddddddddddddddddddd"); // 4 → rotates

        Path log1 = base.resolveSibling("watch.log.1");
        Path log2 = base.resolveSibling("watch.log.2");
        Path log3 = base.resolveSibling("watch.log.3");

        assertTrue(Files.exists(log1), ".log.1 should exist after multiple rotations");
        assertTrue(Files.exists(log2), ".log.2 should exist after multiple rotations");
        assertFalse(Files.exists(log3),
                "maxFiles=2 → .log.3 must never appear; oldest is dropped on rotation");
    }

    @Test
    @DisplayName("old entries remain readable in rotated files (tailable across rotation)")
    void rotatedEntriesStayTailable(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("watch.log");
        RotatingLogFile rl = new RotatingLogFile(base, /*maxSize=*/ 50L, /*maxFiles=*/ 3);

        rl.write("OLD-ENTRY-A");
        rl.write("OLD-ENTRY-B-padding-to-force-rotation-XXXXXXX");
        rl.write("NEW-ENTRY-1");

        // Collect lines from base + all rotated files.
        List<Path> all = List.of(
                base,
                base.resolveSibling("watch.log.1"),
                base.resolveSibling("watch.log.2"));
        StringBuilder sb = new StringBuilder();
        for (Path p : all) {
            if (Files.exists(p)) sb.append(Files.readString(p, StandardCharsets.UTF_8));
        }
        String unified = sb.toString();
        assertTrue(unified.contains("OLD-ENTRY-A"), "OLD-ENTRY-A must survive across rotation");
        assertTrue(unified.contains("OLD-ENTRY-B"), "OLD-ENTRY-B must survive across rotation");
        assertTrue(unified.contains("NEW-ENTRY-1"), "NEW-ENTRY-1 must be in current log");
    }

    @Test
    @DisplayName("each write appends exactly one platform-line-separator")
    void writesOneNewlinePerCall(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("watch.log");
        RotatingLogFile rl = new RotatingLogFile(base, 100_000L, 1);
        rl.write("line-one");
        rl.write("line-two");
        rl.write("line-three");

        String content = Files.readString(base, StandardCharsets.UTF_8);
        long newlines = content.chars().filter(c -> c == '\n').count();
        assertEquals(3, newlines, "exactly one newline per write");
    }

    @Test
    @DisplayName("logFile() returns the configured base path")
    void logFileReturnsBase(@TempDir Path tmp) {
        Path base = tmp.resolve("watch.log");
        RotatingLogFile rl = new RotatingLogFile(base, 100L, 1);
        assertEquals(base, rl.logFile());
    }

    @Test
    @DisplayName("creates parent directory lazily on first write")
    void createsParentDir(@TempDir Path tmp) throws IOException {
        Path nested = tmp.resolve("a").resolve("b").resolve("watch.log");
        assertFalse(Files.exists(nested.getParent()));
        RotatingLogFile rl = new RotatingLogFile(nested, 100L, 1);
        rl.write("hi");
        assertTrue(Files.exists(nested));
    }
}

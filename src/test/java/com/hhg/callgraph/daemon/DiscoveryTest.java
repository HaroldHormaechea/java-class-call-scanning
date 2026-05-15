package com.hhg.callgraph.daemon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AC #4 (discovery file path/schema), AC #9 (stale PID detection / delete +
 * non-zero exit), and the read/write/delete contract used by {@code daemon stop} (AC #6).
 *
 * <p>Tests use a tmpdir-backed {@link Discovery} rather than {@link Discovery#defaults}
 * so they never touch the real {@code ~/.cache/java-class-call-scanning} folder.
 */
@DisplayName("Discovery — write/read/delete + PID liveness")
class DiscoveryTest {

    @Test
    @DisplayName("write + read round-trip preserves all fields")
    void writeReadRoundTrip(@TempDir Path tmp) throws IOException {
        Discovery discovery = new Discovery(tmp, "1.0-TEST");
        Path cp = Files.createDirectory(tmp.resolve("cp"));
        Path sr = Files.createDirectory(tmp.resolve("sr"));
        ScopeConfig scope = ScopeConfig.of(List.of(cp), List.of(sr),
                List.of("**/*.java"), List.of("**/test/**"));

        long pid = ProcessHandle.current().pid();
        int port = 54321;
        long startedAt = 1_700_000_000_000L;
        discovery.write(scope, pid, port, startedAt);

        Path file = discovery.filePath(scope.projectHash());
        assertTrue(Files.exists(file));

        Optional<Discovery.Record> rec = Discovery.read(file);
        assertTrue(rec.isPresent());
        Discovery.Record r = rec.get();
        assertEquals(scope.projectHash(), r.projectHash());
        assertEquals(pid, r.pid());
        assertEquals(port, r.port());
        assertEquals(startedAt, r.startedAtEpochMillis());
        assertEquals("1.0-TEST", r.daemonVersion());
        assertTrue(r.classpath().get(0).endsWith("cp"));
        assertTrue(r.sourceRoots().get(0).endsWith("sr"));
        assertEquals(List.of("**/*.java"), r.include());
        assertEquals(List.of("**/test/**"), r.exclude());
    }

    @Test
    @DisplayName("read of nonexistent file returns empty")
    void readNonexistent(@TempDir Path tmp) {
        assertFalse(Discovery.read(tmp.resolve("missing.json")).isPresent());
    }

    @Test
    @DisplayName("read of malformed file returns empty (no throw)")
    void readMalformed(@TempDir Path tmp) throws IOException {
        Path bad = tmp.resolve("bad.json");
        Files.writeString(bad, "{not valid json");
        assertFalse(Discovery.read(bad).isPresent());
    }

    @Test
    @DisplayName("read of unsupported schema returns empty")
    void readUnsupportedSchema(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("future.json");
        Files.writeString(file, "{\"schema\":999,\"project_hash\":\"abc\",\"pid\":1,\"port\":1}");
        assertFalse(Discovery.read(file).isPresent());
    }

    @Test
    @DisplayName("delete removes the discovery file")
    void deleteFile(@TempDir Path tmp) throws IOException {
        Discovery discovery = new Discovery(tmp, "1.0");
        Path cp = Files.createDirectory(tmp.resolve("cp"));
        ScopeConfig scope = ScopeConfig.of(List.of(cp), List.of(), List.of(), List.of());
        discovery.write(scope, 1L, 1, 0L);
        assertTrue(Files.exists(discovery.filePath(scope.projectHash())));

        assertTrue(discovery.delete(scope.projectHash()));
        assertFalse(Files.exists(discovery.filePath(scope.projectHash())));

        // Second delete is idempotent (returns false, but doesn't throw).
        assertFalse(discovery.delete(scope.projectHash()));
    }

    @Test
    @DisplayName("filePath uses <project-hash>.json under the directory")
    void filePathLayout(@TempDir Path tmp) {
        Discovery discovery = new Discovery(tmp, "1.0");
        Path p = discovery.filePath("deadbeef00112233");
        assertEquals(tmp.resolve("deadbeef00112233.json"), p);
        // companion lock and log files use the same hash:
        assertEquals(tmp.resolve("deadbeef00112233.lock"), discovery.lockPath("deadbeef00112233"));
        assertEquals(tmp.resolve("deadbeef00112233.log"),  discovery.logPath("deadbeef00112233"));
    }

    @Test
    @DisplayName("pidAlive returns true for the current JVM and false for a synthetic dead PID")
    void pidAlive() {
        long me = ProcessHandle.current().pid();
        assertTrue(Discovery.pidAlive(me), "current PID should be live");
        // 0 isn't a valid PID on any common OS — and ProcessHandle.of(0) typically
        // returns empty. Use that as a "no such process" check (covers AC #9).
        assertFalse(Discovery.pidAlive(0L));
    }

    @Test
    @DisplayName("resolveCacheDirectory ends with project-name segment")
    void cacheDir() {
        Path resolved = Discovery.resolveCacheDirectory();
        assertNotNull(resolved);
        assertEquals("java-class-call-scanning", resolved.getFileName().toString(),
                "cache dir must terminate in the project-name segment (AC #4)");
    }

    @Test
    @DisplayName("ensureDirectory creates the path lazily")
    void ensureDirectory(@TempDir Path tmp) throws IOException {
        Path nested = tmp.resolve("a/b/c");
        Discovery discovery = new Discovery(nested, "1.0");
        assertFalse(Files.exists(nested));
        discovery.ensureDirectory();
        assertTrue(Files.isDirectory(nested));
    }
}

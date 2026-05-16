package com.hhg.callgraph.daemon.watch;

import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.IndexSnapshot;
import com.hhg.callgraph.daemon.ScanMeta;
import com.hhg.callgraph.daemon.ScopeConfig;
import com.hhg.callgraph.daemon.op.OperationResult;
import com.hhg.callgraph.daemon.op.Operations;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;
import com.hhg.callgraph.scanner.test.JUnit5TestDetector;
import com.hhg.callgraph.scanner.test.SpockTestDetector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * UC04 AC #17(i) — {@code watcher-status} returns the AC #14 payload over the dispatch
 * surface used by both TCP and MCP. We exercise {@link Operations#dispatch} directly
 * (the same façade both transports route through, as documented in
 * {@code OperationsBenchmarkTest}), so this test doubles as the dispatch contract for
 * both surfaces.
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>After a rebuild → payload reflects {@code enabled:true}, last_rebuild populated,
 *       watched roots non-empty, log_file non-null.</li>
 *   <li>After a {@code skipped} (source-only) event → last_rebuild has
 *       {@code outcome: "skipped"} and {@code trigger_watcher: "source"}.</li>
 *   <li>After {@code --no-watch} (disabled) → payload reports {@code enabled:false}
 *       and empty watched root arrays, but still echoes log_file + log_schema_version.</li>
 * </ol>
 *
 * <p>Also covers AC #6 / §6: while {@code restartInProgress} is true, all ops except
 * {@code watcher-status}, {@code ping}, and {@code shutdown} are rejected with
 * {@code restart-in-progress}.
 */
@DisplayName("Operations dispatch — watcher-status payload (UC04 AC #17(i))")
class WatcherStatusOperationTest {

    private static Path benchmarkDir;
    private static IndexSnapshot snapshot;

    @BeforeAll
    static void buildIndex() throws IOException {
        benchmarkDir = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/benchmark");
        if (!Files.exists(benchmarkDir)) {
            fail("Benchmark classes not compiled; run `./gradlew compileTestJava` first.");
        }
        ScanResult scan = new ClassFileScanner(List.of(
                new JUnit5TestDetector(), new SpockTestDetector())).scan(benchmarkDir);
        ScopeConfig scope = ScopeConfig.of(List.of(benchmarkDir),
                List.of(Path.of(System.getProperty("user.dir")).resolve("src/test/java")),
                List.of(), List.of());
        ScanMeta meta = new ScanMeta(scan.callGraph().methodCount(),
                scan.callGraph().edgeCount(), 7L, System.currentTimeMillis());
        snapshot = new IndexSnapshot(scan, scope, meta);
    }

    @Test
    @DisplayName("dispatch('watcher-status') after a real rebuild — full payload populated")
    void afterRebuild(@TempDir Path logDir) {
        Path logFile = logDir.resolve("watcher.log");
        WatcherStatusHolder holder = new WatcherStatusHolder(true, 1000, logFile, 1);
        holder.setWatchedRoots(List.of(benchmarkDir),
                List.of(Path.of(System.getProperty("user.dir")).resolve("src/test/java")),
                List.of(Path.of(System.getProperty("user.dir")).resolve("build.gradle")));
        holder.recordRebuild("classpath", "success", 42L,
                /*scopeClassCount=*/ 3, /*scopeArchiveCount=*/ 0);
        holder.clearError();

        Operations ops = new Operations(() -> snapshot, /*refresher=*/ null,
                holder::snapshot, /*restartInProgress=*/ () -> false);

        OperationResult r = ops.dispatch("watcher-status", new JsonObject());
        JsonObject payload = expectOk(r);

        // Top-level shape per AC #14.
        assertTrue(payload.get("enabled").getAsBoolean());
        assertNotNull(payload.getAsJsonArray("watched_classpath_roots"));
        assertEquals(1, payload.getAsJsonArray("watched_classpath_roots").size());
        assertEquals(benchmarkDir.toAbsolutePath().toString(),
                payload.getAsJsonArray("watched_classpath_roots").get(0).getAsString());
        assertEquals(1, payload.getAsJsonArray("watched_source_roots").size());
        assertEquals(1, payload.getAsJsonArray("watched_build_config_files").size());
        assertEquals(1000, payload.get("debounce_ms").getAsLong());
        assertEquals(logFile.toAbsolutePath().toString(),
                payload.get("log_file").getAsString());
        assertEquals(1, payload.get("log_schema_version").getAsInt());
        assertTrue(payload.get("last_error").isJsonNull(),
                "last_error must be null after clearError()");

        // last_rebuild sub-object.
        JsonObject lr = payload.getAsJsonObject("last_rebuild");
        assertEquals("classpath", lr.get("trigger_watcher").getAsString());
        assertEquals("success", lr.get("outcome").getAsString());
        assertEquals(42L, lr.get("elapsed_ms").getAsLong());
        assertEquals(3, lr.get("scope_class_count").getAsInt());
        assertEquals(0, lr.get("scope_archive_count").getAsInt());
        assertTrue(lr.has("timestamp"),
                "timestamp must be present (ISO-8601 UTC)");
        // timestamp parses as ISO-8601:
        java.time.Instant.parse(lr.get("timestamp").getAsString());
    }

    @Test
    @DisplayName("dispatch('watcher-status') after a source-only 'skipped' event")
    void afterSkippedSourceOnly(@TempDir Path logDir) {
        Path logFile = logDir.resolve("watcher.log");
        WatcherStatusHolder holder = new WatcherStatusHolder(true, 1000, logFile, 1);
        holder.setWatchedRoots(List.of(), List.of(), List.of());
        holder.recordSkipped("source", 0L);

        Operations ops = new Operations(() -> snapshot, null, holder::snapshot, () -> false);
        JsonObject payload = expectOk(ops.dispatch("watcher-status", new JsonObject()));
        JsonObject lr = payload.getAsJsonObject("last_rebuild");
        assertEquals("source", lr.get("trigger_watcher").getAsString());
        assertEquals("skipped", lr.get("outcome").getAsString());
        assertEquals(0, lr.get("scope_class_count").getAsInt());
        assertEquals(0, lr.get("scope_archive_count").getAsInt());
    }

    @Test
    @DisplayName("--no-watch payload: enabled=false, empty arrays, log_file echoed")
    void noWatchPayload(@TempDir Path logDir) {
        Path logFile = logDir.resolve("watcher.log");
        WatcherStatusHolder holder = new WatcherStatusHolder(false, 1000, logFile, 1);
        holder.setDisabled();

        Operations ops = new Operations(() -> snapshot, null, holder::snapshot, () -> false);
        JsonObject payload = expectOk(ops.dispatch("watcher-status", new JsonObject()));
        assertFalse(payload.get("enabled").getAsBoolean());
        assertEquals(0, payload.getAsJsonArray("watched_classpath_roots").size());
        assertEquals(0, payload.getAsJsonArray("watched_source_roots").size());
        assertEquals(0, payload.getAsJsonArray("watched_build_config_files").size());
        assertEquals(logFile.toAbsolutePath().toString(),
                payload.get("log_file").getAsString());
        assertTrue(payload.get("last_rebuild").isJsonNull(),
                "no rebuild has happened yet so last_rebuild must be JSON null");
    }

    @Test
    @DisplayName("last_error is reported when recordError(...) is called")
    void lastErrorReported(@TempDir Path logDir) {
        WatcherStatusHolder holder = new WatcherStatusHolder(true, 1000,
                logDir.resolve("w.log"), 1);
        holder.recordError(new IllegalStateException("synthetic"));

        Operations ops = new Operations(() -> snapshot, null, holder::snapshot, () -> false);
        JsonObject payload = expectOk(ops.dispatch("watcher-status", new JsonObject()));
        JsonObject le = payload.getAsJsonObject("last_error");
        assertEquals("java.lang.IllegalStateException", le.get("class").getAsString());
        assertEquals("synthetic", le.get("message").getAsString());
        // clearError should reset.
        holder.clearError();
        JsonObject again = expectOk(ops.dispatch("watcher-status", new JsonObject()));
        assertTrue(again.get("last_error").isJsonNull());
    }

    @Test
    @DisplayName("watcher-status is permitted during a self-restart (other ops are blocked)")
    void watcherStatusBypassesRestartGate(@TempDir Path logDir) {
        WatcherStatusHolder holder = new WatcherStatusHolder(true, 1000,
                logDir.resolve("w.log"), 1);
        AtomicBoolean restarting = new AtomicBoolean(true);
        Operations ops = new Operations(() -> snapshot, null,
                holder::snapshot, restarting::get);

        // watcher-status passes through.
        OperationResult okR = ops.dispatch("watcher-status", new JsonObject());
        assertTrue(okR instanceof OperationResult.Success,
                "watcher-status MUST be permitted during self-restart per AC #6 / §6");

        // A non-status op is rejected with restart-in-progress.
        JsonObject args = new JsonObject();
        args.addProperty("method_fqn", "com.acme.Foo#bar()V");
        OperationResult blocked = ops.dispatch("find-callers", args);
        OperationResult.Failure f = expectFailure(blocked);
        assertEquals("restart-in-progress", f.errorCode());
    }

    @Test
    @DisplayName("watcher-status fails with watcher-unavailable when no provider is wired")
    void watcherStatusUnavailable() {
        Operations ops = new Operations(() -> snapshot, null,
                /*watcherStatusProvider=*/ null, () -> false);
        OperationResult.Failure f = expectFailure(ops.dispatch("watcher-status", new JsonObject()));
        assertEquals("watcher-unavailable", f.errorCode());
    }

    @Test
    @DisplayName("WatcherStatusHolder.snapshot is thread-safe under concurrent recordRebuild/snapshot")
    void concurrentSnapshotSafe(@TempDir Path logDir) throws Exception {
        WatcherStatusHolder holder = new WatcherStatusHolder(true, 1000,
                logDir.resolve("w.log"), 1);
        AtomicReference<Throwable> seenError = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 10_000; i++) {
                    holder.recordRebuild("classpath", "success", i, i, 0);
                }
            } catch (Throwable t) {
                seenError.set(t);
            }
        });
        Thread reader = new Thread(() -> {
            try {
                for (int i = 0; i < 10_000; i++) {
                    holder.snapshot();
                }
            } catch (Throwable t) {
                seenError.set(t);
            }
        });
        writer.start();
        reader.start();
        writer.join(5_000);
        reader.join(5_000);
        assertNull(seenError.get(),
                "snapshot() and recordRebuild() must not throw under contention");
    }

    private static JsonObject expectOk(OperationResult r) {
        if (r instanceof OperationResult.Success s) return s.result();
        fail("expected Success, got Failure: " + r);
        return null;
    }

    private static OperationResult.Failure expectFailure(OperationResult r) {
        if (r instanceof OperationResult.Failure f) return f;
        fail("expected Failure, got Success: " + r);
        return null;
    }
}

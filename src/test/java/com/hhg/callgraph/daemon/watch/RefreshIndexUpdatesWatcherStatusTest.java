package com.hhg.callgraph.daemon.watch;

import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.Daemon;
import com.hhg.callgraph.daemon.Discovery;
import com.hhg.callgraph.daemon.ScopeConfig;
import com.hhg.callgraph.daemon.op.OperationResult;
import com.hhg.callgraph.daemon.op.Operations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.hhg.callgraph.daemon.watch.LogFileAssertions.awaitLogLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * UC04 AC #17(k) — calling {@code refresh-index} while the watcher is running:
 * <ul>
 *   <li>produces no torn snapshot (the {@code AtomicReference} + {@code ReadWriteLock}
 *       discipline serialises through);</li>
 *   <li>updates {@code watcher-status.last_rebuild.trigger_watcher} to {@code "manual"};</li>
 *   <li>clears {@code last_error}.</li>
 * </ul>
 *
 * <p>Also AC #14 last-mile: after a fresh {@code daemon start}, {@code watcher-status}
 * must report a non-null {@code last_rebuild} (the initial scan counts as a manual
 * rebuild, per UC04 §15).
 */
@DisplayName("refresh-index updates watcher-status (UC04 AC #17(k) + AC #14 last_rebuild)")
class RefreshIndexUpdatesWatcherStatusTest {

    private Daemon daemon;

    @AfterEach
    void cleanup() {
        if (daemon != null) {
            try { daemon.stop(); } catch (Throwable ignored) {}
        }
    }

    @Test
    @DisplayName("refresh-index sets last_rebuild.trigger_watcher = 'manual' and clears last_error")
    void refreshIndexFlipsToManual(@TempDir Path discoveryDir, @TempDir Path classpath) throws IOException {
        Path benchmark = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/benchmark");
        if (!Files.exists(benchmark)) {
            fail("Benchmark classes not compiled — run `./gradlew compileTestJava` first.");
        }
        // Seed a couple of benchmark classes into the test classpath.
        Path serviceDir = classpath.resolve("com/hhg/benchmark/service");
        Files.createDirectories(serviceDir);
        Files.copy(benchmark.resolve("service/UserService.class"),
                serviceDir.resolve("UserService.class"));

        Path logFile = discoveryDir.resolve("watcher.log");
        ScopeConfig scope = ScopeConfig.of(List.of(classpath), List.of(), List.of(), List.of());
        Discovery discovery = new Discovery(discoveryDir, "test-version");
        WatcherConfig watcherCfg = new WatcherConfig(true, 250,
                WatcherConfig.DEFAULT_RESTART_DRAIN_MS, logFile,
                WatcherConfig.DEFAULT_LOG_MAX_SIZE_BYTES,
                WatcherConfig.DEFAULT_LOG_MAX_FILES);
        LaunchArgs args = new LaunchArgs(scope, /*idleMinutes=*/ 0,
                /*foreground=*/ true, watcherCfg);

        daemon = Daemon.startForScope(args, discovery, /*force=*/ false);
        assertNotNull(daemon);

        // Initial scan satisfies AC #14: watcher-status.last_rebuild is non-null.
        JsonObject initialStatus = daemon.statusHolder().snapshot();
        JsonObject initialLr = initialStatus.getAsJsonObject("last_rebuild");
        assertNotNull(initialLr,
                "AC #14: last_rebuild must be non-null after daemon start (initial scan = manual rebuild)");
        assertEquals("manual", initialLr.get("trigger_watcher").getAsString());
        assertEquals("success", initialLr.get("outcome").getAsString());

        // Synthetic error so we can verify refresh-index clears it.
        daemon.statusHolder().recordError(new IllegalStateException("synthetic-pre-refresh"));
        JsonObject preRefresh = daemon.statusHolder().snapshot();
        assertTrue(preRefresh.has("last_error"));
        assertTrue(!preRefresh.get("last_error").isJsonNull(),
                "we just recorded a last_error — it should be present in the payload");

        // Call refresh-index via Operations.dispatch.
        Operations ops = daemon.operations();
        OperationResult r = ops.dispatch("refresh-index", new JsonObject());
        assertTrue(r instanceof OperationResult.Success,
                "refresh-index must succeed; got " + r);

        // last_rebuild now reflects manual + last_error is cleared.
        JsonObject afterStatus = daemon.statusHolder().snapshot();
        JsonObject lr = afterStatus.getAsJsonObject("last_rebuild");
        assertEquals("manual", lr.get("trigger_watcher").getAsString());
        assertEquals("success", lr.get("outcome").getAsString());
        assertTrue(afterStatus.get("last_error").isJsonNull(),
                "refresh-index must clear last_error per UC04 §15");

        // The structured log includes the manual rebuild entry.
        JsonObject manualLogLine = awaitLogLine(logFile,
                obj -> obj.has("trigger.watcher")
                        && "manual".equals(obj.get("trigger.watcher").getAsString()),
                Duration.ofSeconds(10));
        assertNotNull(manualLogLine);
        assertEquals("watch.rebuild", manualLogLine.get("event").getAsString());
    }
}

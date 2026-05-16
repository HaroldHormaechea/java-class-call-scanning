package com.hhg.callgraph.daemon.watch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hhg.callgraph.daemon.Daemon;
import com.hhg.callgraph.daemon.Discovery;
import com.hhg.callgraph.daemon.ScopeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.hhg.callgraph.daemon.watch.LogFileAssertions.awaitLogLine;
import static com.hhg.callgraph.daemon.watch.LogFileAssertions.selfRestartPhase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * UC04 AC #17(e) — a {@code build.gradle} change triggers a graceful self-restart that
 * ends in {@code phase: "completed"}, with the daemon back on a new TCP port and the
 * discovery file updated. We also drive a post-restart op over the new TCP port to
 * prove the surface is alive.
 *
 * <p>Runs in-process against a real {@link Daemon} (no child JVM) so we can drive the
 * watcher and inspect its state directly. The {@code CALLGRAPH_DISCOVERY_DIR} override
 * is set via a fresh {@link Discovery} instance pointed at the JUnit {@link TempDir}.
 */
@DisplayName("FileWatcher — graceful self-restart on build-config change (UC04 AC #17(e))")
class FileWatcherSelfRestartHappyPathTest {

    private static final Gson GSON = new Gson();

    private Daemon daemon;

    @AfterEach
    void cleanup() {
        if (daemon != null) {
            try { daemon.stop(); } catch (Throwable ignored) {}
        }
    }

    @Test
    @DisplayName("touching build.gradle drives the daemon through phase: starting → completed on a new port")
    void buildGradleChangeRestartsDaemon(@TempDir Path discoveryDir,
                                         @TempDir Path projectRoot) throws Exception {
        // Layout:
        //   <projectRoot>/build.gradle
        //   <projectRoot>/classes/com/hhg/benchmark/service/UserService.class
        //   <projectRoot>/src/main/java   (empty; just so the watcher has a source root)
        Path benchmark = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/benchmark");
        if (!Files.exists(benchmark)) {
            fail("Benchmark classes not compiled — run `./gradlew compileTestJava` first.");
        }
        Path classpath = projectRoot.resolve("classes");
        Files.createDirectories(classpath.resolve("com/hhg/benchmark/service"));
        Files.copy(benchmark.resolve("service/UserService.class"),
                classpath.resolve("com/hhg/benchmark/service/UserService.class"));
        Path source = projectRoot.resolve("src/main/java");
        Files.createDirectories(source);
        Path buildGradle = projectRoot.resolve("build.gradle");
        Files.writeString(buildGradle, "plugins { id 'java' }\n");
        Path logFile = discoveryDir.resolve("watcher.log");

        ScopeConfig scope = ScopeConfig.of(List.of(classpath), List.of(source),
                List.of(), List.of());
        Discovery discovery = new Discovery(discoveryDir, "test-version");
        WatcherConfig watcherCfg = new WatcherConfig(true, /*debounceMs=*/ 300,
                /*restartDrainMs=*/ 500, logFile,
                WatcherConfig.DEFAULT_LOG_MAX_SIZE_BYTES,
                WatcherConfig.DEFAULT_LOG_MAX_FILES);
        LaunchArgs args = new LaunchArgs(scope, /*idleMinutes=*/ 0,
                /*foreground=*/ true, watcherCfg);

        daemon = Daemon.startForScope(args, discovery, /*force=*/ false);
        int portBefore = daemon.tcpPort();
        assertTrue(portBefore > 0);
        Path discoveryFile = discovery.filePath(scope.projectHash());
        assertTrue(Files.exists(discoveryFile));
        Discovery.Record recBefore = Discovery.read(discoveryFile).orElseThrow();
        assertEquals(portBefore, recBefore.port());

        // Trigger the self-restart by modifying build.gradle.
        Files.writeString(buildGradle, "plugins { id 'java' }\n// touched\n");

        // Wait for phase: "starting" then phase: "completed".
        JsonObject starting = awaitLogLine(logFile, selfRestartPhase("starting"),
                Duration.ofSeconds(15));
        assertNotNull(starting);
        assertEquals("build-config", starting.get("trigger.watcher").getAsString());

        JsonObject completed = awaitLogLine(logFile, selfRestartPhase("completed"),
                Duration.ofSeconds(20));
        assertNotNull(completed);
        assertTrue(completed.has("elapsed_ms"),
                "completed phase log must include elapsed_ms");

        // New TCP port is bound; discovery file updated.
        int portAfter = daemon.tcpPort();
        assertNotEquals(portBefore, portAfter,
                "graceful self-restart must bind a fresh OS-assigned ephemeral port");
        assertTrue(Files.exists(discoveryFile),
                "discovery file must exist after a successful self-restart");
        Discovery.Record recAfter = Discovery.read(discoveryFile).orElseThrow();
        assertEquals(portAfter, recAfter.port(),
                "discovery file must reflect the new port");

        // Post-restart MCP-equivalent tool call via TCP (Operations is the same façade).
        JsonObject ping = sendOp(portAfter, "ping", null);
        assertTrue(ping.get("ok").getAsBoolean());
        JsonObject watcherStatusReq = sendOp(portAfter, "watcher-status", null);
        assertTrue(watcherStatusReq.get("ok").getAsBoolean(),
                "post-restart watcher-status must be served on the new port");
        JsonObject result = watcherStatusReq.getAsJsonObject("result");
        assertTrue(result.get("enabled").getAsBoolean(),
                "watcher should remain enabled after a self-restart");
        JsonObject lr = result.getAsJsonObject("last_rebuild");
        assertEquals("build-config", lr.get("trigger_watcher").getAsString(),
                "last_rebuild must record the self-restart trigger");
        assertEquals("self-restart", lr.get("outcome").getAsString());

        // Restart did not flip restartInProgress permanently.
        assertFalse(daemon.restartInProgress(),
                "restartInProgress must be cleared at the end of the cycle");
    }

    private static JsonObject sendOp(int port, String op, JsonObject args) throws IOException {
        JsonObject req = new JsonObject();
        req.addProperty("op", op);
        if (args != null) req.add("args", args);
        try (Socket sock = new Socket(InetAddress.getLoopbackAddress(), port);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                     sock.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     sock.getInputStream(), StandardCharsets.UTF_8))) {
            sock.setSoTimeout(10_000);
            out.write(GSON.toJson(req));
            out.write('\n');
            out.flush();
            String line = in.readLine();
            assertNotNull(line, "daemon must respond on port " + port);
            JsonElement el = JsonParser.parseString(line);
            return el.getAsJsonObject();
        }
    }
}

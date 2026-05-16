package com.hhg.callgraph.daemon;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.ProcessBuilder.Redirect;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end spawn test: fork a real child JVM that boots {@code daemon start
 * --foreground} against the benchmark fixture, then drive it over TCP exactly
 * like a real client (ping, find-callers, shutdown) and verify cleanup.
 *
 * <p>Complements {@link com.hhg.callgraph.daemon.tcp.TcpServerIntegrationTest},
 * which exercises the daemon in-process. This test specifically validates the
 * spawn path — process boot, discovery file write, ping/op/shutdown round-trip,
 * graceful exit, and discovery + lock file cleanup — that the in-process test
 * cannot cover.
 *
 * <p>Environment override: the child JVM is given {@code CALLGRAPH_DISCOVERY_DIR}
 * pointing at the JUnit {@link TempDir} so it writes discovery / lock files into
 * a sandbox rather than the user's real cache directory.
 *
 * <p>Skips (via {@link Assumptions#abort}, never {@code fail}) when the spawn
 * preconditions cannot be met locally — e.g. {@code java.home} doesn't resolve a
 * runnable {@code java} binary, or the benchmark fixture classes haven't been
 * compiled yet. Diverging from {@code TcpServerIntegrationTest} (which fails
 * loudly) is intentional: a spawn-test environment can legitimately differ from
 * the in-process JVM, and a noisy fail there would block unrelated CI jobs.
 */
@DisplayName("Daemon — child-process spawn over TCP against benchmark fixture")
class DaemonProcessSpawnE2ETest {

    private static final Gson GSON = new Gson();
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path tempDir;

    /** Reference held so {@link #cleanup()} can force-kill if anything goes sideways. */
    private Process child;

    @AfterEach
    void cleanup() {
        // Belt-and-braces: if the test failed before sending shutdown, make sure
        // we don't leak a child JVM into the rest of the suite.
        try {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // Cleanup must never throw — only log.
            System.err.println("DaemonProcessSpawnE2ETest cleanup failed: " + t);
        }
    }

    @Test
    @DisplayName("forked daemon writes discovery, serves ops, shuts down, cleans up")
    void happyPath() throws Exception {
        // --- 1. Preconditions / skip-with-message -----------------------------
        Path javaBin = Path.of(System.getProperty("java.home"), "bin",
                IS_WINDOWS ? "java.exe" : "java");
        Path benchmarkDir = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/benchmark");

        if (!Files.isExecutable(javaBin)) {
            Assumptions.abort("skipping spawn test: java binary not executable at "
                    + javaBin + " (java.home="
                    + System.getProperty("java.home") + ")");
        }
        if (!Files.isDirectory(benchmarkDir) || !Files.isReadable(benchmarkDir)) {
            Assumptions.abort("skipping spawn test: benchmark fixture classes missing "
                    + "or unreadable at " + benchmarkDir
                    + " — run `./gradlew compileTestJava` first");
        }

        // --- 2. Pre-compute the expected project hash -------------------------
        // Same scope shape TcpServerIntegrationTest uses: a single classpath
        // entry pointing at the benchmark fixture, no source roots / globs.
        String expectedHash = ScopeConfig.of(
                List.of(benchmarkDir), List.of(), List.of(), List.of()
        ).projectHash();

        Path discoveryFile = tempDir.resolve(expectedHash + ".json");
        Path lockFile      = tempDir.resolve(expectedHash + ".lock");
        Path stderrLog     = tempDir.resolve("child-stderr.log");

        // --- 3. Build the spawn command ---------------------------------------
        // Identical to what `CallGraphBuilder daemon start --foreground` would
        // exec from the host CLI, minus the hidden --internal-spawned marker
        // (which is reserved for the parent → child re-exec path inside
        // Daemonization.spawnBackgroundAndAwait).
        ProcessBuilder pb = new ProcessBuilder(
                javaBin.toString(),
                "-cp", System.getProperty("java.class.path"),
                "com.hhg.callgraph.CallGraphBuilder",
                "daemon", "start",
                "--foreground",
                "--classpath", benchmarkDir.toString()
        );
        pb.redirectOutput(Redirect.DISCARD);
        pb.redirectError(Redirect.to(stderrLog.toFile()));
        pb.environment().put("CALLGRAPH_DISCOVERY_DIR", tempDir.toString());

        // --- 4. Spawn and detach stdin ----------------------------------------
        child = pb.start();
        // Close stdin so the child doesn't sit waiting on inherited stdin — same
        // affordance Daemonization.spawnBackgroundAndAwait uses (line 57).
        child.getOutputStream().close();

        // --- 5. Poll for the discovery file -----------------------------------
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (!child.isAlive()) {
                fail("child daemon exited before writing discovery file (exit="
                        + child.exitValue() + "); stderr:\n" + tailLog(stderrLog));
            }
            if (Files.exists(discoveryFile)) break;
            Thread.sleep(100);
        }
        if (!Files.exists(discoveryFile)) {
            fail("timed out after 30s waiting for discovery file at " + discoveryFile
                    + "; child alive=" + child.isAlive()
                    + "; stderr:\n" + tailLog(stderrLog));
        }

        // --- 6. Parse + assert discovery contents -----------------------------
        Discovery.Record rec = Discovery.read(discoveryFile).orElseThrow(() ->
                new AssertionError("discovery file existed but could not be parsed: "
                        + discoveryFile + "; stderr:\n" + tailLog(stderrLog)));

        assertEquals(expectedHash, rec.projectHash(),
                "discovery file project_hash must match the scope hash");
        assertEquals(child.pid(), rec.pid(),
                "discovery file pid must match the spawned child JVM");
        assertTrue(rec.port() > 0 && rec.port() < 65536,
                "discovery file port should be a valid TCP port; was " + rec.port());

        long now = System.currentTimeMillis();
        long started = rec.startedAtEpochMillis();
        // Sane window: started recently and not in the future. 60s lower bound
        // is generous for slow CI; 5s upper bound covers minor clock skew.
        assertTrue(started > now - 60_000 && started < now + 5_000,
                "started_at_epoch_millis clock-skew check failed: started="
                        + started + " now=" + now);

        // --- 6b. UC 03 — discovery file's daemon_version must equal project.version ---
        // Both Daemon.DAEMON_VERSION and McpStdioServer.SERVER_VERSION resolve through
        // BuildVersion.value(), so seeing project.version round-trip through the
        // discovery file proves the build-time embedding survived classpath +
        // child-JVM boot. build.gradle wires project.version into the test JVM via
        // `test { systemProperty 'project.version', project.version }`, but it is
        // NOT propagated into the spawned child; the child reads its own embedded
        // resource. We compare the parent's view of project.version against the
        // child's reported daemon_version — they must match.
        String projectVersion = System.getProperty("project.version");
        Assumptions.assumeTrue(projectVersion != null && !projectVersion.isBlank(),
                "skipping daemon_version assertion: 'project.version' system "
                        + "property unset (only present under ./gradlew test)");
        assertEquals(projectVersion, rec.daemonVersion(),
                "discovery file daemon_version must match build.gradle project.version "
                        + "(UC 03 — build-time version embedding). If this fails, the "
                        + "spawned daemon either picked up a stale resource or fell back "
                        + "to a hardcoded literal.");

        // --- 7. TCP ping ------------------------------------------------------
        // Top-level {"op":"ping"} — missing 'args' is fine; JsonProtocol.parseRequest
        // defaults to an empty JsonObject.
        JsonObject pingReq = buildOpRequest("ping", null);
        JsonObject pingEnv = sendAndReceive(rec.port(), pingReq);
        assertTrue(pingEnv.get("ok").getAsBoolean(),
                "ping envelope must be ok: " + pingEnv);
        JsonObject pingResult = pingEnv.getAsJsonObject("result");
        assertEquals(expectedHash, pingResult.get("project_hash").getAsString(),
                "ping.result.project_hash must echo the scope hash");

        // --- 8. TCP find-callers ----------------------------------------------
        // Smoke-test only — full per-op coverage lives in TcpServerIntegrationTest.
        // The point here is "the forked daemon serves real ops", not exhaustive
        // semantics.
        JsonObject findArgs = new JsonObject();
        findArgs.addProperty("method_fqn",
                "com.hhg.benchmark.service.UserService#findAll()Ljava/util/List;");
        findArgs.addProperty("depth", 1);
        JsonObject findEnv = sendAndReceive(rec.port(),
                buildOpRequest("find-callers", findArgs));
        assertTrue(findEnv.get("ok").getAsBoolean(),
                "find-callers envelope must be ok: " + findEnv);
        assertTrue(findEnv.getAsJsonObject("result").has("tree"),
                "find-callers result must contain a 'tree' field");

        // --- 8b. UC04 — writes outside watched roots must NOT trigger rebuilds ------
        // The spawned daemon's watcher is registered against the benchmark classes dir
        // and (since this test passes no --src) no source roots. Touching files inside
        // the TempDir but OUTSIDE the registered classpath must not move the watcher's
        // last_rebuild fingerprint. We snapshot watcher-status, write an unrelated file,
        // wait one debounce window, then verify last_rebuild.timestamp is unchanged.
        JsonObject statusBefore = sendAndReceive(rec.port(),
                buildOpRequest("watcher-status", null));
        assertTrue(statusBefore.get("ok").getAsBoolean(),
                "watcher-status must be served: " + statusBefore);
        JsonObject lrBefore = statusBefore.getAsJsonObject("result")
                .getAsJsonObject("last_rebuild");
        // If the watcher is on, last_rebuild is the initial scan (trigger=manual).
        // If --no-watch was passed (not here) last_rebuild may also be populated.
        // Touch a file in tempDir that the watcher cannot possibly be watching.
        Path stray = tempDir.resolve("not-watched.txt");
        Files.writeString(stray, "noise\n");
        // Wait through one full default debounce window (1000ms) + safety margin.
        Thread.sleep(1500);
        JsonObject statusAfter = sendAndReceive(rec.port(),
                buildOpRequest("watcher-status", null));
        JsonObject lrAfter = statusAfter.getAsJsonObject("result")
                .getAsJsonObject("last_rebuild");
        assertEquals(lrBefore == null ? null : lrBefore.get("timestamp").getAsString(),
                lrAfter == null ? null : lrAfter.get("timestamp").getAsString(),
                "writes outside watched roots must NOT advance last_rebuild.timestamp");

        // --- 9. TCP shutdown --------------------------------------------------
        JsonObject shutdownEnv = sendAndReceive(rec.port(),
                buildOpRequest("shutdown", null));
        assertTrue(shutdownEnv.get("ok").getAsBoolean(),
                "shutdown envelope must be ok: " + shutdownEnv);
        assertEquals("shutting-down",
                shutdownEnv.getAsJsonObject("result").get("status").getAsString(),
                "shutdown.result.status must be 'shutting-down' (Daemon.shutdown lines 219-227)");

        // --- 10. Wait for child exit + verify cleanup -------------------------
        boolean exited = child.waitFor(10, TimeUnit.SECONDS);
        assertTrue(exited,
                "child daemon must exit within 10s of shutdown ack; stderr:\n"
                        + tailLog(stderrLog));
        assertEquals(0, child.exitValue(),
                "child daemon must exit cleanly (exit code 0); stderr:\n"
                        + tailLog(stderrLog));

        assertTrue(Files.notExists(discoveryFile),
                "discovery file must be removed on graceful shutdown: " + discoveryFile);
        assertTrue(Files.notExists(lockFile),
                "lock file must be removed on graceful shutdown: " + lockFile);
    }

    // -------------------------------------------------------------------------
    // helpers — mirror TcpServerIntegrationTest's pattern so the
    // {"op": ..., "args": {...}} envelope is built in exactly one place.
    // -------------------------------------------------------------------------

    /**
     * Builds a request envelope with the top-level {@code op} and optional nested
     * {@code args}. Pass {@code null} for ops like ping/shutdown that take no args
     * — the wire shape stays the same as {@code DaemonCli.runFindTree} produces.
     */
    private static JsonObject buildOpRequest(String op, JsonObject args) {
        JsonObject req = new JsonObject();
        req.addProperty("op", op);
        if (args != null) {
            req.add("args", args);
        }
        return req;
    }

    /**
     * Opens one TCP connection to the loopback port, writes {@code request} as a
     * newline-terminated UTF-8 JSON line, reads one response line back, parses
     * it, and returns the envelope. 10s socket timeout per op.
     */
    private static JsonObject sendAndReceive(int port, JsonObject request) throws IOException {
        try (Socket sock = new Socket(InetAddress.getLoopbackAddress(), port);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                     sock.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     sock.getInputStream(), StandardCharsets.UTF_8))) {
            sock.setSoTimeout(10_000);
            out.write(GSON.toJson(request));
            out.write('\n');
            out.flush();
            String line = in.readLine();
            assertNotNull(line, "daemon must respond with a JSON line on port " + port);
            JsonElement el = JsonParser.parseString(line);
            assertFalse(el.isJsonNull(), "daemon response must be a JSON object, got null");
            return el.getAsJsonObject();
        }
    }

    /** Best-effort tail of the child stderr log for failure messages. */
    private static String tailLog(Path log) {
        try {
            if (Files.exists(log)) return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
        return "(no stderr log captured at " + log + ")";
    }
}

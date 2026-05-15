package com.hhg.callgraph.daemon.tcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hhg.callgraph.daemon.IndexSnapshot;
import com.hhg.callgraph.daemon.ScanMeta;
import com.hhg.callgraph.daemon.ScopeConfig;
import com.hhg.callgraph.daemon.op.OperationResult;
import com.hhg.callgraph.daemon.op.Operations;
import com.hhg.callgraph.scanner.ClassFileScanner;
import com.hhg.callgraph.scanner.ScanResult;
import com.hhg.callgraph.scanner.test.JUnit5TestDetector;
import com.hhg.callgraph.scanner.test.SpockTestDetector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end TCP path test. Boots a real {@link TcpServer} wired to {@link Operations}
 * against the benchmark fixture, exercises each op over a loopback socket, and verifies:
 *
 * <ul>
 *   <li>AC #2 — server binds to {@code 127.0.0.1} on an OS-assigned ephemeral port.</li>
 *   <li>AC #8 — JSON request/response over TCP; success and error envelopes.</li>
 *   <li>AC #19 — malformed input returns a structured error and the daemon stays alive
 *       (subsequent requests succeed on the same server).</li>
 *   <li>AC #20 — JUnit 5 covers each of the 9 operations against the benchmark fixture
 *       on the TCP-client code path.</li>
 * </ul>
 */
@DisplayName("TcpServer — loopback JSON round-trip against benchmark fixture")
class TcpServerIntegrationTest {

    private static TcpServer server;
    private static int port;
    private static final TestTcpOps TCP_OPS = new TestTcpOps();
    private static final Gson GSON = new Gson();

    @BeforeAll
    static void boot() throws IOException {
        Path benchmarkDir = Path.of(System.getProperty("user.dir"))
                .resolve("build/classes/java/test/com/hhg/benchmark");
        if (!Files.exists(benchmarkDir)) {
            fail("benchmark classes missing; run `./gradlew compileTestJava` first");
        }
        ScanResult scan = new ClassFileScanner(List.of(
                new JUnit5TestDetector(),
                new SpockTestDetector()
        )).scan(benchmarkDir);

        ScopeConfig scope = ScopeConfig.of(List.of(benchmarkDir), List.of(),
                List.of(), List.of());
        ScanMeta meta = new ScanMeta(scan.callGraph().methodCount(),
                scan.callGraph().edgeCount(), 1L, System.currentTimeMillis());
        IndexSnapshot snap = new IndexSnapshot(scan, scope, meta);
        AtomicReference<IndexSnapshot> ref = new AtomicReference<>(snap);
        Operations operations = new Operations(ref::get, () -> {
            JsonObject r = new JsonObject();
            r.addProperty("status", "ok");
            r.addProperty("methods", snap.meta().methodCount());
            r.addProperty("edges",   snap.meta().edgeCount());
            r.addProperty("scan_ms", snap.meta().scanMillis());
            return r;
        });

        server = new TcpServer(operations, TCP_OPS, null);
        server.start();
        port = server.port();
    }

    @AfterAll
    static void shutdown() {
        if (server != null) server.shutdown(3);
    }

    /** Test stand-in for {@link com.hhg.callgraph.daemon.Daemon}'s TcpOps. */
    private static final class TestTcpOps implements ConnectionHandler.TcpOps {
        volatile int pingCount = 0;
        volatile int shutdownCount = 0;

        @Override
        public JsonObject ping() {
            pingCount++;
            JsonObject obj = new JsonObject();
            obj.addProperty("project_hash", "test1234abcd0000");
            obj.addProperty("pid", ProcessHandle.current().pid());
            obj.addProperty("port", port);
            obj.addProperty("daemon_version", "test");
            return obj;
        }

        @Override
        public OperationResult shutdown() {
            shutdownCount++;
            JsonObject ok = new JsonObject();
            ok.addProperty("status", "shutting-down");
            return OperationResult.ok(ok);
        }
    }

    // -------------------------------------------------------------------
    // AC #2 — loopback bind
    // -------------------------------------------------------------------

    @Test
    @DisplayName("AC #2 — server binds to 127.0.0.1 on an OS-assigned ephemeral port")
    void loopbackBind() {
        assertTrue(port > 0 && port < 65536, "ephemeral port should be in user range; was " + port);
        InetAddress bound = server.boundAddress();
        assertTrue(bound.isLoopbackAddress(),
                "server must bind to a loopback address (AC #2); got " + bound);
    }

    // -------------------------------------------------------------------
    // AC #8 — round-trip + ping
    // -------------------------------------------------------------------

    @Test
    @DisplayName("AC #8 — ping round-trips a Success envelope")
    void pingRoundTrip() throws IOException {
        JsonObject req = new JsonObject();
        req.addProperty("op", "ping");
        JsonObject env = sendAndReceive(req);
        assertTrue(env.get("ok").getAsBoolean(),
                "ping envelope: " + env);
        assertEquals("test1234abcd0000",
                env.getAsJsonObject("result").get("project_hash").getAsString());
    }

    @Test
    @DisplayName("AC #8 — request_id is echoed back on the envelope")
    void requestIdEchoed() throws IOException {
        JsonObject req = new JsonObject();
        req.addProperty("op", "ping");
        req.addProperty("request_id", "abc-123");
        JsonObject env = sendAndReceive(req);
        assertEquals("abc-123", env.get("request_id").getAsString());
    }

    // -------------------------------------------------------------------
    // AC #20 — JUnit 5 covers each of 9 ops over the TCP-client path
    // -------------------------------------------------------------------

    @Test
    @DisplayName("AC #20 — refresh-index over TCP")
    void refreshIndexOverTcp() throws IOException {
        JsonObject r = okResult(sendOp("refresh-index", new JsonObject()));
        assertEquals("ok", r.get("status").getAsString());
        assertTrue(r.has("methods"));
        assertTrue(r.has("edges"));
        assertTrue(r.has("scan_ms"));
    }

    @Test
    @DisplayName("AC #20 — find-callers over TCP")
    void findCallersOverTcp() throws IOException {
        // UserController#findAll calls userService.findAll (which exists in the benchmark)
        JsonObject args = new JsonObject();
        args.addProperty("method_fqn",
                "com.hhg.benchmark.service.UserService#findAll()Ljava/util/List;");
        args.addProperty("depth", 1);
        JsonObject r = okResult(sendOp("find-callers", args));
        assertTrue(r.has("tree"));
    }

    @Test
    @DisplayName("AC #20 — find-callees over TCP")
    void findCalleesOverTcp() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("method_fqn",
                "com.hhg.benchmark.service.UserService#findAll()Ljava/util/List;");
        args.addProperty("depth", 0);
        JsonObject r = okResult(sendOp("find-callees", args));
        JsonObject tree = r.getAsJsonObject("tree");
        // depth=0 → truncated at the root
        assertTrue(tree.get("truncated").getAsBoolean());
        assertEquals("depth", tree.get("reason").getAsString());
    }

    @Test
    @DisplayName("AC #20 — methods-in-class over TCP")
    void methodsInClassOverTcp() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("class_fqn", "com.hhg.benchmark.service.UserService");
        JsonObject r = okResult(sendOp("methods-in-class", args));
        assertTrue(r.getAsJsonArray("methods").size() > 0);
    }

    @Test
    @DisplayName("AC #20 — methods-at-line over TCP")
    void methodsAtLineOverTcp() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("source_file", "UserService.java");
        args.addProperty("line", 1);
        JsonObject env = sendOp("methods-at-line", args);
        // May return empty list if line 1 has no methods; we just need success envelope
        assertTrue(env.get("ok").getAsBoolean(), "envelope: " + env);
    }

    @Test
    @DisplayName("AC #20 — find-field-readers + find-field-writers over TCP")
    void fieldAccessOverTcp() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("field_fqn",
                "com.hhg.benchmark.entity.User#username:Ljava/lang/String;");
        JsonObject readers = okResult(sendOp("find-field-readers", args));
        JsonObject writers = okResult(sendOp("find-field-writers", args));
        assertTrue(readers.getAsJsonArray("methods").size() > 0);
        assertTrue(writers.getAsJsonArray("methods").size() > 0);
    }

    @Test
    @DisplayName("AC #20 — impact-of-diff + tests-for-diff over TCP")
    void diffOpsOverTcp() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("diff_text", "");
        JsonObject impact = okResult(sendOp("impact-of-diff", args));
        assertEquals(0, impact.getAsJsonArray("impactedTrees").size());
        JsonObject tests = okResult(sendOp("tests-for-diff", args));
        assertEquals(0, tests.getAsJsonArray("tests").size());
    }

    // -------------------------------------------------------------------
    // AC #19 — daemon stays alive on malformed input
    // -------------------------------------------------------------------

    @Test
    @DisplayName("AC #19 — malformed JSON returns bad-request + daemon stays alive")
    void daemonAliveAfterMalformed() throws IOException {
        JsonObject env = sendRaw("{not valid json}\n");
        assertNotNull(env);
        assertFalse(env.get("ok").getAsBoolean());
        assertEquals("bad-request", env.get("error").getAsString());

        // A subsequent valid request on a new connection should still succeed.
        JsonObject req = new JsonObject();
        req.addProperty("op", "ping");
        JsonObject ok = sendAndReceive(req);
        assertTrue(ok.get("ok").getAsBoolean(),
                "daemon must survive a malformed previous request (AC #19); env=" + ok);
    }

    @Test
    @DisplayName("AC #19 — unknown op returns unknown-op + daemon stays alive")
    void daemonAliveAfterUnknownOp() throws IOException {
        JsonObject req = new JsonObject();
        req.addProperty("op", "frobnicate");
        JsonObject env = sendAndReceive(req);
        assertFalse(env.get("ok").getAsBoolean());
        assertEquals("unknown-op", env.get("error").getAsString());

        // Daemon still answers a follow-up ping.
        JsonObject ping = new JsonObject();
        ping.addProperty("op", "ping");
        assertTrue(sendAndReceive(ping).get("ok").getAsBoolean());
    }

    @Test
    @DisplayName("AC #19 — missing op field returns bad-request")
    void missingOp() throws IOException {
        JsonObject env = sendAndReceive(new JsonObject());
        assertFalse(env.get("ok").getAsBoolean());
        assertEquals("bad-request", env.get("error").getAsString());
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private static JsonObject sendOp(String op, JsonObject args) throws IOException {
        JsonObject req = new JsonObject();
        req.addProperty("op", op);
        req.add("args", args);
        return sendAndReceive(req);
    }

    private static JsonObject okResult(JsonObject env) {
        assertTrue(env.get("ok").getAsBoolean(),
                "expected Success envelope, got: " + env);
        return env.getAsJsonObject("result");
    }

    private static JsonObject sendAndReceive(JsonObject req) throws IOException {
        return sendRaw(GSON.toJson(req) + "\n");
    }

    private static JsonObject sendRaw(String line) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            s.setSoTimeout(10_000);
            out.write(line);
            out.flush();
            String resp = in.readLine();
            assertNotNull(resp, "no response line from daemon");
            JsonElement el = JsonParser.parseString(resp);
            return el.getAsJsonObject();
        }
    }
}

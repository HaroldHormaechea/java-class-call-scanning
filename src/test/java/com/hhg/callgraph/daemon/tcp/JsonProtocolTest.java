package com.hhg.callgraph.daemon.tcp;

import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.op.OperationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the wire envelope contract (AC #8 — JSON request/response over loopback) and
 * the AC #19 promise that malformed input becomes a structured error payload — the
 * codec itself surfaces {@code bad-request} via the typed exception {@link
 * JsonProtocol.BadRequestException}, which {@link ConnectionHandler} turns into a
 * {@code bad-request} envelope.
 */
@DisplayName("JsonProtocol — request/response codec (AC #8, #19)")
class JsonProtocolTest {

    @Test
    @DisplayName("parseRequest extracts op + args + request_id")
    void parseHappyPath() {
        JsonProtocol.Request req = JsonProtocol.parseRequest(
                "{\"op\":\"find-callers\",\"args\":{\"method_fqn\":\"x.Y#z()V\"}," +
                        "\"request_id\":\"r-1\"}");
        assertEquals("find-callers", req.op());
        assertEquals("x.Y#z()V", req.args().get("method_fqn").getAsString());
        assertEquals("r-1", req.requestId());
    }

    @Test
    @DisplayName("missing args defaults to empty object — no NPE")
    void missingArgsDefaultsEmpty() {
        JsonProtocol.Request req = JsonProtocol.parseRequest("{\"op\":\"refresh-index\"}");
        assertEquals("refresh-index", req.op());
        assertEquals(0, req.args().size());
        assertNull(req.requestId());
    }

    @Test
    @DisplayName("malformed JSON throws BadRequestException")
    void malformedJsonRejected() {
        assertThrows(JsonProtocol.BadRequestException.class,
                () -> JsonProtocol.parseRequest("{op:\"x\""));
    }

    @Test
    @DisplayName("non-object root throws BadRequestException")
    void nonObjectRoot() {
        assertThrows(JsonProtocol.BadRequestException.class,
                () -> JsonProtocol.parseRequest("[1,2,3]"));
    }

    @Test
    @DisplayName("missing op throws BadRequestException")
    void missingOp() {
        assertThrows(JsonProtocol.BadRequestException.class,
                () -> JsonProtocol.parseRequest("{}"));
    }

    @Test
    @DisplayName("blank op throws BadRequestException")
    void blankOp() {
        assertThrows(JsonProtocol.BadRequestException.class,
                () -> JsonProtocol.parseRequest("{\"op\":\"\"}"));
    }

    @Test
    @DisplayName("encodeSuccess produces {ok:true, result:..., request_id:...}")
    void encodeSuccess() {
        JsonObject body = new JsonObject();
        body.addProperty("x", 1);
        JsonObject env = JsonProtocol.encodeSuccess(
                new OperationResult.Success(body), "req-9");
        assertTrue(env.get("ok").getAsBoolean());
        assertEquals(1, env.getAsJsonObject("result").get("x").getAsInt());
        assertEquals("req-9", env.get("request_id").getAsString());
    }

    @Test
    @DisplayName("encodeFailure produces {ok:false, error, detail, request_id}")
    void encodeFailure() {
        JsonObject env = JsonProtocol.encodeFailure(
                new OperationResult.Failure("bad-request", "no op"), null);
        assertFalse(env.get("ok").getAsBoolean());
        assertEquals("bad-request", env.get("error").getAsString());
        assertEquals("no op", env.get("detail").getAsString());
        assertTrue(env.get("request_id").isJsonNull(),
                "null request_id should become JsonNull, not be omitted");
    }
}

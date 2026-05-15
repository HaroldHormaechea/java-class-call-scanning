package com.hhg.callgraph.daemon.tcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.hhg.callgraph.daemon.op.OperationResult;

/**
 * Line-delimited JSON request / response codec for the TCP loopback surface.
 * One round-trip per connection. The codec is pure (no I/O) — callers handle
 * line reading and writing.
 */
public final class JsonProtocol {

    private JsonProtocol() {}

    /** Parsed request envelope. */
    public record Request(String op, JsonObject args, String requestId) {}

    /** Parses a request line. Throws on malformed JSON. */
    public static Request parseRequest(String line) {
        JsonElement el;
        try {
            el = JsonParser.parseString(line);
        } catch (JsonSyntaxException e) {
            throw new BadRequestException("malformed JSON: " + e.getMessage());
        }
        if (!el.isJsonObject()) {
            throw new BadRequestException("request must be a JSON object");
        }
        JsonObject obj = el.getAsJsonObject();
        String op = stringFieldOrNull(obj, "op");
        if (op == null || op.isBlank()) {
            throw new BadRequestException("missing 'op' field");
        }
        JsonObject args = (obj.has("args") && obj.get("args").isJsonObject())
                ? obj.getAsJsonObject("args")
                : new JsonObject();
        String requestId = stringFieldOrNull(obj, "request_id");
        return new Request(op, args, requestId);
    }

    /** Encodes a successful result envelope. */
    public static JsonObject encodeSuccess(OperationResult.Success success, String requestId) {
        JsonObject env = new JsonObject();
        env.addProperty("ok", true);
        env.add("result", success.result() == null ? new JsonObject() : success.result());
        env.add("request_id", requestId == null ? JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(requestId));
        return env;
    }

    /** Encodes an error envelope. */
    public static JsonObject encodeFailure(OperationResult.Failure failure, String requestId) {
        JsonObject env = new JsonObject();
        env.addProperty("ok", false);
        env.addProperty("error", failure.errorCode());
        env.addProperty("detail", failure.detail());
        env.add("request_id", requestId == null ? JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(requestId));
        return env;
    }

    private static String stringFieldOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) return null;
        return obj.get(key).getAsString();
    }

    /** Thrown for malformed protocol envelopes (handled into a {@code bad-request} reply). */
    public static final class BadRequestException extends RuntimeException {
        public BadRequestException(String msg) { super(msg); }
    }
}

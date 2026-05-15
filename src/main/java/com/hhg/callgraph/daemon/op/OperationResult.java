package com.hhg.callgraph.daemon.op;

import com.google.gson.JsonObject;

/**
 * Discriminated result of an operation invocation. Wire encoders translate
 * {@link Success} → {@code {"ok": true, "result": ...}} and {@link Failure} →
 * {@code {"ok": false, "error": "...", "detail": "..."}}.
 */
public sealed interface OperationResult permits OperationResult.Success, OperationResult.Failure {

    static Success ok(JsonObject result)                   { return new Success(result); }
    static Failure error(String code, String detail)       { return new Failure(code, detail); }

    record Success(JsonObject result) implements OperationResult {}
    record Failure(String errorCode, String detail) implements OperationResult {}
}

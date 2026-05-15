package com.hhg.callgraph.daemon.tcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hhg.callgraph.daemon.IdleWatchdog;
import com.hhg.callgraph.daemon.op.OperationResult;
import com.hhg.callgraph.daemon.op.Operations;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Handles one TCP connection: read one line, dispatch, write one line, close.
 * Parse errors → {@code bad-request}; uncaught exceptions → {@code internal-error}
 * with the exception message in {@code detail}. The daemon stays alive regardless.
 */
public final class ConnectionHandler implements Runnable {

    /** Special ops handled by the TCP server itself (not by {@link Operations}). */
    public interface TcpOps {
        JsonObject ping();
        OperationResult shutdown();
    }

    private static final Gson GSON = new Gson();

    private final Socket socket;
    private final Operations operations;
    private final TcpOps tcpOps;
    private final IdleWatchdog watchdog;     // nullable
    private final Consumer<Throwable> errorSink;

    public ConnectionHandler(Socket socket,
                             Operations operations,
                             TcpOps tcpOps,
                             IdleWatchdog watchdog,
                             Consumer<Throwable> errorSink) {
        this.socket = socket;
        this.operations = operations;
        this.tcpOps = tcpOps;
        this.watchdog = watchdog;
        this.errorSink = errorSink == null ? t -> {} : errorSink;
    }

    @Override
    public void run() {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            if (watchdog != null) watchdog.bump();

            String line = in.readLine();
            JsonObject envelope = handle(line);

            out.write(GSON.toJson(envelope));
            out.write('\n');
            out.flush();
        } catch (Throwable t) {
            errorSink.accept(t);
        }
    }

    private JsonObject handle(String line) {
        if (line == null) {
            return JsonProtocol.encodeFailure(
                    new OperationResult.Failure("bad-request", "empty request"), null);
        }
        JsonProtocol.Request req;
        try {
            req = JsonProtocol.parseRequest(line);
        } catch (JsonProtocol.BadRequestException e) {
            return JsonProtocol.encodeFailure(
                    new OperationResult.Failure("bad-request", e.getMessage()), null);
        }

        try {
            OperationResult result = switch (req.op()) {
                case "ping"     -> OperationResult.ok(tcpOps.ping());
                case "shutdown" -> tcpOps.shutdown();
                default         -> operations.dispatch(req.op(), req.args());
            };
            return encode(result, req.requestId());
        } catch (Throwable t) {
            return JsonProtocol.encodeFailure(
                    new OperationResult.Failure("internal-error",
                            t.getClass().getSimpleName() + ": " + t.getMessage()),
                    req.requestId());
        }
    }

    private static JsonObject encode(OperationResult r, String requestId) {
        if (r instanceof OperationResult.Success s) {
            return JsonProtocol.encodeSuccess(s, requestId);
        }
        return JsonProtocol.encodeFailure((OperationResult.Failure) r, requestId);
    }
}

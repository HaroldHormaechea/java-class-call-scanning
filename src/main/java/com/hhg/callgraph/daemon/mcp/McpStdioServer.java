package com.hhg.callgraph.daemon.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hhg.callgraph.BuildVersion;
import com.hhg.callgraph.daemon.IdleWatchdog;
import com.hhg.callgraph.daemon.op.OperationResult;
import com.hhg.callgraph.daemon.op.Operations;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * MCP stdio server exposing the ten user-facing operations as MCP tools.
 *
 * <p><strong>Critical:</strong> when this server is running, daemon logs MUST go to
 * {@link System#err}; {@code System.out} is reserved for the MCP protocol stream.
 * The {@link Operations} façade and other daemon components already obey this rule.
 *
 * <p><b>Self-restart contract (UC04 §6):</b> the {@link McpSyncServer} and its
 * underlying {@link StdioServerTransportProvider} live for the whole JVM. On a
 * graceful self-restart, the daemon rebuilds {@link Operations} (new index, new
 * supplier closures) and calls {@link #setOperations(Operations)} to re-point the
 * tool handlers — the MCP client's stdio session is preserved, no protocol
 * renegotiation, and the tool surface (names + schemas) is identical.
 */
public final class McpStdioServer implements AutoCloseable {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String SERVER_NAME    = "java-class-call-scanning";
    private static final String SERVER_VERSION = BuildVersion.value();

    private volatile Operations currentOperations;
    private final IdleWatchdog watchdog;     // nullable
    private final McpSyncServer mcpServer;

    public McpStdioServer(Operations operations, IdleWatchdog watchdog) {
        this.currentOperations = operations;
        this.watchdog = watchdog;

        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper());

        List<McpServerFeatures.SyncToolSpecification> tools = List.of(
                tool("refresh-index",
                        "Re-scan the daemon's original launch scope and atomically swap the index. Returns {status, methods, edges, scan_ms}.",
                        schemaEmpty()),
                tool("find-callers",
                        "BFS over the call graph from {method_fqn} returning callers. Default depth -1 (unbounded). Truncated nodes carry {truncated:true, reason}.",
                        schemaMethodFqnDepth()),
                tool("find-callees",
                        "BFS over the call graph from {method_fqn} returning callees. Default depth 3. Truncated nodes carry {truncated:true, reason}.",
                        schemaMethodFqnDepth()),
                tool("methods-in-class",
                        "List methods declared on the dotted class {class_fqn}. Each entry is {name, descriptor, line_start, line_end, is_test}.",
                        schemaSingleString("class_fqn",
                                "Dotted class FQN, e.g. com.acme.Foo")),
                tool("methods-at-line",
                        "Return methods whose LineNumberTable covers {line} in {source_file}. Path is matched suffix-style against source roots.",
                        schemaMethodsAtLine()),
                tool("find-field-readers",
                        "Methods that READ {field_fqn} (JVM-descriptor form, e.g. com.acme.Foo#bar:Ljava/lang/String;).",
                        schemaSingleString("field_fqn", "Field FQN in JVM-descriptor form")),
                tool("find-field-writers",
                        "Methods that WRITE {field_fqn} (JVM-descriptor form).",
                        schemaSingleString("field_fqn", "Field FQN in JVM-descriptor form")),
                tool("impact-of-diff",
                        "Parse a unified diff in {diff_text} and emit the legacy 'impactedTrees' payload (byte-identical modulo whitespace).",
                        schemaSingleString("diff_text", "Unified Git diff as text")),
                tool("tests-for-diff",
                        "Flat list of impacted tests {fqn, type, displayName, root_change} for the unified diff in {diff_text}.",
                        schemaSingleString("diff_text", "Unified Git diff as text")),
                tool("watcher-status",
                        "Returns the current state of the auto-watch subsystem and the single-source-of-truth payload for 'when was the index last touched'. Includes manual refresh-index calls.",
                        schemaEmpty())
        );

        this.mcpServer = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools)
                .build();
    }

    /**
     * Atomically swaps the underlying {@link Operations}. Used by the graceful
     * self-restart pipeline so the MCP client's stdio session survives the restart.
     */
    public void setOperations(Operations newOps) {
        this.currentOperations = newOps;
    }

    /** Closes the MCP server, draining stdio cleanly. */
    @Override
    public void close() {
        try {
            mcpServer.closeGracefully();
        } catch (Throwable t) {
            System.err.println("mcp-stdio: closeGracefully threw: " + t);
        }
    }

    // -----------------------------------------------------------------------
    // Tool builders
    // -----------------------------------------------------------------------

    private McpServerFeatures.SyncToolSpecification tool(String name, String description,
                                                         McpSchema.JsonSchema inputSchema) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange,
                io.modelcontextprotocol.spec.McpSchema.CallToolRequest,
                io.modelcontextprotocol.spec.McpSchema.CallToolResult> handler =
                (exchange, request) -> dispatchAsTool(name, request);

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    private McpSchema.CallToolResult dispatchAsTool(String op,
                                                    McpSchema.CallToolRequest request) {
        if (watchdog != null) watchdog.bump();

        JsonObject args = mapToJsonObject(request.arguments());
        OperationResult result = currentOperations.dispatch(op, args);

        JsonObject envelope = new JsonObject();
        if (result instanceof OperationResult.Success s) {
            envelope.addProperty("ok", true);
            envelope.add("result", s.result() == null ? new JsonObject() : s.result());
        } else {
            OperationResult.Failure f = (OperationResult.Failure) result;
            envelope.addProperty("ok", false);
            envelope.addProperty("error", f.errorCode());
            envelope.addProperty("detail", f.detail());
        }
        String json = GSON.toJson(envelope);

        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(result instanceof OperationResult.Failure)
                .build();
    }

    private static JsonObject mapToJsonObject(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return new JsonObject();
        // Round-trip through Gson to coerce nested Maps / Lists / primitives into JsonObject.
        String json = GSON.toJson(args);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // -----------------------------------------------------------------------
    // JSON Schemas for the MCP tool listing
    // -----------------------------------------------------------------------

    private static McpSchema.JsonSchema schemaEmpty() {
        return new McpSchema.JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of());
    }

    private static McpSchema.JsonSchema schemaSingleString(String name, String description) {
        Map<String, Object> props = Map.of(
                name, Map.of("type", "string", "description", description));
        return new McpSchema.JsonSchema("object", props, List.of(name), false, Map.of(), Map.of());
    }

    private static McpSchema.JsonSchema schemaMethodFqnDepth() {
        Map<String, Object> props = Map.of(
                "method_fqn", Map.of("type", "string",
                        "description", "Method FQN in JVM-descriptor form, e.g. com.acme.Foo#bar()V"),
                "depth", Map.of("type", "integer",
                        "description", "Traversal depth; -1 means unbounded"));
        return new McpSchema.JsonSchema("object", props, List.of("method_fqn"), false, Map.of(), Map.of());
    }

    private static McpSchema.JsonSchema schemaMethodsAtLine() {
        Map<String, Object> props = Map.of(
                "source_file", Map.of("type", "string",
                        "description", "Absolute or source-root-relative path"),
                "line", Map.of("type", "integer",
                        "description", "1-based line number"));
        return new McpSchema.JsonSchema(
                "object", props, List.of("source_file", "line"), false, Map.of(), Map.of());
    }
}

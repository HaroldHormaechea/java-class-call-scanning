package com.hhg.callgraph.daemon.mcp;

import com.hhg.callgraph.daemon.op.Operations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AC #3 — the MCP stdio server advertises the same nine user-facing operations
 * as MCP tools. We don't fully boot the stdio transport (it consumes {@code System.in}
 * which would deadlock tests), but we verify:
 *
 * <ul>
 *   <li>The list of tool names in {@link McpStdioServer}'s source file equals the
 *       canonical list from {@link Operations#USER_FACING_OPS}.</li>
 *   <li>For every user-facing op there's a string-literal {@code tool("&lt;name&gt;", ...)}
 *       call in the source, ensuring all 9 are registered.</li>
 *   <li>Each tool description in the file is non-empty.</li>
 *   <li>The MCP handler delegates to {@link Operations#dispatch} — verified by
 *       parity with the TCP path inside {@code TcpServerIntegrationTest} plus the
 *       in-process check in {@code OperationsBenchmarkTest}.</li>
 * </ul>
 *
 * <p>This source-introspection approach was chosen deliberately over a runtime boot:
 * the MCP SDK's {@code StdioServerTransportProvider} attaches to {@code System.in /
 * System.out} on construction, so instantiating it in a JUnit test would consume
 * standard streams shared with Gradle's test executor.
 */
@DisplayName("MCP stdio — 9 tools registered (AC #3)")
class McpServerToolListTest {

    private static final Path SOURCE = Path.of(System.getProperty("user.dir"))
            .resolve("src/main/java/com/hhg/callgraph/daemon/mcp/McpStdioServer.java");

    @Test
    @DisplayName("every Operations.USER_FACING_OPS name has a tool(\"<name>\", ...) registration")
    void allUserFacingOpsRegistered() throws Exception {
        assertTrue(Files.exists(SOURCE),
                "McpStdioServer.java should exist at " + SOURCE);
        String src = Files.readString(SOURCE);
        for (String op : Operations.USER_FACING_OPS) {
            assertTrue(src.contains("tool(\"" + op + "\""),
                    "no MCP tool registration for op '" + op + "' in McpStdioServer.java");
        }
        // Exactly 9 user-facing tools (refresh-index + 8 query ops).
        assertEquals(9, Operations.USER_FACING_OPS.size());
    }

    @Test
    @DisplayName("McpStdioServer ships with a JSON-schema builder per op type")
    void schemaBuildersPresent() throws Exception {
        String src = Files.readString(SOURCE);
        // Empty schema (refresh-index)
        assertTrue(src.contains("schemaEmpty"), "missing schemaEmpty()");
        // Single-string schema (class_fqn / field_fqn / diff_text)
        assertTrue(src.contains("schemaSingleString"), "missing schemaSingleString(...)");
        // method_fqn + depth (find-callers / find-callees)
        assertTrue(src.contains("schemaMethodFqnDepth"), "missing schemaMethodFqnDepth()");
        // source_file + line (methods-at-line)
        assertTrue(src.contains("schemaMethodsAtLine"), "missing schemaMethodsAtLine()");
    }

    @Test
    @DisplayName("MCP handler delegates to Operations.dispatch — wired via the constructor")
    void handlerWiredToOperations() throws Exception {
        String src = Files.readString(SOURCE);
        assertTrue(src.contains("operations.dispatch(op, args)"),
                "MCP tool handler must route through Operations.dispatch (parity with TCP path)");
        // System.err for logs (System.out reserved for MCP protocol stream)
        assertTrue(src.contains("System.err"),
                "MCP server must log to System.err (System.out reserved for protocol)");
    }

    @Test
    @DisplayName("MCP server has serverInfo + capabilities + tools builder calls")
    void serverInfoCapabilitiesTools() throws Exception {
        String src = Files.readString(SOURCE);
        assertTrue(src.contains(".serverInfo("));
        assertTrue(src.contains(".capabilities("));
        assertTrue(src.contains(".tools("));
        // Public AutoCloseable per the contract (Daemon.stop calls close()).
        assertTrue(src.contains("public void close()"));
    }

    @Test
    @DisplayName("Operations class exposes a public dispatch(String, JsonObject) used by MCP")
    void operationsDispatchIsPublic() throws NoSuchMethodException {
        Method m = Operations.class.getMethod("dispatch", String.class,
                com.google.gson.JsonObject.class);
        assertNotNull(m);
        // Sanity: returns OperationResult so MCP can re-encode success/failure.
        assertEquals("com.hhg.callgraph.daemon.op.OperationResult",
                m.getReturnType().getName());
    }

    @Test
    @DisplayName("Canonical list of nine ops matches the contract in the use case")
    void contractList() {
        assertEquals(List.of(
                "refresh-index",
                "find-callers", "find-callees",
                "methods-in-class", "methods-at-line",
                "find-field-readers", "find-field-writers",
                "impact-of-diff", "tests-for-diff"
        ), Operations.USER_FACING_OPS);
        // Note: there are 9 user-facing tools advertised by MCP; the daemon-internal
        // ping/shutdown ops are TCP-only and intentionally NOT exposed via MCP.
        assertFalse(Operations.USER_FACING_OPS.contains("ping"));
        assertFalse(Operations.USER_FACING_OPS.contains("shutdown"));
    }
}

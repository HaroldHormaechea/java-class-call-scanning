package com.hhg.callgraph.daemon.op;

/**
 * Wire-boundary mapping for {@code TestDescriptor.testType()}. Internal code uses
 * the legacy {@code "JUnit5"} / {@code "Spock"} strings; the wire protocol uses
 * {@code "junit5"} / {@code "spock"} per AC #18.
 *
 * <p>This is called <em>only</em> when serialising responses to TCP / MCP clients —
 * never from internal logic.
 */
public final class TestTypeMapping {

    private TestTypeMapping() {}

    public static String wireType(String internal) {
        if ("JUnit5".equals(internal)) return "junit5";
        if ("Spock".equals(internal))  return "spock";
        throw new IllegalStateException("Unmapped test type: " + internal);
    }
}

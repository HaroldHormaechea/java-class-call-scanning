package com.hhg.callgraph.output;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.MethodReference;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CallTreePrinter {

    private final CallGraph graph;
    private final PrintStream out;

    public CallTreePrinter(CallGraph graph, PrintStream out) {
        this.graph = graph;
        this.out = out;
    }

    // -----------------------------------------------------------------------
    // Console (pipe-style) output
    // -----------------------------------------------------------------------

    public void printCalleeTree(MethodReference root, int maxDepth) {
        out.println(root.toDisplayString());
        Set<MethodReference> path = new HashSet<>();
        path.add(root);
        printChildren(root, "", 0, maxDepth, path, true);
    }

    public void printCallerTree(MethodReference root, int maxDepth) {
        out.println(root.toDisplayString());
        Set<MethodReference> path = new HashSet<>();
        path.add(root);
        printChildren(root, "", 0, maxDepth, path, false);
    }

    private void printChildren(MethodReference node, String indent, int depth, int maxDepth,
                               Set<MethodReference> path, boolean callees) {
        if (maxDepth > 0 && depth >= maxDepth) return;

        List<MethodReference> children = sorted(
                callees ? graph.getCalleesOf(node) : graph.getCallersOf(node));

        for (int i = 0; i < children.size(); i++) {
            boolean isLast = i == children.size() - 1;
            MethodReference child = children.get(i);
            String connector   = isLast ? "\\-- " : "+-- ";
            String childIndent = indent + (isLast ? "    " : "|   ");

            if (path.contains(child)) {
                out.println(indent + connector + child.toDisplayString() + " [cycle]");
            } else {
                out.println(indent + connector + child.toDisplayString());
                path.add(child);
                printChildren(child, childIndent, depth + 1, maxDepth, path, callees);
                path.remove(child);
            }
        }
    }

    // -----------------------------------------------------------------------
    // JSON output
    // -----------------------------------------------------------------------

    public String buildCalleeTreeJson(MethodReference root, int maxDepth) {
        Set<MethodReference> path = new HashSet<>();
        path.add(root);
        return nodeToJson(root, path, 0, maxDepth, true);
    }

    public String buildCallerTreeJson(MethodReference root, int maxDepth) {
        Set<MethodReference> path = new HashSet<>();
        path.add(root);
        return nodeToJson(root, path, 0, maxDepth, false);
    }

    private String nodeToJson(MethodReference node, Set<MethodReference> path,
                              int depth, int maxDepth, boolean callees) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"method\":").append(jsonString(node.toDisplayString()));

        if (maxDepth <= 0 || depth < maxDepth) {
            List<MethodReference> children = sorted(
                    callees ? graph.getCalleesOf(node) : graph.getCallersOf(node))
                    .stream().filter(n -> !path.contains(n)).toList();

            if (!children.isEmpty()) {
                sb.append(",\"children\":[");
                for (int i = 0; i < children.size(); i++) {
                    if (i > 0) sb.append(",");
                    path.add(children.get(i));
                    sb.append(nodeToJson(children.get(i), path, depth + 1, maxDepth, callees));
                    path.remove(children.get(i));
                }
                sb.append("]");
            }
        }

        sb.append("}");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static List<MethodReference> sorted(Set<MethodReference> methods) {
        return methods.stream()
                .sorted(Comparator.comparing(MethodReference::toDisplayString))
                .toList();
    }

    static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

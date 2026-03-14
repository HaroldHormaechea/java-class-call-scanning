package com.hhg.callgraph.output;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.TestIndex;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CallTreePrinter {

    private final CallGraph graph;
    private final PrintStream out;
    private final TestIndex testIndex;

    public CallTreePrinter(CallGraph graph, PrintStream out) {
        this(graph, out, TestIndex.empty());
    }

    public CallTreePrinter(CallGraph graph, PrintStream out, TestIndex testIndex) {
        this.graph = graph;
        this.out = out;
        this.testIndex = testIndex;
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

    public JsonObject buildCalleeTreeJson(MethodReference root, int maxDepth) {
        Set<MethodReference> path = new HashSet<>();
        path.add(root);
        return nodeToJson(root, path, 0, maxDepth, true);
    }

    public JsonObject buildCallerTreeJson(MethodReference root, int maxDepth) {
        Set<MethodReference> path = new HashSet<>();
        path.add(root);
        return nodeToJson(root, path, 0, maxDepth, false);
    }

    private JsonObject nodeToJson(MethodReference node, Set<MethodReference> path,
                                  int depth, int maxDepth, boolean callees) {
        JsonObject obj = new JsonObject();
        obj.addProperty("method", node.toDisplayString());

        if (testIndex.isTestMethod(node)) {
            obj.addProperty("isTest", true);
            testIndex.getDescriptor(node).ifPresent(desc -> {
                obj.addProperty("testType", desc.testType());
                obj.addProperty("testDisplayName", desc.displayName());
            });
        }

        if (maxDepth <= 0 || depth < maxDepth) {
            List<MethodReference> children = sorted(
                    callees ? graph.getCalleesOf(node) : graph.getCallersOf(node))
                    .stream().filter(n -> !path.contains(n)).toList();

            if (!children.isEmpty()) {
                JsonArray childrenArray = new JsonArray();
                for (MethodReference child : children) {
                    path.add(child);
                    childrenArray.add(nodeToJson(child, path, depth + 1, maxDepth, callees));
                    path.remove(child);
                }
                obj.add("children", childrenArray);
            }
        }

        return obj;
    }

    // -----------------------------------------------------------------------
    // Impact caller tree — unlimited depth, cycle-safe, split class/method
    // -----------------------------------------------------------------------

    /**
     * Builds the full caller tree for a method with unlimited depth.
     * Stops at cycles (marks them with "cycle": true) and leaves.
     * Each node has separate className, methodName, and fqn fields,
     * plus isTest/testType/testDisplayName when applicable.
     */
    public JsonObject buildImpactCallerTree(MethodReference root) {
        Set<MethodReference> path = new HashSet<>();
        path.add(root);
        return impactCallerNode(root, path);
    }

    private JsonObject impactCallerNode(MethodReference node, Set<MethodReference> path) {
        JsonObject obj = methodToJsonNode(node);

        List<MethodReference> callers = sorted(graph.getCallersOf(node));

        if (!callers.isEmpty()) {
            JsonArray childrenArray = new JsonArray();
            for (MethodReference caller : callers) {
                if (path.contains(caller)) {
                    JsonObject cycleNode = methodToJsonNode(caller);
                    cycleNode.addProperty("cycle", true);
                    childrenArray.add(cycleNode);
                } else {
                    path.add(caller);
                    childrenArray.add(impactCallerNode(caller, path));
                    path.remove(caller);
                }
            }
            obj.add("callers", childrenArray);
        }

        return obj;
    }

    private JsonObject methodToJsonNode(MethodReference node) {
        JsonObject obj = new JsonObject();
        obj.addProperty("className", node.getClassName().replace('/', '.'));
        obj.addProperty("methodName", node.getMethodName());
        obj.addProperty("fqn", node.toDisplayString());

        if (testIndex.isTestMethod(node)) {
            obj.addProperty("isTest", true);
            testIndex.getDescriptor(node).ifPresent(desc -> {
                obj.addProperty("testType", desc.testType());
                obj.addProperty("testDisplayName", desc.displayName());
            });
        }

        return obj;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static List<MethodReference> sorted(Set<MethodReference> methods) {
        return methods.stream()
                .sorted(Comparator.comparing(MethodReference::toDisplayString))
                .toList();
    }
}

package com.hhg.callgraph.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class CallGraph {

    private final Map<MethodReference, Set<MethodReference>> callerToCallees = new HashMap<>();
    private final Map<MethodReference, Set<MethodReference>> calleeToCaller = new HashMap<>();

    public void addCall(MethodReference caller, MethodReference callee) {
        callerToCallees.computeIfAbsent(caller, k -> new HashSet<>()).add(callee);
        calleeToCaller.computeIfAbsent(callee, k -> new HashSet<>()).add(caller);
    }

    public Set<MethodReference> getCalleesOf(MethodReference caller) {
        return Collections.unmodifiableSet(callerToCallees.getOrDefault(caller, Collections.emptySet()));
    }

    public Set<MethodReference> getCallersOf(MethodReference callee) {
        return Collections.unmodifiableSet(calleeToCaller.getOrDefault(callee, Collections.emptySet()));
    }

    public Set<MethodReference> getAllTransitiveCallees(MethodReference caller) {
        return traverseTransitive(caller, callerToCallees);
    }

    public Set<MethodReference> getAllTransitiveCallers(MethodReference callee) {
        return traverseTransitive(callee, calleeToCaller);
    }

    private Set<MethodReference> traverseTransitive(
            MethodReference start, Map<MethodReference, Set<MethodReference>> adjacency) {
        Set<MethodReference> visited = new HashSet<>();
        Queue<MethodReference> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            MethodReference current = queue.poll();
            for (MethodReference neighbor : adjacency.getOrDefault(current, Collections.emptySet())) {
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        visited.remove(start);
        return visited;
    }

    public Set<MethodReference> getAllMethods() {
        Set<MethodReference> all = new HashSet<>();
        all.addAll(callerToCallees.keySet());
        all.addAll(calleeToCaller.keySet());
        return Collections.unmodifiableSet(all);
    }

    public int methodCount() {
        return getAllMethods().size();
    }

    public void mergeFrom(CallGraph other) {
        for (MethodReference caller : other.callerToCallees.keySet()) {
            for (MethodReference callee : other.callerToCallees.get(caller)) {
                addCall(caller, callee);
            }
        }
    }

    public int edgeCount() {
        int count = 0;
        for (Set<MethodReference> callees : callerToCallees.values()) {
            count += callees.size();
        }
        return count;
    }

    /**
     * Returns a deep copy of this graph. Used by the incremental rebuild path so the
     * draft snapshot can be mutated without touching the live snapshot.
     */
    public CallGraph copy() {
        CallGraph clone = new CallGraph();
        for (Map.Entry<MethodReference, Set<MethodReference>> e : callerToCallees.entrySet()) {
            clone.callerToCallees.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        for (Map.Entry<MethodReference, Set<MethodReference>> e : calleeToCaller.entrySet()) {
            clone.calleeToCaller.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        return clone;
    }

    /**
     * Removes every outgoing edge whose <em>caller</em> belongs to the given internal
     * class name. Edges where only the callee class matches are intentionally retained —
     * they're cleaned up the next time the caller class itself is re-scanned. This is the
     * standard incremental-build trade-off and produces transient "ghost" edges if a
     * callee method is renamed/removed; users who care can run {@code refresh-index}
     * for a full rescan.
     */
    public void removeClass(String internalClassName) {
        if (internalClassName == null) return;
        List<MethodReference> callersToRemove = new ArrayList<>();
        for (MethodReference caller : callerToCallees.keySet()) {
            if (internalClassName.equals(caller.getClassName())) {
                callersToRemove.add(caller);
            }
        }
        for (MethodReference caller : callersToRemove) {
            Set<MethodReference> callees = callerToCallees.remove(caller);
            if (callees != null) {
                for (MethodReference callee : callees) {
                    Set<MethodReference> callers = calleeToCaller.get(callee);
                    if (callers != null) {
                        callers.remove(caller);
                        if (callers.isEmpty()) {
                            calleeToCaller.remove(callee);
                        }
                    }
                }
            }
        }
    }
}

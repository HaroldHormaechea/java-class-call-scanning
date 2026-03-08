package com.hhg.callgraph.scanner.test;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.TestDescriptor;
import com.hhg.callgraph.model.TestIndex;

import java.util.LinkedHashSet;
import java.util.Set;

public class TestSelector {

    /**
     * Given a set of affected methods (from a git diff + impact analysis), returns all tests
     * that transitively call any of them — i.e., the tests that need to run.
     */
    public Set<TestDescriptor> selectTests(CallGraph callGraph, TestIndex testIndex,
                                           Set<MethodReference> affectedMethods) {
        Set<TestDescriptor> result = new LinkedHashSet<>();
        for (MethodReference affected : affectedMethods) {
            Set<MethodReference> callers = callGraph.getAllTransitiveCallers(affected);
            callers.add(affected);
            for (MethodReference caller : callers) {
                testIndex.getDescriptor(caller).ifPresent(result::add);
            }
        }
        return result;
    }
}

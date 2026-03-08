package com.hhg.callgraph.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class TestIndex {

    private final Map<MethodReference, TestDescriptor> tests = new HashMap<>();

    public void add(TestDescriptor descriptor) {
        tests.put(descriptor.method(), descriptor);
    }

    public boolean isTestMethod(MethodReference ref) {
        return tests.containsKey(ref);
    }

    public Optional<TestDescriptor> getDescriptor(MethodReference ref) {
        return Optional.ofNullable(tests.get(ref));
    }

    public Set<MethodReference> allTestMethods() {
        return Collections.unmodifiableSet(tests.keySet());
    }

    public int size() {
        return tests.size();
    }

    public static TestIndex empty() {
        return new TestIndex();
    }
}

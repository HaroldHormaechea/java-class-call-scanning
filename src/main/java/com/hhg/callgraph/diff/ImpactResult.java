package com.hhg.callgraph.diff;

import com.hhg.callgraph.model.MethodReference;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public record ImpactResult(
        Set<MethodReference> directlyChanged,
        Set<MethodReference> transitiveCallers
) {

    public Set<MethodReference> allImpacted() {
        Set<MethodReference> all = new HashSet<>(directlyChanged);
        all.addAll(transitiveCallers);
        return Collections.unmodifiableSet(all);
    }
}

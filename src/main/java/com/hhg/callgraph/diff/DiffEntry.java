package com.hhg.callgraph.diff;

import java.util.Set;

public record DiffEntry(String filePath, Set<Integer> changedLines) {
}

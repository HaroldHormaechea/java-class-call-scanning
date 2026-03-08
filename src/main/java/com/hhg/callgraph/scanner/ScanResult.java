package com.hhg.callgraph.scanner;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.FieldAccessIndex;
import com.hhg.callgraph.model.SourceIndex;
import com.hhg.callgraph.model.TestIndex;

public record ScanResult(CallGraph callGraph, SourceIndex sourceIndex, FieldAccessIndex fieldAccessIndex,
                         TestIndex testIndex) {
}

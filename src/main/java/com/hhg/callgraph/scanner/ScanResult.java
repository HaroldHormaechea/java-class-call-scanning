package com.hhg.callgraph.scanner;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.FieldAccessIndex;
import com.hhg.callgraph.model.SourceIndex;

public record ScanResult(CallGraph callGraph, SourceIndex sourceIndex, FieldAccessIndex fieldAccessIndex) {
}

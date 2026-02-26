package com.hhg.callgraph.diff;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.MethodReference;
import com.hhg.callgraph.model.SourceIndex;
import com.hhg.callgraph.scanner.ScanResult;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ImpactAnalyzer {

    private final CallGraph callGraph;
    private final SourceIndex sourceIndex;
    private final Path sourcesRoot;

    public ImpactAnalyzer(ScanResult scanResult) {
        this(scanResult, null);
    }

    public ImpactAnalyzer(ScanResult scanResult, Path sourcesRoot) {
        this.callGraph = scanResult.callGraph();
        this.sourceIndex = scanResult.sourceIndex();
        this.sourcesRoot = sourcesRoot;
    }

    public ImpactResult analyze(List<DiffEntry> diffs) {
        Set<MethodReference> directlyChanged = new HashSet<>();

        for (DiffEntry diff : diffs) {
            String filePath = normalizeDiffPath(diff.filePath());
            for (int line : diff.changedLines()) {
                directlyChanged.addAll(sourceIndex.findMethodsAt(filePath, line));
            }
        }

        Set<MethodReference> transitiveCallers = new HashSet<>();
        for (MethodReference method : directlyChanged) {
            transitiveCallers.addAll(callGraph.getAllTransitiveCallers(method));
        }
        transitiveCallers.removeAll(directlyChanged);

        return new ImpactResult(directlyChanged, transitiveCallers);
    }

    /**
     * When a sources root is configured, strips it from the diff file path so that
     * {@code src/main/java/com/example/Foo.java} becomes {@code com/example/Foo.java},
     * enabling an exact package-qualified match in {@link SourceIndex#findMethodsAt}.
     */
    private String normalizeDiffPath(String filePath) {
        if (sourcesRoot == null) return filePath;
        String normalizedPath = filePath.replace('\\', '/');
        String root = sourcesRoot.toString().replace('\\', '/');
        if (!root.endsWith("/")) root += "/";
        return normalizedPath.startsWith(root) ? normalizedPath.substring(root.length()) : filePath;
    }
}

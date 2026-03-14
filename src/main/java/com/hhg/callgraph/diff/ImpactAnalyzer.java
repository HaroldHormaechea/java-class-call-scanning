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

        // Direct match: diff path starts with full sources root
        if (normalizedPath.startsWith(root)) {
            return normalizedPath.substring(root.length());
        }

        // Suffix match: diff path is relative (e.g. "spring-context/src/main/java/org/...")
        // and sources root is absolute. Find the overlapping suffix.
        int idx = root.indexOf(normalizedPath);
        if (idx >= 0) {
            // The diff path contains the sources root path as a prefix
            // e.g., diff="spring-context/src/main/java/org/Foo.java", root ends with "spring-context/src/main/java/"
            String diffAfterRoot = normalizedPath.substring(root.length() - idx);
            if (!diffAfterRoot.isEmpty()) return diffAfterRoot;
        }

        // Try finding where the diff path's directory overlaps with the end of the root
        // e.g., root="/.../spring-context/src/main/java/", diff="spring-context/src/main/java/org/Foo.java"
        for (int i = 1; i < normalizedPath.length(); i++) {
            if (normalizedPath.charAt(i) == '/') {
                String prefix = normalizedPath.substring(0, i + 1);
                if (root.endsWith(prefix)) {
                    return normalizedPath.substring(i + 1);
                }
            }
        }

        return filePath;
    }
}

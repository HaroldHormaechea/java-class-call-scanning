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
    private final List<Path> sourceRoots;

    public ImpactAnalyzer(ScanResult scanResult) {
        this(scanResult, (Path) null);
    }

    /**
     * Legacy single-root constructor. {@code null} preserves the historical "no roots"
     * behaviour (paths are passed through verbatim to {@link SourceIndex}); a non-null
     * path is wrapped as {@code List.of(sourcesRoot)} so the new path-stripping logic
     * is reused without forking codepaths.
     */
    public ImpactAnalyzer(ScanResult scanResult, Path sourcesRoot) {
        this(scanResult, sourcesRoot == null ? List.<Path>of() : List.of(sourcesRoot));
    }

    /** Canonical multi-root constructor used by the UC01 daemon (one or more {@code --src} roots). */
    public ImpactAnalyzer(ScanResult scanResult, List<Path> sourceRoots) {
        this.callGraph = scanResult.callGraph();
        this.sourceIndex = scanResult.sourceIndex();
        this.sourceRoots = sourceRoots == null ? List.<Path>of() : List.copyOf(sourceRoots);
    }

    public ImpactResult analyze(List<DiffEntry> diffs) {
        Set<MethodReference> directlyChanged = new HashSet<>();

        for (DiffEntry diff : diffs) {
            String filePath = normalizeDiffPath(diff.filePath());
            for (int line : diff.changedLines()) {
                directlyChanged.addAll(sourceIndex.findMethodsAt(filePath, line));
            }
        }

        // Also include callers of the <classref> sentinel for each affected class.
        // This catches Groovy/dynamic-dispatch code that references the class without
        // a direct INVOKE* instruction.
        Set<String> affectedClasses = new HashSet<>();
        for (MethodReference method : directlyChanged) {
            affectedClasses.add(method.getClassName());
        }

        Set<MethodReference> transitiveCallers = new HashSet<>();
        for (MethodReference method : directlyChanged) {
            transitiveCallers.addAll(callGraph.getAllTransitiveCallers(method));
        }
        for (String className : affectedClasses) {
            MethodReference classRef = new MethodReference(className, "<classref>", "()V");
            transitiveCallers.addAll(callGraph.getAllTransitiveCallers(classRef));
        }
        transitiveCallers.removeAll(directlyChanged);

        return new ImpactResult(directlyChanged, transitiveCallers);
    }

    /**
     * Iterates the configured source roots in order; the first root that produces a
     * stripped result wins. With zero roots configured the path is returned verbatim
     * (legacy "no sources root" behaviour preserved by the
     * {@link #ImpactAnalyzer(ScanResult, Path)} delegation with {@code null}).
     */
    private String normalizeDiffPath(String filePath) {
        if (sourceRoots.isEmpty()) return filePath;
        for (Path root : sourceRoots) {
            String stripped = stripWithRoot(filePath, root);
            if (stripped != null) return stripped;
        }
        return filePath;
    }

    private static String stripWithRoot(String filePath, Path sourcesRoot) {
        if (sourcesRoot == null) return null;
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

        return null;
    }
}

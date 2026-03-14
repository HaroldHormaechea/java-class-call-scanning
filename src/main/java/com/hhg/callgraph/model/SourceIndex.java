package com.hhg.callgraph.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class SourceIndex {

    private final Map<MethodReference, SourceLocation> locationByMethod = new HashMap<>();

    public void add(MethodReference method, SourceLocation location) {
        locationByMethod.put(method, location);
    }

    public Optional<SourceLocation> getLocation(MethodReference method) {
        return Optional.ofNullable(locationByMethod.get(method));
    }

    public Set<MethodReference> findMethodsAt(String sourceFile, int line) {
        String normalizedQuery = sourceFile.replace('\\', '/');
        Set<MethodReference> result = new HashSet<>();
        for (Map.Entry<MethodReference, SourceLocation> entry : locationByMethod.entrySet()) {
            MethodReference method = entry.getKey();
            SourceLocation loc = entry.getValue();
            if (matchesPath(normalizedQuery, method.getClassName(), loc.sourceFile()) && loc.containsLine(line)) {
                result.add(method);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public void mergeFrom(SourceIndex other) {
        locationByMethod.putAll(other.locationByMethod);
    }

    public int size() {
        return locationByMethod.size();
    }

    /**
     * Matches a diff query path against a stored source file using two strategies:
     * <ol>
     *   <li>Package-qualified suffix match — reconstructs {@code com/pkg/Foo.java} from
     *       the class name and checks whether the (normalized) query path ends with it,
     *       bounded by a {@code /} separator. This handles diff paths that include any
     *       source-root prefix and eliminates false positives from same-simple-named files
     *       in different packages.</li>
     *   <li>Filename-only fallback — strips everything up to the last separator and
     *       compares simple names, preserving backward compatibility for callers that pass
     *       only a bare filename.</li>
     * </ol>
     */
    private static boolean matchesPath(String normalizedQuery, String className, String storedFile) {
        // Derive package-relative path, e.g. "com/example/Foo.java"
        int lastSlash = className.lastIndexOf('/');
        String packageRelative = (lastSlash >= 0 ? className.substring(0, lastSlash + 1) : "") + storedFile;

        // Tier 1: exact or slash-bounded suffix match (correct for package-qualified paths)
        if (normalizedQuery.equals(packageRelative) || normalizedQuery.endsWith("/" + packageRelative)) {
            return true;
        }

        // Tier 2: filename-only fallback (backward compat)
        return normalizeToFilename(storedFile).equals(normalizeToFilename(normalizedQuery));
    }

    private static String normalizeToFilename(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}

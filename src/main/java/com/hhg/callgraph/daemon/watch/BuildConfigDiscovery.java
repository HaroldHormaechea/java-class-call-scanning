package com.hhg.callgraph.daemon.watch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Static helper that walks ancestors of each source root looking for build / dependency
 * configuration files (AC #4). Returns the nearest-ancestor match per filename,
 * deduplicated across source roots. The watcher only registers what
 * {@link #discover(java.util.List)} returns — any subproject build file living outside
 * the watched tree is missed by design, documented in the README.
 */
public final class BuildConfigDiscovery {

    /** The names recognised, in priority order (only used for stable iteration). */
    public static final List<String> CONFIG_FILE_NAMES = List.of(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle/libs.versions.toml",
            "pom.xml"
    );

    private BuildConfigDiscovery() {}

    /**
     * Discovers nearest-ancestor build-config files across the given source roots.
     * Returns absolute, normalised paths. Order is deterministic by input order then
     * config-file name; duplicates across source roots are removed.
     */
    public static List<Path> discover(List<Path> sourceRoots) {
        Set<Path> out = new LinkedHashSet<>();
        if (sourceRoots == null) return new ArrayList<>(out);
        for (Path src : sourceRoots) {
            if (src == null) continue;
            Path canonical;
            try {
                canonical = src.toAbsolutePath().normalize();
            } catch (Exception e) {
                continue;
            }
            for (String name : CONFIG_FILE_NAMES) {
                Path found = findNearestAncestor(canonical, name);
                if (found != null) {
                    out.add(found);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Walks upward from {@code start} (inclusive) looking for {@code relativeName}
     * (which may itself contain {@code /} segments, e.g. {@code gradle/libs.versions.toml}).
     * Returns the first match or {@code null} if none.
     */
    private static Path findNearestAncestor(Path start, String relativeName) {
        Path dir = start;
        while (dir != null) {
            Path candidate = dir.resolve(relativeName);
            if (Files.isRegularFile(candidate)) {
                try {
                    return candidate.toAbsolutePath().normalize();
                } catch (Exception e) {
                    return candidate;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }
}

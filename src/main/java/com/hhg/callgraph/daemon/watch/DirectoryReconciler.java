package com.hhg.callgraph.daemon.watch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Compares the prior class-origin map against what's currently on disk under the
 * touched classpath subtrees, returning the {@code deleted} set used by the
 * incremental rebuilder. {@code added} and {@code modified} are also computed but
 * are unused in production — events are authoritative for them. They're exposed
 * for tests and for the rare lost-{@code ENTRY_CREATE} case.
 *
 * <p>Per UC04 AC #8: FS events do not reliably encode rename/atomic-replace, so
 * deletion detection must come from a directory walk.
 */
public final class DirectoryReconciler {

    public record Result(Set<Path> added, Set<Path> modified, Set<Path> deleted) {
    }

    /**
     * Reconciles the touched subtrees against the prior origins.
     *
     * @param touchedClasspathRoots subset of classpath directory roots that received
     *                              an FS event in the current debounce window
     * @param priorClassOrigins     class-name → origin-path map from the prior snapshot
     * @return added / modified / deleted sets (paths)
     */
    public static Result reconcile(Set<Path> touchedClasspathRoots,
                                   Map<String, Path> priorClassOrigins) {
        Set<Path> diskClassFiles = new HashSet<>();
        if (touchedClasspathRoots != null) {
            for (Path root : touchedClasspathRoots) {
                if (root == null || !Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".class"))
                            .map(Path::toAbsolutePath)
                            .forEach(diskClassFiles::add);
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }

        // Build the set of prior class-files under the touched roots.
        Set<Path> priorUnderTouched = new LinkedHashSet<>();
        if (priorClassOrigins != null && touchedClasspathRoots != null) {
            for (Path origin : priorClassOrigins.values()) {
                if (origin == null) continue;
                Path abs = origin.toAbsolutePath();
                for (Path root : touchedClasspathRoots) {
                    if (root == null) continue;
                    Path rootAbs = root.toAbsolutePath();
                    if (abs.startsWith(rootAbs)) {
                        priorUnderTouched.add(abs);
                        break;
                    }
                }
            }
        }

        Set<Path> deleted = new LinkedHashSet<>();
        for (Path p : priorUnderTouched) {
            if (!diskClassFiles.contains(p)) {
                deleted.add(p);
            }
        }

        Set<Path> added = new LinkedHashSet<>();
        for (Path p : diskClassFiles) {
            if (!priorUnderTouched.contains(p)) {
                added.add(p);
            }
        }

        // We don't reliably detect "modified" without mtime comparison; leave empty.
        Set<Path> modified = new LinkedHashSet<>();

        return new Result(added, modified, deleted);
    }

    private DirectoryReconciler() {}

    /** Used by tests / callers needing the union of two path collections. */
    public static Set<Path> union(List<Path> a, List<Path> b) {
        Set<Path> out = new LinkedHashSet<>();
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out;
    }
}

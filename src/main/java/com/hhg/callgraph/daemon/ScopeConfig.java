package com.hhg.callgraph.daemon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Canonical scope a daemon was launched with. Drives {@code <project-hash>} resolution
 * (used for the discovery filename) and is frozen for the daemon's lifetime —
 * {@code refresh-index} re-scans this same scope; it never re-reads CLI flags.
 *
 * <p>Hash rule: first 16 hex chars of SHA-256 over sorted, newline-joined canonical
 * absolute paths of {@code classpath ∪ source-roots}. Include / exclude globs are NOT
 * part of the hash.
 */
public final class ScopeConfig {

    private final List<Path> classpath;     // canonical, absolute, dedup-preserved order
    private final List<Path> sourceRoots;   // canonical, absolute, dedup-preserved order
    private final List<String> include;     // raw globs (may be empty)
    private final List<String> exclude;     // raw globs (may be empty)
    private final String projectHash;

    private ScopeConfig(List<Path> classpath, List<Path> sourceRoots,
                        List<String> include, List<String> exclude, String projectHash) {
        this.classpath = classpath;
        this.sourceRoots = sourceRoots;
        this.include = include;
        this.exclude = exclude;
        this.projectHash = projectHash;
    }

    /**
     * Canonicalises the given paths and computes the project hash. Paths are
     * resolved to absolute form via {@link Path#toRealPath()} when possible, falling
     * back to {@link Path#toAbsolutePath()} for paths that don't yet exist (the
     * caller is responsible for validating existence before scanning).
     */
    public static ScopeConfig of(List<Path> rawClasspath, List<Path> rawSourceRoots,
                                 List<String> include, List<String> exclude) {
        List<Path> canonClasspath = canonicalise(rawClasspath);
        List<Path> canonSources   = canonicalise(rawSourceRoots);
        List<String> safeInclude  = include == null ? List.of() : List.copyOf(include);
        List<String> safeExclude  = exclude == null ? List.of() : List.copyOf(exclude);

        String hash = computeHash(canonClasspath, canonSources);
        return new ScopeConfig(canonClasspath, canonSources, safeInclude, safeExclude, hash);
    }

    private static List<Path> canonicalise(List<Path> raw) {
        if (raw == null) return List.of();
        List<Path> out = new ArrayList<>(raw.size());
        for (Path p : raw) {
            Path canon;
            try {
                canon = p.toAbsolutePath().normalize();
                // Try real-path when the file exists; if not, fall back to abs+normalised.
                try {
                    canon = canon.toRealPath();
                } catch (IOException ignored) {
                    // File doesn't exist yet — keep the normalised absolute path.
                }
            } catch (Exception e) {
                throw new UncheckedIOException(new IOException("Cannot canonicalise " + p, e));
            }
            if (!out.contains(canon)) {
                out.add(canon);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String computeHash(List<Path> classpath, List<Path> sourceRoots) {
        List<String> all = new ArrayList<>(classpath.size() + sourceRoots.size());
        for (Path p : classpath)   all.add(p.toString());
        for (Path p : sourceRoots) all.add(p.toString());
        Collections.sort(all);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(all.get(i));
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++) {
                hex.append(String.format("%02x", digest[i] & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public List<Path> classpath()       { return classpath; }
    public List<Path> sourceRoots()     { return sourceRoots; }
    public List<String> include()       { return include; }
    public List<String> exclude()       { return exclude; }
    public String projectHash()         { return projectHash; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScopeConfig that)) return false;
        return classpath.equals(that.classpath)
                && sourceRoots.equals(that.sourceRoots)
                && include.equals(that.include)
                && exclude.equals(that.exclude);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classpath, sourceRoots, include, exclude);
    }

    @Override
    public String toString() {
        return "ScopeConfig{hash=" + projectHash
                + ", classpath=" + classpath
                + ", sources=" + sourceRoots
                + ", include=" + include
                + ", exclude=" + exclude + "}";
    }
}

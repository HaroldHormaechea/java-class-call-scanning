package com.hhg.callgraph.daemon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AC #4 — project hash is computed over the normalized, sorted set of canonical
 * classpath + source-root paths. Include/exclude globs are NOT part of the hash.
 */
@DisplayName("ScopeConfig — hash determinism & canonicalisation")
class ScopeConfigTest {

    @Test
    @DisplayName("hash is stable for identical inputs")
    void hashStable(@TempDir Path tmp) throws IOException {
        Path cp = Files.createDirectory(tmp.resolve("classes"));
        Path src = Files.createDirectory(tmp.resolve("src"));
        ScopeConfig a = ScopeConfig.of(List.of(cp), List.of(src), List.of(), List.of());
        ScopeConfig b = ScopeConfig.of(List.of(cp), List.of(src), List.of(), List.of());
        assertEquals(a.projectHash(), b.projectHash());
        assertEquals(16, a.projectHash().length(), "hash should be 16 hex chars (8 bytes)");
    }

    @Test
    @DisplayName("hash is order-independent — inputs are sorted before hashing")
    void hashSorted(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        Path c = Files.createDirectory(tmp.resolve("c"));
        ScopeConfig p1 = ScopeConfig.of(List.of(a, b), List.of(c), List.of(), List.of());
        ScopeConfig p2 = ScopeConfig.of(List.of(b, a), List.of(c), List.of(), List.of());
        assertEquals(p1.projectHash(), p2.projectHash());
    }

    @Test
    @DisplayName("classpath ∪ source-roots hashed together")
    void unionHashed(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        // {classpath=[a], sources=[b]} and {classpath=[a,b], sources=[]} should
        // produce the same hash since the union is identical.
        ScopeConfig viaSrc = ScopeConfig.of(List.of(a), List.of(b), List.of(), List.of());
        ScopeConfig viaCp  = ScopeConfig.of(List.of(a, b), List.of(), List.of(), List.of());
        assertEquals(viaSrc.projectHash(), viaCp.projectHash(),
                "hash is over the union, so source/classpath split shouldn't matter");
    }

    @Test
    @DisplayName("include/exclude globs are NOT part of the hash")
    void includeExcludeNotHashed(@TempDir Path tmp) throws IOException {
        Path cp = Files.createDirectory(tmp.resolve("classes"));
        ScopeConfig bare = ScopeConfig.of(List.of(cp), List.of(), List.of(), List.of());
        ScopeConfig withGlobs = ScopeConfig.of(List.of(cp), List.of(),
                List.of("**/Foo.java"), List.of("**/Bar.java"));
        assertEquals(bare.projectHash(), withGlobs.projectHash());
    }

    @Test
    @DisplayName("different paths produce different hashes")
    void differentPathsDifferentHashes(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        ScopeConfig pa = ScopeConfig.of(List.of(a), List.of(), List.of(), List.of());
        ScopeConfig pb = ScopeConfig.of(List.of(b), List.of(), List.of(), List.of());
        assertNotEquals(pa.projectHash(), pb.projectHash());
    }

    @Test
    @DisplayName("relative paths are canonicalised to absolute")
    void relativeCanonicalised(@TempDir Path tmp) throws IOException {
        Path cp = Files.createDirectory(tmp.resolve("classes"));
        ScopeConfig sc = ScopeConfig.of(List.of(cp), List.of(), List.of(), List.of());
        assertTrue(sc.classpath().get(0).isAbsolute(),
                "canonical paths must be absolute");
    }

    @Test
    @DisplayName("hash is 16 hex chars (8 bytes of SHA-256)")
    void hashWidth(@TempDir Path tmp) throws IOException {
        Path cp = Files.createDirectory(tmp.resolve("c"));
        ScopeConfig sc = ScopeConfig.of(List.of(cp), List.of(), List.of(), List.of());
        assertTrue(sc.projectHash().matches("[0-9a-f]{16}"),
                "hash should be 16 lowercase hex chars; was: " + sc.projectHash());
    }

    @Test
    @DisplayName("equality uses (classpath, sources, include, exclude)")
    void equality(@TempDir Path tmp) throws IOException {
        Path cp = Files.createDirectory(tmp.resolve("c"));
        ScopeConfig a = ScopeConfig.of(List.of(cp), List.of(), List.of("i"), List.of("e"));
        ScopeConfig b = ScopeConfig.of(List.of(cp), List.of(), List.of("i"), List.of("e"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}

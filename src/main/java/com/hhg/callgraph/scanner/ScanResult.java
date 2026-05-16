package com.hhg.callgraph.scanner;

import com.hhg.callgraph.model.CallGraph;
import com.hhg.callgraph.model.FieldAccessIndex;
import com.hhg.callgraph.model.SourceIndex;
import com.hhg.callgraph.model.TestIndex;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Aggregated output of a scan pass.
 *
 * <p>{@code classOrigins} maps each scanned class's internal name (e.g.
 * {@code com/acme/Foo}) to the filesystem path that contributed it. For directory roots,
 * that path is the {@code .class} file itself; for JAR/WAR archives, it is the archive
 * file (multiple classes share one origin). This is the authoritative reverse-lookup
 * used by the incremental rebuilder (UC04) to figure out which classes are affected by
 * a set of changed paths.
 */
public record ScanResult(CallGraph callGraph, SourceIndex sourceIndex, FieldAccessIndex fieldAccessIndex,
                         TestIndex testIndex, Map<String, Path> classOrigins) {

    /** Backwards-compatible constructor: no class-origin map. */
    public ScanResult(CallGraph callGraph, SourceIndex sourceIndex,
                      FieldAccessIndex fieldAccessIndex, TestIndex testIndex) {
        this(callGraph, sourceIndex, fieldAccessIndex, testIndex, new HashMap<>());
    }

    /** Defensive copy on construction so callers can't share mutable refs. */
    public ScanResult {
        if (classOrigins == null) {
            classOrigins = new HashMap<>();
        } else {
            classOrigins = new HashMap<>(classOrigins);
        }
    }
}

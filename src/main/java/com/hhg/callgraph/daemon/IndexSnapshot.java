package com.hhg.callgraph.daemon;

import com.hhg.callgraph.scanner.ScanResult;

/**
 * Immutable bundle of (scan result + scope + meta) atomically swapped into the daemon's
 * {@code AtomicReference} on rebuild. After construction the contained {@link ScanResult}
 * is treated as effectively immutable — readers never mutate it.
 */
public record IndexSnapshot(ScanResult scan, ScopeConfig scope, ScanMeta meta) {
}

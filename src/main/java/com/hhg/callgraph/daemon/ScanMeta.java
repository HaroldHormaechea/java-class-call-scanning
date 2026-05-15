package com.hhg.callgraph.daemon;

/**
 * Bookkeeping numbers attached to each {@link IndexSnapshot}. {@code scanMillis} is the
 * elapsed scan time used to populate the snapshot; {@code scannedAtEpochMillis} is the
 * wall-clock time the scan completed.
 */
public record ScanMeta(int methodCount, int edgeCount, long scanMillis, long scannedAtEpochMillis) {
}

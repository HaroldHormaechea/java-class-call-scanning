package com.hhg.callgraph.model;

public record SourceLocation(String sourceFile, int startLine, int endLine) {

    public SourceLocation {
        if (startLine != -1 || endLine != -1) {
            if (startLine > endLine) {
                throw new IllegalArgumentException(
                        "startLine (" + startLine + ") must be <= endLine (" + endLine + ")");
            }
        }
    }

    public boolean isUnknown() {
        return startLine == -1;
    }

    public boolean containsLine(int line) {
        if (isUnknown()) return false;
        return startLine <= line && line <= endLine;
    }

    @Override
    public String toString() {
        if (isUnknown()) {
            return sourceFile + ":(unknown)";
        }
        return sourceFile + ":" + startLine + "-" + endLine;
    }
}

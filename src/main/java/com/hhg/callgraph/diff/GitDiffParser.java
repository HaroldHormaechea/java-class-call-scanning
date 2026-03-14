package com.hhg.callgraph.diff;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitDiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    public List<DiffEntry> parse(String unifiedDiff) {
        List<DiffEntry> entries = new ArrayList<>();
        String currentFile = null;
        Set<Integer> currentLines = null;
        int currentLineNumber = 0;
        boolean inHunk = false;

        for (String rawLine : unifiedDiff.split("\\r?\\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.startsWith("+++ b/")) {
                if (currentFile != null) {
                    entries.add(new DiffEntry(currentFile, currentLines));
                }
                currentFile = line.substring("+++ b/".length());
                currentLines = new HashSet<>();
                inHunk = false;
                continue;
            }

            if (line.startsWith("--- ")) {
                continue;
            }

            Matcher hunkMatcher = HUNK_HEADER.matcher(line);
            if (hunkMatcher.find()) {
                currentLineNumber = Integer.parseInt(hunkMatcher.group(1));
                inHunk = true;
                continue;
            }

            if (inHunk && currentFile != null) {
                if (line.startsWith("+")) {
                    currentLines.add(currentLineNumber);
                    currentLineNumber++;
                } else if (line.startsWith("-")) {
                    // Removed line: do not increment new-file line number
                } else {
                    // Context line: increment new-file line number
                    currentLineNumber++;
                }
            }
        }

        if (currentFile != null && currentLines != null) {
            entries.add(new DiffEntry(currentFile, currentLines));
        }

        return entries;
    }

    public List<DiffEntry> parseFile(Path path) throws IOException {
        String content = readWithCharsetDetection(path);
        return parse(content);
    }

    private String readWithCharsetDetection(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        // Detect UTF-16 BOM (ff fe = LE, fe ff = BE) — use UTF_16 which auto-detects and strips BOM
        if (raw.length >= 2) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
                return new String(raw, StandardCharsets.UTF_16);
            }
        }
        // Detect UTF-8 BOM (ef bb bf)
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }
}

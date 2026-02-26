package com.hhg.callgraph.diff;

import java.io.IOException;
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

        for (String line : unifiedDiff.split("\n", -1)) {
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
        String content = Files.readString(path);
        return parse(content);
    }
}

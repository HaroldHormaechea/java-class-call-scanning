package com.hhg.callgraph.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tiny flag parser for {@link DaemonCli}. Each subcommand declares the flags it
 * accepts; unknown flags trigger a {@link BadArgsException} with a {@code "Did you
 * mean?"} suggestion when the Levenshtein distance is ≤ 3.
 */
public final class SubcommandParser {

    private final String subcommand;
    private final Set<String> recognisedFlags;
    private final Set<String> repeatableFlags;
    private final Set<String> booleanFlags;

    public SubcommandParser(String subcommand,
                            Set<String> recognisedFlags,
                            Set<String> repeatableFlags,
                            Set<String> booleanFlags) {
        this.subcommand = subcommand;
        this.recognisedFlags = recognisedFlags;
        this.repeatableFlags = repeatableFlags;
        this.booleanFlags    = booleanFlags;
    }

    /**
     * Parses the given args. Returns a {@code Map<flag, List<value>>}; boolean flags
     * map to a single-element list containing {@code "true"}.
     */
    public Map<String, List<String>> parse(String[] args) {
        Map<String, List<String>> out = new HashMap<>();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new BadArgsException("unexpected positional argument: " + arg);
            }
            if (!recognisedFlags.contains(arg)) {
                String suggestion = closestMatch(arg, recognisedFlags);
                throw new BadArgsException("unknown flag for `" + subcommand + "`: " + arg
                        + (suggestion != null ? " (did you mean: " + suggestion + "?)" : ""));
            }
            if (booleanFlags.contains(arg)) {
                out.computeIfAbsent(arg, k -> new ArrayList<>()).add("true");
                i++;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new BadArgsException("flag `" + arg + "` requires a value");
            }
            String value = args[i + 1];
            if (!repeatableFlags.contains(arg) && out.containsKey(arg)) {
                throw new BadArgsException("flag `" + arg + "` may not appear more than once");
            }
            out.computeIfAbsent(arg, k -> new ArrayList<>()).add(value);
            i += 2;
        }
        return out;
    }

    public static String firstOrThrow(Map<String, List<String>> parsed, String flag) {
        List<String> vs = parsed.get(flag);
        if (vs == null || vs.isEmpty()) {
            throw new BadArgsException("missing required flag: " + flag);
        }
        return vs.get(0);
    }

    public static String firstOrNull(Map<String, List<String>> parsed, String flag) {
        List<String> vs = parsed.get(flag);
        if (vs == null || vs.isEmpty()) return null;
        return vs.get(0);
    }

    public static List<String> allOrEmpty(Map<String, List<String>> parsed, String flag) {
        List<String> vs = parsed.get(flag);
        return vs == null ? Collections.emptyList() : vs;
    }

    public static boolean booleanFlag(Map<String, List<String>> parsed, String flag) {
        return parsed.containsKey(flag);
    }

    /**
     * Returns the recognised flag with Levenshtein distance ≤ 3 to the unknown input,
     * or {@code null} if no close match exists.
     */
    public static String closestMatch(String unknown, Iterable<String> recognised) {
        String best = null;
        int bestDist = 4;
        for (String r : recognised) {
            int d = levenshtein(unknown, r);
            if (d < bestDist) {
                bestDist = d;
                best = r;
            }
        }
        return bestDist <= 3 ? best : null;
    }

    /** Iterative Levenshtein with O(min(a,b)) space. */
    static int levenshtein(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.length() < b.length()) { String t = a; a = b; b = t; }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    /** Closest match against an arbitrary set of names (for subcommand suggestions). */
    public static String suggestClosestName(String unknown, List<String> recognisedNames) {
        return closestMatch(unknown, recognisedNames);
    }

    /** Convenience for callers that just need a tokenised view (kept for future use). */
    public static List<String> toList(String[] args) {
        return Arrays.asList(args);
    }

    /** Thrown for any structural parse error in user-supplied args. */
    public static final class BadArgsException extends RuntimeException {
        public BadArgsException(String msg) { super(msg); }
    }
}

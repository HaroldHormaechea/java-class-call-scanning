package com.hhg.callgraph.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hhg.callgraph.daemon.Daemon;
import com.hhg.callgraph.daemon.Daemonization;
import com.hhg.callgraph.daemon.Discovery;
import com.hhg.callgraph.daemon.ScopeConfig;
import com.hhg.callgraph.daemon.watch.LaunchArgs;
import com.hhg.callgraph.daemon.watch.WatcherConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top-level CLI dispatcher for daemon subcommands. {@code CallGraphBuilder.main}
 * routes here when the first arg is one of the bare subcommand tokens.
 *
 * <p>All success and error output is JSON on stdout (AC #8). Exit code is 0 on success,
 * non-zero on failure.
 */
public final class DaemonCli {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Known subcommand names, ordered so legacy intent is preserved. */
    public static final List<String> SUBCOMMANDS = List.of(
            "daemon",
            "refresh-index",
            "find-callers", "find-callees",
            "methods-in-class", "methods-at-line",
            "find-field-readers", "find-field-writers",
            "impact-of-diff", "tests-for-diff",
            "watcher-status"
    );

    public static int dispatch(String[] args) {
        if (args.length == 0) {
            return printErrorAndExit("bad-request", "no subcommand given", 2);
        }
        String sub = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            return switch (sub) {
                case "daemon"              -> runDaemonSubcommand(rest);
                case "refresh-index"       -> runQueryNoArgs("refresh-index", rest);
                case "find-callers"        -> runFindTree("find-callers", rest);
                case "find-callees"        -> runFindTree("find-callees", rest);
                case "methods-in-class"    -> runMethodsInClass(rest);
                case "methods-at-line"     -> runMethodsAtLine(rest);
                case "find-field-readers"  -> runFindField("find-field-readers", rest);
                case "find-field-writers"  -> runFindField("find-field-writers", rest);
                case "impact-of-diff"      -> runDiffOp("impact-of-diff", rest);
                case "tests-for-diff"      -> runDiffOp("tests-for-diff", rest);
                case "watcher-status"      -> runQueryNoArgs("watcher-status", rest);
                default -> {
                    String suggestion = SubcommandParser.suggestClosestName(sub, SUBCOMMANDS);
                    String detail = "unknown subcommand: " + sub
                            + (suggestion != null ? " (did you mean: " + suggestion + "?)" : "");
                    yield printErrorAndExit("bad-request", detail, 2);
                }
            };
        } catch (SubcommandParser.BadArgsException e) {
            return printErrorAndExit("bad-request", e.getMessage(), 2);
        } catch (RuntimeException e) {
            return printErrorAndExit("internal-error",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), 1);
        }
    }

    // -----------------------------------------------------------------------
    // `daemon start` / `daemon stop`
    // -----------------------------------------------------------------------

    private static int runDaemonSubcommand(String[] args) {
        if (args.length == 0) {
            return printErrorAndExit("bad-request",
                    "daemon requires 'start' or 'stop'", 2);
        }
        String action = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (action) {
            case "start" -> runDaemonStart(rest);
            case "stop"  -> runDaemonStop(rest);
            default      -> printErrorAndExit("bad-request",
                    "unknown daemon action: " + action, 2);
        };
    }

    private static int runDaemonStart(String[] args) {
        SubcommandParser p = new SubcommandParser("daemon start",
                Set.of("--classpath", "--src", "--include", "--exclude",
                        "--idle-timeout", "--foreground", "--internal-spawned", "--force",
                        // UC04 watcher flags
                        "--no-watch", "--watch-debounce-ms", "--restart-drain-ms",
                        "--log-file", "--log-max-size-mb", "--log-max-files"),
                Set.of("--classpath", "--src", "--include", "--exclude"),
                Set.of("--foreground", "--internal-spawned", "--force", "--no-watch"));
        Map<String, List<String>> parsed = p.parse(args);

        List<Path> classpath = SubcommandParser.allOrEmpty(parsed, "--classpath")
                .stream().map(Path::of).toList();
        List<Path> sources = SubcommandParser.allOrEmpty(parsed, "--src")
                .stream().map(Path::of).toList();
        List<String> include = SubcommandParser.allOrEmpty(parsed, "--include");
        List<String> exclude = SubcommandParser.allOrEmpty(parsed, "--exclude");

        if (classpath.isEmpty()) {
            return printErrorAndExit("bad-request",
                    "daemon start requires at least one --classpath", 2);
        }

        int idleMinutes = 0;
        String idleStr = SubcommandParser.firstOrNull(parsed, "--idle-timeout");
        if (idleStr != null) {
            try {
                idleMinutes = Integer.parseInt(idleStr);
                if (idleMinutes < 1) {
                    return printErrorAndExit("bad-request",
                            "--idle-timeout must be >= 1 minute", 2);
                }
            } catch (NumberFormatException nfe) {
                return printErrorAndExit("bad-request",
                        "--idle-timeout must be an integer minute count", 2);
            }
        }
        boolean foreground = SubcommandParser.booleanFlag(parsed, "--foreground");
        boolean force      = SubcommandParser.booleanFlag(parsed, "--force");

        // UC04 watcher options.
        boolean noWatch = SubcommandParser.booleanFlag(parsed, "--no-watch");
        long debounceMs = WatcherConfig.DEFAULT_DEBOUNCE_MS;
        long restartDrainMs = WatcherConfig.DEFAULT_RESTART_DRAIN_MS;
        long logMaxSizeBytes = WatcherConfig.DEFAULT_LOG_MAX_SIZE_BYTES;
        int  logMaxFiles = WatcherConfig.DEFAULT_LOG_MAX_FILES;
        Path logFileOverride = null;
        try {
            String s = SubcommandParser.firstOrNull(parsed, "--watch-debounce-ms");
            if (s != null) debounceMs = Long.parseLong(s);
            s = SubcommandParser.firstOrNull(parsed, "--restart-drain-ms");
            if (s != null) restartDrainMs = Long.parseLong(s);
            s = SubcommandParser.firstOrNull(parsed, "--log-max-size-mb");
            if (s != null) logMaxSizeBytes = Long.parseLong(s) * 1024L * 1024L;
            s = SubcommandParser.firstOrNull(parsed, "--log-max-files");
            if (s != null) logMaxFiles = Integer.parseInt(s);
            s = SubcommandParser.firstOrNull(parsed, "--log-file");
            if (s != null) logFileOverride = Path.of(s);
        } catch (NumberFormatException nfe) {
            return printErrorAndExit("bad-request",
                    "numeric watcher flag is malformed: " + nfe.getMessage(), 2);
        }

        ScopeConfig scope = ScopeConfig.of(classpath, sources, include, exclude);
        Discovery discovery = Discovery.defaults(Daemon.DAEMON_VERSION);
        Path effectiveLog = logFileOverride != null
                ? logFileOverride : discovery.logPath(scope.projectHash());
        WatcherConfig watcherConfig = new WatcherConfig(!noWatch, debounceMs, restartDrainMs,
                effectiveLog, logMaxSizeBytes, logMaxFiles);

        if (foreground || Daemonization.wasInternallySpawned(Arrays.asList(args))) {
            try {
                LaunchArgs launchArgs = new LaunchArgs(scope, idleMinutes, foreground, watcherConfig);
                Daemon daemon = Daemon.startForScope(launchArgs, discovery, force);
                JsonObject out = new JsonObject();
                out.addProperty("status", "started");
                out.addProperty("project_hash", daemon.projectHash());
                out.addProperty("pid", ProcessHandle.current().pid());
                out.addProperty("port", daemon.tcpPort());
                System.err.println(GSON.toJson(out));
                // Foreground mode: park forever (until shutdown hook fires).
                blockUntilShutdown();
                return 0;
            } catch (Discovery.AlreadyRunningException are) {
                return printErrorAndExit("already-running",
                        "another daemon owns this scope: pid=" + are.pid
                                + " port=" + are.port, 3);
            } catch (IOException ioe) {
                return printErrorAndExit("internal-error",
                        "daemon failed to start: " + ioe.getMessage(), 1);
            }
        }

        // Background mode: spawn child JVM and wait for discovery file.
        try {
            Discovery.Record rec = Daemonization.spawnBackgroundAndAwait(scope, discovery,
                    Arrays.asList(args));
            JsonObject out = new JsonObject();
            out.addProperty("status", "started");
            out.addProperty("project_hash", rec.projectHash());
            out.addProperty("pid", rec.pid());
            out.addProperty("port", rec.port());
            System.out.println(GSON.toJson(out));
            return 0;
        } catch (IOException ioe) {
            return printErrorAndExit("internal-error",
                    "background daemon launch failed: " + ioe.getMessage(), 1);
        }
    }

    private static void blockUntilShutdown() {
        // Park forever; the JVM shutdown hook handles cleanup.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static int runDaemonStop(String[] args) {
        SubcommandParser p = new SubcommandParser("daemon stop",
                Set.of("--project"), Set.of(), Set.of());
        Map<String, List<String>> parsed = p.parse(args);
        String project = SubcommandParser.firstOrThrow(parsed, "--project");

        Discovery.Record rec = locateDaemonOrFail(project);
        if (rec == null) return 4;

        JsonObject req = new JsonObject();
        req.addProperty("op", "shutdown");
        JsonObject envelope = sendOnLoopback(rec.port(), req);
        if (envelope == null) {
            return printErrorAndExit("no-daemon",
                    "daemon at port " + rec.port() + " did not respond", 4);
        }
        // Wait a moment for the discovery file to disappear.
        long deadline = System.currentTimeMillis() + 5_000;
        Path discoveryFile = Discovery.defaults(Daemon.DAEMON_VERSION)
                .filePath(rec.projectHash());
        while (System.currentTimeMillis() < deadline && Files.exists(discoveryFile)) {
            try { Thread.sleep(50); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println(GSON.toJson(envelope));
        return 0;
    }

    // -----------------------------------------------------------------------
    // Query subcommands
    // -----------------------------------------------------------------------

    private static int runQueryNoArgs(String op, String[] args) {
        SubcommandParser p = new SubcommandParser(op,
                Set.of("--project"), Set.of(), Set.of());
        Map<String, List<String>> parsed = p.parse(args);
        String project = SubcommandParser.firstOrThrow(parsed, "--project");
        return sendQuery(project, op, new JsonObject());
    }

    private static int runFindTree(String op, String[] args) {
        SubcommandParser p = new SubcommandParser(op,
                Set.of("--project", "--method", "--depth"), Set.of(), Set.of());
        Map<String, List<String>> parsed = p.parse(args);
        String project = SubcommandParser.firstOrThrow(parsed, "--project");
        String method  = SubcommandParser.firstOrThrow(parsed, "--method");
        String depth   = SubcommandParser.firstOrNull(parsed, "--depth");

        JsonObject opArgs = new JsonObject();
        opArgs.addProperty("method_fqn", method);
        if (depth != null) {
            try {
                opArgs.addProperty("depth", Integer.parseInt(depth));
            } catch (NumberFormatException nfe) {
                return printErrorAndExit("bad-request", "--depth must be an integer", 2);
            }
        }
        return sendQuery(project, op, opArgs);
    }

    private static int runMethodsInClass(String[] args) {
        SubcommandParser p = new SubcommandParser("methods-in-class",
                Set.of("--project", "--class"), Set.of(), Set.of());
        Map<String, List<String>> parsed = p.parse(args);
        String project = SubcommandParser.firstOrThrow(parsed, "--project");
        String classFqn = SubcommandParser.firstOrThrow(parsed, "--class");

        JsonObject opArgs = new JsonObject();
        opArgs.addProperty("class_fqn", classFqn);
        return sendQuery(project, "methods-in-class", opArgs);
    }

    private static int runMethodsAtLine(String[] args) {
        SubcommandParser p = new SubcommandParser("methods-at-line",
                Set.of("--project", "--source-file", "--line"), Set.of(), Set.of());
        Map<String, List<String>> parsed = p.parse(args);
        String project = SubcommandParser.firstOrThrow(parsed, "--project");
        String src = SubcommandParser.firstOrThrow(parsed, "--source-file");
        String line = SubcommandParser.firstOrThrow(parsed, "--line");

        JsonObject opArgs = new JsonObject();
        opArgs.addProperty("source_file", src);
        try {
            opArgs.addProperty("line", Integer.parseInt(line));
        } catch (NumberFormatException nfe) {
            return printErrorAndExit("bad-request", "--line must be an integer", 2);
        }
        return sendQuery(project, "methods-at-line", opArgs);
    }

    private static int runFindField(String op, String[] args) {
        SubcommandParser p = new SubcommandParser(op,
                Set.of("--project", "--field"), Set.of(), Set.of());
        Map<String, List<String>> parsed = p.parse(args);
        String project = SubcommandParser.firstOrThrow(parsed, "--project");
        String field   = SubcommandParser.firstOrThrow(parsed, "--field");
        JsonObject opArgs = new JsonObject();
        opArgs.addProperty("field_fqn", field);
        return sendQuery(project, op, opArgs);
    }

    private static int runDiffOp(String op, String[] args) {
        SubcommandParser p = new SubcommandParser(op,
                Set.of("--project", "--diff", "--diff-stdin"),
                Set.of(), Set.of("--diff-stdin"));
        Map<String, List<String>> parsed = p.parse(args);
        String project = SubcommandParser.firstOrThrow(parsed, "--project");
        String diffFile = SubcommandParser.firstOrNull(parsed, "--diff");
        boolean stdin   = SubcommandParser.booleanFlag(parsed, "--diff-stdin");

        if ((diffFile == null && !stdin) || (diffFile != null && stdin)) {
            return printErrorAndExit("bad-request",
                    "exactly one of --diff <file> or --diff-stdin is required", 2);
        }

        String diffText;
        try {
            if (stdin) {
                diffText = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            } else {
                diffText = Files.readString(Path.of(diffFile), StandardCharsets.UTF_8);
            }
        } catch (IOException ioe) {
            return printErrorAndExit("internal-error",
                    "failed to read diff input: " + ioe.getMessage(), 1);
        }
        JsonObject opArgs = new JsonObject();
        opArgs.addProperty("diff_text", diffText);
        return sendQuery(project, op, opArgs);
    }

    // -----------------------------------------------------------------------
    // Daemon discovery + wire
    // -----------------------------------------------------------------------

    /**
     * Locates the discovery file for the given {@code --project} value (either a
     * project hash or a path that hashes to one). Performs PID-alive check; on stale
     * deletes the file and prints {@code no-daemon}. On mismatch from ping, same path.
     */
    private static Discovery.Record locateDaemonOrFail(String projectArg) {
        Discovery discovery = Discovery.defaults(Daemon.DAEMON_VERSION);

        // Resolve discovery file from the argument.
        Path discoveryFile;
        String expectedHash;
        if (looksLikeHash(projectArg)) {
            expectedHash = projectArg;
            discoveryFile = discovery.filePath(expectedHash);
        } else {
            // Treat as a path -> hash it via ScopeConfig (classpath-only entry).
            ScopeConfig probe = ScopeConfig.of(List.of(Path.of(projectArg)), List.of(), List.of(), List.of());
            expectedHash = probe.projectHash();
            discoveryFile = discovery.filePath(expectedHash);
        }

        if (!Files.exists(discoveryFile)) {
            printErrorAndExit("no-daemon",
                    "no daemon running for this project (no discovery file at "
                            + discoveryFile + ") — run `daemon start ...`", 4);
            return null;
        }
        Discovery.Record rec = Discovery.read(discoveryFile).orElse(null);
        if (rec == null) {
            printErrorAndExit("no-daemon",
                    "discovery file at " + discoveryFile + " is malformed; delete it and re-run `daemon start ...`",
                    4);
            return null;
        }
        if (!Discovery.pidAlive(rec.pid())) {
            try { Files.deleteIfExists(discoveryFile); } catch (IOException ignored) {}
            printErrorAndExit("no-daemon",
                    "stale discovery file removed (pid " + rec.pid()
                            + " is dead) — run `daemon start ...`", 4);
            return null;
        }
        // Sanity-check via ping; verify project hash matches.
        JsonObject ping = sendOnLoopback(rec.port(), pingRequest());
        if (ping == null
                || !ping.has("ok") || !ping.get("ok").getAsBoolean()
                || !ping.has("result")
                || !ping.getAsJsonObject("result").has("project_hash")
                || !rec.projectHash().equals(
                        ping.getAsJsonObject("result").get("project_hash").getAsString())) {
            try { Files.deleteIfExists(discoveryFile); } catch (IOException ignored) {}
            printErrorAndExit("no-daemon",
                    "discovery file pointed at port " + rec.port()
                            + " but ping mismatched — file removed; run `daemon start ...`", 4);
            return null;
        }
        return rec;
    }

    private static JsonObject pingRequest() {
        JsonObject obj = new JsonObject();
        obj.addProperty("op", "ping");
        return obj;
    }

    private static boolean looksLikeHash(String s) {
        if (s == null) return false;
        if (s.length() != 16) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    /** Sends a query op to the daemon located via {@code --project}. */
    private static int sendQuery(String projectArg, String op, JsonObject opArgs) {
        Discovery.Record rec = locateDaemonOrFail(projectArg);
        if (rec == null) return 4;

        JsonObject req = new JsonObject();
        req.addProperty("op", op);
        req.add("args", opArgs);

        JsonObject envelope = sendOnLoopback(rec.port(), req);
        if (envelope == null) {
            return printErrorAndExit("no-daemon",
                    "daemon at port " + rec.port() + " did not respond", 4);
        }
        if (envelope.has("ok") && envelope.get("ok").getAsBoolean()) {
            JsonElement result = envelope.get("result");
            System.out.println(GSON.toJson(result == null ? new JsonObject() : result));
            return 0;
        }
        JsonObject errObj = new JsonObject();
        errObj.add("error",  envelope.get("error"));
        errObj.add("detail", envelope.get("detail"));
        System.out.println(GSON.toJson(errObj));
        return 1;
    }

    /**
     * Opens a single TCP connection to the loopback port, writes one request line,
     * reads one response line, closes. Returns the parsed envelope or {@code null} on
     * I/O error.
     */
    private static JsonObject sendOnLoopback(int port, JsonObject request) {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            s.setSoTimeout(30_000);
            out.write(new Gson().toJson(request));
            out.write('\n');
            out.flush();
            String line = in.readLine();
            if (line == null) return null;
            JsonElement el = JsonParser.parseString(line);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (IOException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Errors
    // -----------------------------------------------------------------------

    private static int printErrorAndExit(String code, String detail, int exitCode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", code);
        obj.addProperty("detail", detail);
        System.out.println(GSON.toJson(obj));
        return exitCode;
    }

    /** Set of subcommand tokens recognised by the new dispatcher. */
    public static Set<String> recognisedSubcommands() {
        return Set.copyOf(new LinkedHashSet<>(SUBCOMMANDS));
    }

    /** Returns a {@link List<String>} version of recognisedSubcommands for suggestion lookups. */
    public static List<String> recognisedSubcommandsAsList() {
        return new ArrayList<>(SUBCOMMANDS);
    }
}

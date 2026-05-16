package com.hhg.callgraph.daemon.watch;

import com.hhg.callgraph.daemon.ScopeConfig;

/**
 * Captured at {@code daemon start} parse time and held on the {@code Daemon} for
 * self-restart reuse (UC04 §10). {@code foreground} is preserved so a build-config
 * triggered self-restart doesn't accidentally back-fork.
 */
public record LaunchArgs(ScopeConfig scope,
                         int idleMinutes,
                         boolean foreground,
                         WatcherConfig watcherConfig) {
}

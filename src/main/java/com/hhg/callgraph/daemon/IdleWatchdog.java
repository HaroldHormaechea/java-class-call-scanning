package com.hhg.callgraph.daemon;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in auto-shutdown. Default: disabled. When enabled via {@code --idle-timeout N}
 * (minutes), fires every {@code max(1s, min(30s, N*60s/4))} and triggers shutdown
 * when {@code lastActivityNanos + idleNanos < now}.
 */
public final class IdleWatchdog {

    private final long idleNanos;
    private final long pollIntervalSeconds;
    private final Runnable onIdle;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong lastActivityNanos;

    public IdleWatchdog(int idleMinutes, Runnable onIdle) {
        if (idleMinutes <= 0) {
            throw new IllegalArgumentException("idleMinutes must be > 0");
        }
        this.idleNanos = TimeUnit.MINUTES.toNanos(idleMinutes);

        long quarterSeconds = (idleMinutes * 60L) / 4L;
        if (quarterSeconds < 1)  quarterSeconds = 1;
        if (quarterSeconds > 30) quarterSeconds = 30;
        this.pollIntervalSeconds = quarterSeconds;

        this.onIdle = onIdle;
        this.lastActivityNanos = new AtomicLong(System.nanoTime());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idle-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick,
                pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);
    }

    public void bump() {
        lastActivityNanos.set(System.nanoTime());
    }

    public void shutdownPoller() {
        scheduler.shutdownNow();
    }

    private void tick() {
        long now = System.nanoTime();
        long last = lastActivityNanos.get();
        if (now - last >= idleNanos) {
            try {
                onIdle.run();
            } catch (Throwable t) {
                System.err.println("idle-watchdog: shutdown handler threw: " + t);
            }
        }
    }
}

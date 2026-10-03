package io.jafar.mcp.lifecycle;

import java.time.Duration;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/**
 * Decides when an auto-started SSE daemon should exit: once it has had no connected client for the
 * whole timeout.
 *
 * <p>It counts connected sessions, not tool calls. A daemon with zero live SSE sessions has nobody
 * to strand, which is why this is safe where the stdio-oriented activity watchdog is not (see the
 * note in {@code JafarMcpServer.runSse()}). The idle clock starts when the watchdog is created, so
 * a daemon that nobody ever connects to also exits.
 */
public final class IdleExitWatchdog {

  private final IntSupplier activeSessions;
  private final long timeoutNanos;
  private final LongSupplier nanoClock;
  private Long idleSinceNanos;

  public IdleExitWatchdog(IntSupplier activeSessions, Duration timeout, LongSupplier nanoClock) {
    this.activeSessions = activeSessions;
    this.timeoutNanos = timeout.toNanos();
    this.nanoClock = nanoClock;
    this.idleSinceNanos = nanoClock.getAsLong();
  }

  /** Returns true once no session has been connected for the full timeout. */
  public synchronized boolean shouldExit() {
    long now = nanoClock.getAsLong();
    if (activeSessions.getAsInt() > 0) {
      idleSinceNanos = null;
      return false;
    }
    if (idleSinceNanos == null) {
      idleSinceNanos = now;
    }
    return now - idleSinceNanos >= timeoutNanos;
  }

  /** Polls {@code watchdog} on a daemon thread and runs {@code onIdle} once, when it says exit. */
  public static Thread start(
      IdleExitWatchdog watchdog, Duration pollInterval, Runnable onIdle, Logger logger) {
    Thread thread =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                if (watchdog.shouldExit()) {
                  logger.info(
                      "No connected client for {}m, shutting down the auto-started daemon",
                      Duration.ofNanos(watchdog.timeoutNanos).toMinutes());
                  onIdle.run();
                  return;
                }
              }
            },
            "mcp-daemon-idle-exit");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }
}

package io.jafar.mcp.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class IdleExitWatchdogTest {

  private static final Duration TIMEOUT = Duration.ofMinutes(30);

  private final AtomicInteger sessions = new AtomicInteger();
  private final AtomicLong nanos = new AtomicLong();
  private final IdleExitWatchdog watchdog =
      new IdleExitWatchdog(sessions::get, TIMEOUT, nanos::get);

  private void advance(Duration d) {
    nanos.addAndGet(d.toNanos());
  }

  @Test
  void doesNotExitBeforeTheTimeout() {
    advance(TIMEOUT.minusSeconds(1));

    assertFalse(watchdog.shouldExit());
  }

  @Test
  void exitsOnceIdleForTheTimeout() {
    advance(TIMEOUT);

    assertTrue(watchdog.shouldExit());
  }

  @Test
  void aConnectedSessionPreventsExitHoweverLongItLasts() {
    sessions.set(1);
    advance(TIMEOUT.multipliedBy(10));

    assertFalse(watchdog.shouldExit());
  }

  @Test
  void idleClockRestartsWhenTheLastSessionDisconnects() {
    sessions.set(1);
    advance(TIMEOUT.multipliedBy(2));
    assertFalse(watchdog.shouldExit());

    sessions.set(0);
    assertFalse(watchdog.shouldExit(), "clock starts at the first idle observation");
    advance(TIMEOUT.minusSeconds(1));
    assertFalse(watchdog.shouldExit());
    advance(Duration.ofSeconds(1));
    assertTrue(watchdog.shouldExit());
  }

  @Test
  void aReconnectDuringTheIdleWindowResetsIt() {
    advance(TIMEOUT.minusSeconds(1));
    assertFalse(watchdog.shouldExit());

    sessions.set(1);
    assertFalse(watchdog.shouldExit());
    sessions.set(0);
    advance(TIMEOUT.minusSeconds(1));

    assertFalse(watchdog.shouldExit(), "the earlier idle time must not carry over");
  }
}

package io.jafar.mcp.session;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asks the JVM to unmap the files of sessions that were just closed.
 *
 * <p>Recordings and heap dumps are read through memory-mapped buffers. Closing a session drops the
 * session's references to them, but the JDK unmaps a buffer only when the garbage collector has
 * found it unreachable and its cleaner has run. Until then, Windows refuses to delete, move or
 * overwrite the file (#227). The mapping cannot be released at close time: event values resolve
 * constant-pool entries from the buffer lazily, so after an explicit unmap a late read could crash
 * the JVM.
 *
 * <p>{@link #afterClose()} therefore requests a collection, and a follow-up one {@link
 * #FOLLOW_UP_MILLIS} after the last request (#279: a closed file stayed mapped after the first;
 * that was not reproduced on demand, so the follow-up covers a buffer still held briefly while the
 * first ran). They are requests: with {@code -XX:+DisableExplicitGC} they do nothing, and the
 * cleaner thread unmaps the buffers shortly after a collection, not before this method returns.
 */
public final class MappedFileRelease {

  /** How long after the last request the follow-up collection is requested. */
  static final long FOLLOW_UP_MILLIS = 500;

  private static final long FOLLOW_UP_NANOS =
      java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(FOLLOW_UP_MILLIS);

  /** Whether a follow-up thread is waiting. */
  private static final AtomicBoolean FOLLOW_UP_PENDING = new AtomicBoolean();

  /** {@link System#nanoTime()} of the last request. */
  private static final AtomicLong LAST_REQUEST = new AtomicLong();

  /** The follow-up collections requested so far; for tests. */
  static final AtomicLong FOLLOW_UPS = new AtomicLong();

  /** Follow-up threads not yet ended; for tests. */
  private static final java.util.concurrent.atomic.AtomicInteger IN_FLIGHT =
      new java.util.concurrent.atomic.AtomicInteger();

  private MappedFileRelease() {}

  /** Whether a follow-up is waiting or running; for tests. */
  static boolean followUpPending() {
    return FOLLOW_UP_PENDING.get() || IN_FLIGHT.get() > 0;
  }

  /**
   * Requests a garbage collection so the closed sessions' mapped buffers are unmapped, and a
   * follow-up one {@link #FOLLOW_UP_MILLIS} after the last of a burst of requests. Call it outside
   * any lock: a full collection on a large heap can take a while.
   */
  public static void afterClose() {
    System.gc();
    // Before the flag: a follow-up thread that sees the flag set also sees this request's time
    // The latest time wins: a thread that read the clock earlier but wrote later must not move it
    // back
    LAST_REQUEST.accumulateAndGet(System.nanoTime(), Math::max);
    if (FOLLOW_UP_PENDING.compareAndSet(false, true)) {
      IN_FLIGHT.incrementAndGet();
      Thread followUp =
          new Thread(
              () -> {
                try {
                  followUp();
                } finally {
                  IN_FLIGHT.decrementAndGet();
                }
              },
              "jfr-mcp-mapped-file-release");
      followUp.setDaemon(true);
      followUp.start();
    }
  }

  private static void followUp() {
    while (true) {
      long left;
      while ((left = LAST_REQUEST.get() + FOLLOW_UP_NANOS - System.nanoTime()) > 0) {
        try {
          java.util.concurrent.TimeUnit.NANOSECONDS.sleep(left);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      FOLLOW_UP_PENDING.set(false);
      // A request made after the last check found the flag set and relies on this thread
      if (Thread.currentThread().isInterrupted()
          || System.nanoTime() - LAST_REQUEST.get() >= FOLLOW_UP_NANOS) {
        break;
      }
      if (!FOLLOW_UP_PENDING.compareAndSet(false, true)) {
        return; // a newer request started its own follow-up
      }
    }
    System.gc();
    FOLLOW_UPS.incrementAndGet();
  }
}

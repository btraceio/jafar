package io.jafar.mcp.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #279: one collection right after close could run while something still held a closed file's
 * buffer for a moment; nothing collected again, and the file stayed mapped. afterClose() requests a
 * follow-up collection {@link MappedFileRelease#FOLLOW_UP_MILLIS} after the last request.
 */
class MappedFileReleaseTest {

  private static long collections() {
    long n = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      n += Math.max(0, gc.getCollectionCount());
    }
    return n;
  }

  /** Waits for a follow-up after {@code count}; returns when it came, in System.nanoTime(). */
  private static long awaitFollowUp(long count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (MappedFileRelease.FOLLOW_UPS.get() == count && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(MappedFileRelease.FOLLOW_UPS.get() > count, "a follow-up collection was requested");
    return System.nanoTime();
  }

  private static long millisSince(long start, long end) {
    return TimeUnit.NANOSECONDS.toMillis(end - start);
  }

  @BeforeEach
  void noFollowUpPending() throws InterruptedException {
    // A follow-up left by an earlier test's close would count as this test's
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (MappedFileRelease.followUpPending() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(!MappedFileRelease.followUpPending(), "a follow-up is still pending");
  }

  @Test
  void requestsAFollowUpCollectionAfterTheDelay() throws Exception {
    long followUps = MappedFileRelease.FOLLOW_UPS.get();
    long before = collections();
    long requested = System.nanoTime();
    MappedFileRelease.afterClose();
    assertTrue(collections() > before, "the first collection ran");

    long came = awaitFollowUp(followUps);
    assertTrue(
        millisSince(requested, came) >= MappedFileRelease.FOLLOW_UP_MILLIS,
        "follow-up after " + millisSince(requested, came) + " ms");
  }

  @Test
  void aLaterRequestPostponesTheFollowUp() throws Exception {
    long followUps = MappedFileRelease.FOLLOW_UPS.get();
    MappedFileRelease.afterClose();
    Thread.sleep(MappedFileRelease.FOLLOW_UP_MILLIS / 2);
    long last = System.nanoTime();
    MappedFileRelease.afterClose();

    long came = awaitFollowUp(followUps);
    assertTrue(
        millisSince(last, came) >= MappedFileRelease.FOLLOW_UP_MILLIS,
        "follow-up " + millisSince(last, came) + " ms after the last request");
  }
}

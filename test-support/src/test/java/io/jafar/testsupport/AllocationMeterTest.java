package io.jafar.testsupport;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AllocationMeterTest {

  /**
   * (1) A pool thread that allocates a MiB and marks itself must be counted: stop() returns at
   * least that MiB. The allocation is published through an AtomicReference and read back after the
   * join, so it cannot be optimized away and the test cannot pass vacuously.
   */
  @Test
  void markedPoolThreadBytesAreCounted() throws Exception {
    final AllocationMeter meter = AllocationMeter.start();
    final AtomicReference<byte[]> payload = new AtomicReference<byte[]>();
    final AtomicReference<Throwable> threadFailure = new AtomicReference<Throwable>();
    Thread poolThread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  byte[] allocated = new byte[1 << 20];
                  allocated[allocated.length - 1] = 1;
                  meter.markCurrentThread();
                  payload.set(allocated);
                } catch (Throwable t) {
                  threadFailure.set(t);
                }
              }
            });
    poolThread.start();
    poolThread.join();
    if (threadFailure.get() != null) {
      throw new AssertionError("pool thread failed", threadFailure.get());
    }
    if (payload.get()[payload.get().length - 1] != 1) {
      throw new AssertionError("the pool-thread allocation never materialized");
    }
    long total = meter.stop();
    assertTrue(
        total >= (1 << 20),
        "expected the marked thread's 1 MiB to be counted, but stop() reported " + total);
  }

  /**
   * (2) A thread that allocates but never marks is not counted — pool-thread accounting is opt-in
   * through markCurrentThread(). The mark issued on the meter's own calling thread must be ignored
   * as well, leaving no pool thread ids at all.
   */
  @Test
  void unmarkedThreadBytesAreNotCounted() throws Exception {
    final AllocationMeter meter = AllocationMeter.start();
    // The calling thread's mark must be ignored (its bytes come from the stop() delta)
    meter.markCurrentThread();
    final AtomicReference<byte[]> payload = new AtomicReference<byte[]>();
    Thread unmarkedThread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                byte[] allocated = new byte[1 << 20];
                allocated[0] = 1;
                payload.set(allocated);
              }
            });
    unmarkedThread.start();
    unmarkedThread.join();
    if (payload.get()[0] != 1) {
      throw new AssertionError("the other thread's allocation never materialized");
    }
    long total = meter.stop();
    assertTrue(
        total < (1 << 20),
        "expected the unmarked thread's 1 MiB to be excluded, but stop() reported " + total);
    assertTrue(
        meter.poolThreadIds().isEmpty(),
        "no thread was marked, but poolThreadIds() reported " + meter.poolThreadIds());
  }

  /**
   * (3) Permanent fire test (R5): the freshness guard fails on an overlapping thread id, with the
   * message that names the invalidity of absolute accounting, and lets disjoint sets pass.
   */
  @Test
  void freshPoolThreadsAssertionFiresOnOverlap() {
    Set<Long> previous = setOf(1L, 2L);
    Set<Long> now = setOf(2L, 3L);
    AssertionError failure =
        assertThrows(
            AssertionError.class, () -> AllocationMeter.assertFreshPoolThreads(previous, now));
    assertTrue(
        failure.getMessage().contains("pool thread reused — absolute accounting invalid"),
        "expected the freshness failure to name the reason, got: " + failure.getMessage());
    assertTrue(
        failure.getMessage().contains("2"),
        "expected the freshness failure to name the reused id, got: " + failure.getMessage());
    // Disjoint sets are fresh and must pass
    AllocationMeter.assertFreshPoolThreads(setOf(1L, 2L), setOf(4L, 5L));
    AllocationMeter.assertFreshPoolThreads(
        Collections.<Long>emptySet(), Collections.<Long>emptySet());
  }

  private static Set<Long> setOf(Long... ids) {
    Set<Long> set = new HashSet<Long>();
    Collections.addAll(set, ids);
    return set;
  }
}

package io.jafar.testsupport;

import static org.junit.jupiter.api.Assertions.fail;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Measures the bytes allocated by one parse: the calling thread's allocation delta plus the
 * absolute allocated-bytes counter of every pool thread that took part.
 *
 * <p>Why pool threads need their own accounting: the parsers run chunk callbacks on a small pool of
 * worker threads that is fresh per {@code StreamingChunkParser} — the threads die when the parse
 * ends, and {@code getThreadAllocatedBytes(long)} reports {@code -1} for a dead thread. The counter
 * must therefore be read while the thread is alive, from the callback itself: call {@link
 * #markCurrentThread()} from {@code onChunkEnd} (or, when that listener is not delivered, from the
 * event handler on the same thread).
 *
 * <p>The meter must not perturb what it measures: after its first invocation on a thread, {@link
 * #markCurrentThread()} allocates nothing. The only per-thread allocation is the single cell
 * inserted by {@code computeIfAbsent} (plus the boxed id and the ThreadLocal slot that reach it),
 * never anything per callback.
 *
 * <p>Pool-thread values are lifetime absolutes, not deltas; that is valid only because the pool
 * threads are fresh per parse. {@link #assertFreshPoolThreads(Set, Set)} guards that assumption: if
 * any thread id repeats across two measurements, the absolute counter carries bytes from the
 * earlier parse and the accounting is invalid.
 */
public final class AllocationMeter {

  /**
   * The platform bean behind {@link java.lang.management.ThreadMXBean}, extended with per-thread
   * allocated-bytes. Only HotSpot-family JVMs provide it; a JVM without it cannot be measured and
   * the meter refuses to run rather than silently reporting zero (R3: report where a value came
   * from).
   */
  private static final ThreadMXBean THREAD_BEAN = initThreadBean();

  /** Non-capturing lambda: the JVM instantiates it once, so it never allocates per call. */
  private static final Function<Long, long[]> NEW_CELL = key -> new long[1];

  private final long callerId;
  private final long callerAllocatedAtStart;

  /**
   * Pool thread id → single-element cell holding that thread's maximum reading of the absolute
   * allocated-bytes counter. The counter is monotonic per live thread, so the maximum is the latest
   * read; taking the max also stays correct should a reading arrive out of order.
   *
   * <p>Each cell is synchronized by both writer (the pool thread, in {@link #markCurrentThread()})
   * and reader ({@link #stop()} on the calling thread): the plain {@code long[]} shape is mandated
   * by the meter's allocation discipline, and the monitor supplies the cross-thread visibility the
   * array cannot.
   */
  private final ConcurrentHashMap<Long, long[]> poolBytes = new ConcurrentHashMap<Long, long[]>();

  /**
   * Per-thread cache of this thread's cell, so repeated marks skip the map lookup (and the id
   * boxing it would do). Cleared never; the meter is single-use, start() → stop().
   */
  private final ThreadLocal<long[]> cellCache = new ThreadLocal<long[]>();

  private AllocationMeter() {
    this.callerId = Thread.currentThread().getId();
    this.callerAllocatedAtStart = THREAD_BEAN.getThreadAllocatedBytes(callerId);
  }

  /**
   * Starts measuring on the calling thread. Call from the thread that will drive the parse (the one
   * calling the parser's public entry point), before any warm-up is done — warm-up parses must
   * happen outside the meter, not inside (the first parse allocates ~14x the steady state through
   * lazy caches).
   */
  public static AllocationMeter start() {
    return new AllocationMeter();
  }

  /**
   * Records the absolute allocated-bytes counter of the calling thread. Call this on a pool thread,
   * from a callback delivered while that thread is alive ({@code onChunkEnd}); after the thread
   * dies the counter reads {@code -1} and the value is lost. The thread that called {@link
   * #start()} is ignored — its bytes are accounted by the {@link #stop()} delta.
   *
   * <p>Allocates exactly once per thread (the map cell, via {@code computeIfAbsent}); repeated
   * calls on the same thread allocate nothing.
   */
  public void markCurrentThread() {
    long id = Thread.currentThread().getId();
    if (id == callerId) {
      return; // the calling thread's bytes are measured by the stop() delta
    }
    long allocated = THREAD_BEAN.getThreadAllocatedBytes(id);
    long[] cell = cellCache.get();
    if (cell == null) {
      cell = poolBytes.computeIfAbsent(id, NEW_CELL);
      cellCache.set(cell);
    }
    synchronized (cell) {
      if (allocated > cell[0]) {
        cell[0] = allocated;
      }
    }
  }

  /**
   * Stops measuring and returns the calling thread's allocation delta plus the sum of every marked
   * pool thread's absolute value. Call after the parse has fully returned, so no pool thread can
   * still update a cell.
   */
  public long stop() {
    // Read the calling thread's counter first, so the delta excludes stop()'s own overhead
    // (the map iteration below); pool-thread cells are not affected either way.
    long now = THREAD_BEAN.getThreadAllocatedBytes(callerId);
    long total = now - callerAllocatedAtStart;
    for (long[] cell : poolBytes.values()) {
      long value;
      synchronized (cell) {
        value = cell[0];
      }
      total += value;
    }
    return total;
  }

  /** The ids of the pool threads that called {@link #markCurrentThread()} on this meter. */
  public Set<Long> poolThreadIds() {
    return new HashSet<Long>(poolBytes.keySet());
  }

  /**
   * Asserts that no pool thread id repeats across two measurements. Pool-thread accounting uses
   * lifetime absolute counters, which is only valid for threads that are fresh per parse; a reused
   * id means the counter carries bytes from an earlier parse.
   *
   * @param previous the pool thread ids of an earlier measurement
   * @param now the pool thread ids of the current measurement
   */
  public static void assertFreshPoolThreads(Set<Long> previous, Set<Long> now) {
    Set<Long> reused = new HashSet<Long>(previous);
    reused.retainAll(now);
    if (!reused.isEmpty()) {
      fail(
          "pool thread reused — absolute accounting invalid: thread ids "
              + reused
              + " appear in both measurements");
    }
  }

  private static ThreadMXBean initThreadBean() {
    ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!bean.isThreadAllocatedMemorySupported()) {
      throw new AssertionError(
          "AllocationMeter: this JVM's ThreadMXBean does not support per-thread allocated-bytes"
              + " (com.sun.management.ThreadMXBean required); refusing to report a bogus zero");
    }
    if (!bean.isThreadAllocatedMemoryEnabled()) {
      bean.setThreadAllocatedMemoryEnabled(true);
    }
    return bean;
  }
}

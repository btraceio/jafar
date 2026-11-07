package io.jafar.parser.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.management.ThreadMXBean;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.testsupport.AllocationBudgets;
import io.jafar.testsupport.AllocationMeter;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Allocation budget of the untyped entry point ({@link ParsingContext#create()} + {@code
 * newUntypedParser}) on {@code tck-test.jfr} (AC-3.1).
 *
 * <p>The measured bytes cover the whole parse, not just the calling thread's orchestration
 * (AC-3.2): chunk decode runs on {@code StreamingChunkParser}'s pool threads, which are fresh per
 * parse, so their absolute allocated-bytes counters are per-parse values. They are read on the
 * threads themselves, from {@code withParserListener}'s {@code onChunkEnd} — delivered on the pool
 * thread that parsed the chunk, while it is alive (a dead thread reports {@code -1}).
 *
 * <p>The chunk pool is pinned to 2 threads (one per chunk of {@code tck-test.jfr}) by the {@code
 * -XX:ActiveProcessorCount=4} JVM arg of the {@code allocationTest} task; without the pin the pool
 * size would follow the host CPU count and the thread-to-chunk mapping would vary (D9).
 *
 * <p>Determinism (AC-3.3, kb/a4-jdk8-alloc): the first parse pays ~14x the steady state in lazy
 * initialization and JDK 21 has one more lazy step even after it, so two warm-up parses are
 * discarded before the measured parse.
 */
class AllocationBudgetTest {

  /**
   * Real Datadog profiler recording, checked in (no {@code get_resources.sh} download): 67,657
   * events in 2 chunks. The event count is asserted so a fixture swap or a parser regression that
   * drops events cannot hide behind a smaller allocation figure.
   */
  private static final String RECORDING = "../jfr-shell-tck/src/main/resources/tck-test.jfr";

  /** Budget key without the JDK suffix; {@link AllocationBudgets#check} appends {@code jdk21}. */
  private static final String KEY = "parser-core.untyped.tck-test.jfr";

  private static final int EVENTS = 67657;

  @Test
  void untypedParseStaysWithinAllocationBudget() throws Exception {
    // Two discarded warm-up parses: the first pays the lazy parser/context initialization (~14x
    // steady state), the second one more lazy step observed on JDK 21 (kb/a4-jdk8-alloc).
    Set<Long> previousPoolThreads = new HashSet<>();
    for (int i = 0; i < 2; i++) {
      AllocationMeter warmUpMeter = AllocationMeter.start();
      assertEquals(EVENTS, parse(warmUpMeter), "warm-up parse " + i + ": tck-test.jfr changed?");
      warmUpMeter.stop(); // discarded: lazy-init bytes, never compared against the budget
      previousPoolThreads.addAll(warmUpMeter.poolThreadIds());
    }

    ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long callerBefore = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
    AllocationMeter meter = AllocationMeter.start();
    int events = parse(meter);
    long bytes = meter.stop();
    long callingThreadOnly =
        threadBean.getThreadAllocatedBytes(Thread.currentThread().getId()) - callerBefore;
    // Printed on every run: the pool share is where per-event decode regressions land, so a
    // meter watching only the calling thread would miss them (AC-3.2; the recorded R5 mutation
    // run uses this line to show exactly that blindness). The thread count shows how many of
    // the pinned pool's 2 threads actually took a chunk of this recording.
    System.out.println(
        "alloc "
            + KEY
            + "."
            + AllocationBudgets.jdkKey()
            + " aggregate="
            + bytes
            + " callingThreadOnly="
            + callingThreadOnly
            + " pool="
            + (bytes - callingThreadOnly)
            + " poolThreads="
            + meter.poolThreadIds().size());

    assertEquals(EVENTS, events, "tck-test.jfr event count changed");
    Set<Long> poolThreads = meter.poolThreadIds();
    assertFalse(
        poolThreads.isEmpty(),
        "no pool thread was marked: withParserListener.onChunkEnd is not delivered on the pool"
            + " threads that parse the chunks, so the meter would see only the calling thread's"
            + " bytes (AC-3.2)");
    AllocationMeter.assertFreshPoolThreads(previousPoolThreads, poolThreads);
    AllocationBudgets.check(KEY, bytes);
  }

  /**
   * One untyped parse of the recording: a fresh {@link ParsingContext}, the event-counting handler
   * (allocation-free) and a parser listener that marks the chunk-pool threads. Returns the number
   * of events delivered to the handler.
   */
  private static int parse(AllocationMeter meter) throws Exception {
    AtomicInteger events = new AtomicInteger();
    ParsingContext context = ParsingContext.create();
    try (UntypedJafarParser parser =
        context
            .newUntypedParser(Paths.get(RECORDING))
            .withParserListener(
                new ChunkParserListener() {
                  @Override
                  public boolean onChunkEnd(ParserContext ctx, int chunkIndex, boolean skipped) {
                    // Runs on the pool thread that parsed the chunk (EventStream forwards the
                    // chunk callbacks there), while that thread is alive.
                    meter.markCurrentThread();
                    return true;
                  }
                })) {
      parser.handle((type, event, ctl) -> events.incrementAndGet());
      parser.run();
    }
    return events.get();
  }
}

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
 * JDK 8 twin of {@code parser-core/src/test/java/io/jafar/parser/alloc/AllocationBudgetTest.java} —
 * same measurement, same budget key minus the JDK suffix, Java 8 syntax only because the {@code
 * test8} source set is compiled with {@code --release 8} and run on the JDK 8 toolchain (D9).
 *
 * <p>Allocation differs per JDK build, not only per major version (kb/a4-jdk8-alloc), so the JDK 8
 * cell asserts its own key: {@code parser-core.untyped.tck-test.jfr.jdk8} (AC-3.5).
 */
class AllocationBudget8Test {

  /** Real Datadog profiler recording, checked in (no download): 67,657 events in 2 chunks. */
  private static final String RECORDING = "../jfr-shell-tck/src/main/resources/tck-test.jfr";

  /** Budget key without the JDK suffix; {@link AllocationBudgets#check} appends {@code jdk8}. */
  private static final String KEY = "parser-core.untyped.tck-test.jfr";

  private static final int EVENTS = 67657;

  @Test
  void untypedParseStaysWithinAllocationBudget() throws Exception {
    // Two discarded warm-up parses: the first pays the lazy parser/context initialization,
    // the second one more lazy step observed on JDK 21 (kb/a4-jdk8-alloc).
    Set<Long> previousPoolThreads = new HashSet<Long>();
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
    // meter watching only the calling thread would miss them (AC-3.2). The thread count shows
    // how many of the pinned pool's 2 threads actually took a chunk of this recording.
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
   * One untyped parse: a fresh {@link ParsingContext}, the counting handler, the marking listener.
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
                    // Runs on the pool thread that parsed the chunk, while it is alive.
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

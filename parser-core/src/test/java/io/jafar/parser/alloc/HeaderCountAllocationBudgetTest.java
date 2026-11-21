package io.jafar.parser.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.management.ThreadMXBean;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.impl.UntypedJafarParserImpl;
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
 * Allocation budget of counting events from their headers: the untyped parse of {@code
 * tck-test.jfr} with one header handler for every type ({@code handleHeaders(type -> true, ...)})
 * and no value handler, so no event is decoded; read with the generic reader ({@code EventStream};
 * {@code parser-codegen} is not on this classpath).
 *
 * <p>Same fixture, warm-ups, pool-thread accounting and JVM pins as {@code AllocationBudgetTest}.
 * The handler increments an {@code AtomicInteger}, so the figure is the parser's: reading 67,657
 * event headers and their {@code startTime}s. A regression that allocates per header shows here
 * with 67,657 events behind it.
 */
class HeaderCountAllocationBudgetTest {

  /**
   * Real Datadog profiler recording, checked in (no {@code get_resources.sh} download): 67,657
   * events in 2 chunks. The event count is asserted so a fixture swap or a parser regression that
   * drops events cannot hide behind a smaller allocation figure.
   */
  private static final String RECORDING = "../jfr-shell-tck/src/main/resources/tck-test.jfr";

  /** Budget key without the JDK suffix; {@link AllocationBudgets#check} appends {@code jdk21}. */
  private static final String KEY = "parser-core.untyped-headers.tck-test.jfr";

  private static final int EVENTS = 67657;

  /**
   * The generated recording {@code :test-recordings:generateTestRecordings} writes (JMC writer):
   * every event type has {@code startTime} after {@code stackTrace} and {@code eventThread}, so
   * this cell measures the header path with fields to skip, which tck-test.jfr (startTime first)
   * never does.
   */
  private static final String SKIPPING_RECORDING = "../parser-core/src/test/resources/test-ap.jfr";

  private static final String SKIPPING_KEY = "parser-core.untyped-headers.test-ap.jfr";

  private static final int SKIPPING_EVENTS = 8325;

  @Test
  void headerCountStaysWithinAllocationBudget() throws Exception {
    measure(KEY, RECORDING, EVENTS);
  }

  @Test
  void headerCountWithSkippedFieldsStaysWithinAllocationBudget() throws Exception {
    measure(SKIPPING_KEY, SKIPPING_RECORDING, SKIPPING_EVENTS);
  }

  /** Two discarded warm-up parses of {@code recording}, then one measured against {@code key}. */
  private static void measure(String key, String recording, int expectedEvents) throws Exception {
    // Two discarded warm-up parses: the first pays the lazy parser/context initialization (~14x
    // steady state), the second one more lazy step observed on JDK 21 (kb/a4-jdk8-alloc).
    Set<Long> previousPoolThreads = new HashSet<>();
    for (int i = 0; i < 2; i++) {
      AllocationMeter warmUpMeter = AllocationMeter.start();
      assertEquals(
          expectedEvents,
          parse(warmUpMeter, recording),
          "warm-up parse " + i + ": " + recording + " changed?");
      warmUpMeter.stop(); // discarded: lazy-init bytes, never compared against the budget
      previousPoolThreads.addAll(warmUpMeter.poolThreadIds());
    }

    ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long callerBefore = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
    AllocationMeter meter = AllocationMeter.start();
    int events = parse(meter, recording);
    long bytes = meter.stop();
    long callingThreadOnly =
        threadBean.getThreadAllocatedBytes(Thread.currentThread().getId()) - callerBefore;
    // Printed on every run: the pool share is where per-event decode regressions land, so a
    // meter watching only the calling thread would miss them (AC-3.2; the recorded R5 mutation
    // run uses this line to show exactly that blindness). The thread count shows how many of
    // the pinned pool's 2 threads actually took a chunk of this recording.
    System.out.println(
        "alloc "
            + key
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

    assertEquals(expectedEvents, events, recording + " event count changed");
    Set<Long> poolThreads = meter.poolThreadIds();
    assertFalse(
        poolThreads.isEmpty(),
        "no pool thread was marked: withParserListener.onChunkEnd is not delivered on the pool"
            + " threads that parse the chunks, so the meter would see only the calling thread's"
            + " bytes (AC-3.2)");
    AllocationMeter.assertFreshPoolThreads(previousPoolThreads, poolThreads);
    AllocationBudgets.check(key, bytes);
  }

  /**
   * One untyped parse of the recording: a fresh {@link ParsingContext}, the event-counting handler
   * (allocation-free) and a parser listener that marks the chunk-pool threads. Returns the number
   * of events delivered to the handler.
   */
  private static int parse(AllocationMeter meter, String recording) throws Exception {
    AtomicInteger events = new AtomicInteger();
    ParsingContext context = ParsingContext.create();
    try (UntypedJafarParser parser =
        context
            .newUntypedParser(Paths.get(recording))
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
      // parser-codegen is not on this classpath: the cell measures the generic reader.
      assertFalse(
          ((UntypedJafarParserImpl) parser).usesGeneratedReader(),
          "parse would use the generated reader parser-codegen budgets");
      parser.handleHeaders(type -> true, (type, size, startTime, ctl) -> events.incrementAndGet());
      parser.run();
    }
    return events.get();
  }
}

package io.jafar.parser.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.jafar.parser.TypeFilter;
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
 * Allocation budget of the untyped parse with a type filter, read with the bytecode-generated
 * reader ({@code EventStreamGenerated}) that {@code parser-codegen} provides and the {@code
 * jafar-parser} artifact uses by default.
 *
 * <p>The twin of the filtered cell in {@code
 * parser-core/src/test/java/io/jafar/parser/alloc/AllocationBudgetTest.java}, which measures the
 * generic reader: same fixture, same filter ({@code jdk.GCHeapSummary}, 1,892 of 67,657 events),
 * same two warm-up parses, same pool-thread accounting and the same {@code
 * -XX:ActiveProcessorCount=4} and C1 pins of the {@code allocationTest} task. The 65,765 events of
 * other types are skipped before decoding, so a regression that allocates per skipped event in the
 * generated reader shows here.
 */
class GeneratedUntypedFilteredAllocationBudgetTest {

  /** Real Datadog profiler recording, checked in: 67,657 events in 2 chunks. */
  private static final String RECORDING = "../jfr-shell-tck/src/main/resources/tck-test.jfr";

  /** Budget key without the JDK suffix; {@link AllocationBudgets#check} appends {@code jdk21}. */
  private static final String KEY = "parser-codegen.untyped-filtered.tck-test.jfr";

  private static final String FILTERED_TYPE = "jdk.GCHeapSummary";

  private static final int FILTERED_EVENTS = 1892;

  @Test
  void filteredUntypedParseStaysWithinAllocationBudget() throws Exception {
    Set<Long> previousPoolThreads = new HashSet<>();
    for (int i = 0; i < 2; i++) {
      AllocationMeter warmUpMeter = AllocationMeter.start();
      assertEquals(
          FILTERED_EVENTS, parse(warmUpMeter), "warm-up parse " + i + ": tck-test.jfr changed?");
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

    assertEquals(FILTERED_EVENTS, events, "tck-test.jfr event count changed");
    Set<Long> poolThreads = meter.poolThreadIds();
    assertFalse(
        poolThreads.isEmpty(),
        "no pool thread was marked: withParserListener.onChunkEnd is not delivered on the pool"
            + " threads that parse the chunks, so the meter would see only the calling thread's"
            + " bytes");
    AllocationMeter.assertFreshPoolThreads(previousPoolThreads, poolThreads);
    AllocationBudgets.check(KEY, bytes);
  }

  /**
   * One filtered untyped parse: a fresh {@link ParsingContext}, a counting handler registered for
   * {@link #FILTERED_TYPE} only, and the listener that marks the chunk-pool threads.
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
                    meter.markCurrentThread();
                    return true;
                  }
                })) {
      // Without this the cell would silently measure the generic reader parser-core budgets.
      assertTrue(
          ((UntypedJafarParserImpl) parser).usesGeneratedReader(),
          "parse would not use the generated reader");
      parser.handle(
          TypeFilter.named(FILTERED_TYPE), (type, event, ctl) -> events.incrementAndGet());
      parser.run();
    }
    return events.get();
  }
}

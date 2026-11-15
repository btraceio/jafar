package io.jafar.parser.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.management.ThreadMXBean;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
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
 * JDK 8 twin of {@code
 * parser-codegen/src/test/java/io/jafar/parser/alloc/TypedAllocationBudgetTest.java} — same
 * measurement, same budget key minus the JDK suffix, Java 8 syntax only because the {@code test8}
 * source set is compiled with {@code --release 8} and run on the JDK 8 toolchain (D9).
 *
 * <p>The {@code @JfrType} interface is declared inline (as {@code CoreTypedSmokeTest} does) because
 * the {@code test8} source set's compile classpath does not include the {@code test} source set's
 * output, so {@code io.jafar.parser.types.JFRGCHeapSummary} is not visible here; the shape is the
 * same, so both cells exercise the same generated-deserializer path for {@code jdk.GCHeapSummary}.
 *
 * <p>Allocation differs per JDK build, not only per major version (kb/a4-jdk8-alloc), so the JDK 8
 * cell asserts its own key: {@code parser-codegen.typed.tck-test.jfr.jdk8} (AC-3.5).
 */
class TypedAllocationBudget8Test {

  /** Real Datadog profiler recording, checked in (no download): 67,657 events in 2 chunks. */
  private static final String RECORDING = "../jfr-shell-tck/src/main/resources/tck-test.jfr";

  /** Budget key without the JDK suffix; {@link AllocationBudgets#check} appends {@code jdk8}. */
  private static final String KEY = "parser-codegen.typed.tck-test.jfr";

  /** {@code jdk.GCHeapSummary} events in {@code tck-test.jfr}, the type the handler registers. */
  private static final int GC_HEAP_SUMMARIES = 1892;

  /** Typed interface for the handled event, same shape as {@code types.JFRGCHeapSummary}. */
  // public: the runtime-generated deserializer is defined in io.jafar.parser.internal_api and
  // must access this interface; a package-private one fails on JDK 8 (CoreTypedSmokeTest's
  // inline interface is public for the same reason).
  @JfrType("jdk.GCHeapSummary")
  public interface GCHeapSummary {
    long startTime();

    int gcId();

    String when();

    long heapUsed();
  }

  @Test
  void typedParseStaysWithinAllocationBudget() throws Exception {
    // Two discarded warm-up parses: the first pays the lazy parser/context initialization plus the
    // generation of the typed deserializer bytecode, the second one more lazy step observed on
    // JDK 21 (kb/a4-jdk8-alloc).
    Set<Long> previousPoolThreads = new HashSet<Long>();
    for (int i = 0; i < 2; i++) {
      AllocationMeter warmUpMeter = AllocationMeter.start();
      assertEquals(
          GC_HEAP_SUMMARIES, parse(warmUpMeter), "warm-up parse " + i + ": tck-test.jfr changed?");
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
    // Printed on every run: the pool share is where the typed decode (generated deserializer +
    // handler invocation) runs, so a meter watching only the calling thread would miss per-event
    // regressions there (AC-3.2). The thread count shows how many of the pinned pool's 2 threads
    // actually took a chunk of this recording.
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

    assertEquals(GC_HEAP_SUMMARIES, events, "tck-test.jfr gc-heap-summary count changed");
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
   * One typed parse: a fresh {@link ParsingContext}, the counting handler, the marking listener.
   */
  private static int parse(AllocationMeter meter) throws Exception {
    AtomicInteger events = new AtomicInteger();
    ParsingContext context = ParsingContext.create();
    try (TypedJafarParser parser =
        context
            .newTypedParser(Paths.get(RECORDING))
            .withParserListener(
                new ChunkParserListener() {
                  @Override
                  public boolean onChunkEnd(ParserContext ctx, int chunkIndex, boolean skipped) {
                    // Runs on the pool thread that parsed the chunk, while it is alive.
                    meter.markCurrentThread();
                    return true;
                  }
                })) {
      HandlerRegistration<GCHeapSummary> reg =
          parser.handle(GCHeapSummary.class, (event, ctl) -> events.incrementAndGet());
      parser.run();
      reg.destroy(parser);
    }
    return events.get();
  }
}

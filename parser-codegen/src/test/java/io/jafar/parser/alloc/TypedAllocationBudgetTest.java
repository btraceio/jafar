package io.jafar.parser.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.management.ThreadMXBean;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.types.JFRGCHeapSummary;
import io.jafar.testsupport.AllocationBudgets;
import io.jafar.testsupport.AllocationMeter;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Allocation budget of the typed entry point ({@link ParsingContext#create()} + {@code
 * newTypedParser}) on {@code tck-test.jfr} (AC-3.1), mirroring {@code
 * parser-core/src/test/java/io/jafar/parser/alloc/AllocationBudgetTest.java}: same fixture, same
 * two-warm-up discipline, same pool-thread accounting and the same {@code
 * -XX:ActiveProcessorCount=4} pin of the {@code allocationTest} task (D9).
 *
 * <p>The typed path registers a handler for {@link JFRGCHeapSummary} — a {@code @JfrType} interface
 * from the codegen test types (see {@code TypedJafarParserTest}/{@code
 * TypedJafarParserStabilityTest} for the registration/iteration pattern) — so the measured parse
 * includes the runtime-generated deserializer for that type plus every event instance it produces
 * on the pool threads.
 *
 * <p><b>Why {@link JFRGCHeapSummary} and not {@code JFRExecutionSample}</b> (the interface the
 * stability test uses): {@code tck-test.jfr} never delivers its 103 {@code jdk.ExecutionSample}
 * events to any handler — not on the typed path, and not on the untyped path either (the type never
 * reaches an untyped handler; same for {@code jdk.VirtualizationInformation} et al.), so an
 * ExecutionSample handler would measure a decode path that never runs and the event-count assert
 * would pass vacuously at 0. {@code jdk.GCHeapSummary} is delivered (1,892 events, the JDK {@code
 * jfr summary} count). The handler therefore registers GCHeapSummary. That delivery gap is
 * pre-existing parser behaviour, out of scope here (D3: no parser fixes in this run).
 *
 * <p>The measured bytes cover the whole parse, not just the calling thread's orchestration
 * (AC-3.2): the typed {@code onEvent} — generated-deserializer invocation and the handler call —
 * runs on {@code StreamingChunkParser}'s pool threads, which are fresh per parse, so their absolute
 * allocated-bytes counters are per-parse values. They are read on the threads themselves, from
 * {@code withParserListener}'s {@code onChunkEnd}.
 *
 * <p>Determinism (AC-3.3, kb/a4-jdk8-alloc): two warm-up parses are discarded before the measured
 * parse; the deserializer bytecode is generated per parse (a fresh {@code ParsingContext} per
 * parse, as in the untyped twin), so its generation bytes land in the warm-ups.
 */
class TypedAllocationBudgetTest {

  /**
   * Real Datadog profiler recording, checked in (no {@code get_resources.sh} download): 67,657
   * events in 2 chunks. The {@code jdk.GCHeapSummary} count is asserted so a fixture swap or a
   * parser regression that drops events cannot hide behind a smaller allocation figure.
   */
  private static final String RECORDING = "../jfr-shell-tck/src/main/resources/tck-test.jfr";

  /** Budget key without the JDK suffix; {@link AllocationBudgets#check} appends {@code jdk21}. */
  private static final String KEY = "parser-codegen.typed.tck-test.jfr";

  /** {@code jdk.GCHeapSummary} events in {@code tck-test.jfr}, the type the handler registers. */
  private static final int GC_HEAP_SUMMARIES = 1892;

  @Test
  void typedParseStaysWithinAllocationBudget() throws Exception {
    // Two discarded warm-up parses: the first pays the lazy parser/context initialization plus the
    // generation of the typed deserializer bytecode, the second one more lazy step observed on
    // JDK 21 (kb/a4-jdk8-alloc).
    Set<Long> previousPoolThreads = new HashSet<>();
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
    // regressions there (AC-3.2; the recorded R5 mutation run uses this line to show exactly that
    // blindness). The thread count shows how many of the pinned pool's 2 threads actually took a
    // chunk of this recording.
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
   * One typed parse of the recording: a fresh {@link ParsingContext}, a counting {@code
   * jdk.GCHeapSummary} handler (allocation-free) and a parser listener that marks the chunk-pool
   * threads. Returns the number of events delivered to the handler.
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
                    // Runs on the pool thread that parsed the chunk (the typed onEvent forwards
                    // the chunk callbacks there), while that thread is alive.
                    meter.markCurrentThread();
                    return true;
                  }
                })) {
      HandlerRegistration<JFRGCHeapSummary> reg =
          parser.handle(JFRGCHeapSummary.class, (event, ctl) -> events.incrementAndGet());
      parser.run();
      reg.destroy(parser);
    }
    return events.get();
  }
}

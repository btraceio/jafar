package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.types.JFRGCHeapSummary;
import io.jafar.testsupport.ProcessorCount;
import io.jafar.testsupport.jfr.JfrCanonicalDump;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Stress test of one {@link ParsingContext} shared by concurrent parses (AC-5.3, plan step 5).
 *
 * <p>One {@code ParsingContext} is handed N=8 concurrent parses of {@code tck-test.jfr} — half
 * typed, half untyped — released together by a {@link CyclicBarrier}, for R=3 rounds, and every
 * parse's per-chunk event sequence must equal the sequence a single parse with a fresh context
 * produced. The test lives in parser-codegen on purpose: that is where the generated-deserializer
 * path runs ({@code EventStreamGenerated} for the untyped parses, the runtime-generated typed
 * deserializers for the typed ones), so the shared-context stress exercises it, not just the
 * generic reader (kb/design-concurrency item 2).
 *
 * <p><b>Why per-chunk sequences and not one flat list:</b> a chunk's events are delivered
 * sequentially, on the one pool thread that parses that chunk, so the per-chunk order is exact and
 * needs no synchronization; chunks themselves are parsed in parallel, so a single flat list would
 * have to be sorted (and lose which chunk produced what) or synchronized. Each chunk's list is
 * created in {@code onChunkStart} inside a {@code ConcurrentHashMap} keyed by the chunk index and
 * written only by its own pool thread — exactly the chunk-confined shape the concurrency hazard
 * lint (AC-5.4) requires of listener state.
 *
 * <p><b>Bounded and deterministic:</b> no sleeps, no timing assertions; the {@code @Timeout} is
 * only a hang guard (a lost parse must surface as a failure, never as a silent pass). The
 * assertions compare values, not counts: any event that decodes differently, is dropped, or is
 * delivered twice fails the comparison naming the first differing chunk and index.
 */
class SharedParsingContextStressTest {

  /**
   * Real Datadog profiler recording, checked in: 67,657 events in 2 chunks, 1,892 of them {@code
   * jdk.GCHeapSummary} (the JDK {@code jfr summary} count; also asserted below so a fixture swap
   * cannot make the comparison vacuous).
   */
  private static final Path RECORDING =
      Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  /** N: concurrent parses of one shared context, half typed, half untyped. */
  private static final int PARSES = 8;

  /** R: rounds of the N concurrent parses, all through the same shared context. */
  private static final int ROUNDS = 3;

  /** Events the untyped handler sees: every event of the recording (T12 pinned the same figure). */
  private static final int UNTYPED_EVENTS = 67_657;

  /**
   * Events the typed handler sees: {@code tck-test.jfr} delivers its 1,892 {@code
   * jdk.GCHeapSummary} events but never its 103 {@code jdk.ExecutionSample} ones, so the typed
   * reference registers {@link JFRGCHeapSummary} — the type that is actually delivered (same
   * reasoning as the codegen allocation test).
   */
  private static final int GC_HEAP_SUMMARIES = 1_892;

  /**
   * Name of the type the typed reference and stress parses render; see {@link #GC_HEAP_SUMMARIES}.
   */
  private static final String TYPED_LINE_TYPE = "jdk.GCHeapSummary";

  @BeforeAll
  static void processorCountMatrixFlagArrived() {
    ProcessorCount.assertExpected();
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES) // a hang guard only, never a timing assertion
  void everySharedContextParseMatchesItsSingleParseReference() throws Exception {
    Map<Integer, List<String>> untypedReference = untypedParse(ParsingContext.create(), null);
    Map<Integer, List<String>> typedReference = typedParse(ParsingContext.create(), null);
    assertMultiChunkAndTotals("untyped reference", untypedReference, UNTYPED_EVENTS);
    assertMultiChunkAndTotals("typed reference", typedReference, GC_HEAP_SUMMARIES);

    ParsingContext shared = ParsingContext.create();
    for (int round = 1; round <= ROUNDS; round++) {
      CyclicBarrier startGate = new CyclicBarrier(PARSES);
      List<Future<Map<Integer, List<String>>>> results = new ArrayList<>(PARSES);
      // A pool of exactly PARSES threads: every task is running when the barrier trips, so the
      // parses really do start together (a shared executor with fewer threads would serialize
      // them instead of stressing the shared context).
      ExecutorService pool = Executors.newFixedThreadPool(PARSES);
      try {
        for (int i = 0; i < PARSES; i++) {
          final boolean typed = i % 2 == 0; // half typed, half untyped
          results.add(pool.submit(newStressParse(shared, typed, startGate)));
        }
        for (int i = 0; i < PARSES; i++) {
          boolean typed = i % 2 == 0;
          String name = "round " + round + " parse " + i + (typed ? " (typed)" : " (untyped)");
          Map<Integer, List<String>> actual = results.get(i).get();
          System.out.println(
              "stress "
                  + name
                  + ": chunks="
                  + new TreeSet<>(actual.keySet())
                  + " events="
                  + total(actual));
          assertSequences(name, typed ? typedReference : untypedReference, actual);
        }
      } finally {
        pool.shutdownNow();
      }
    }
  }

  /** One stress task: a parser from the shared context, held until the gate trips, then run. */
  private static Callable<Map<Integer, List<String>>> newStressParse(
      ParsingContext shared, boolean typed, CyclicBarrier startGate) {
    return () -> typed ? typedParse(shared, startGate) : untypedParse(shared, startGate);
  }

  /**
   * One untyped parse of the recording through {@code context}: every delivered event rendered as a
   * canonical {@link JfrCanonicalDump#eventLine} — the one JFR line format (R7), resolved deep, so
   * a decode difference anywhere in a value shows up in the comparison.
   */
  private static Map<Integer, List<String>> untypedParse(
      ParsingContext context, CyclicBarrier startGate) throws Exception {
    ChunkLines lines = new ChunkLines();
    try (UntypedJafarParser parser =
        context.newUntypedParser(RECORDING).<UntypedJafarParser>withParserListener(lines)) {
      parser.handle(
          (type, event, ctl) -> lines.add(JfrCanonicalDump.eventLine(type.getName(), event)));
      awaitStart(startGate);
      parser.run();
    }
    return lines.snapshot();
  }

  /**
   * One typed parse of the recording through {@code context}: the registered {@code
   * jdk.GCHeapSummary} handler rendered per event. The typed twin of {@link #untypedParse}: the
   * typed handler receives an interface instance, not a map, so the line is built from the
   * interface's own getters — all five of them, a value-complete rendering of the typed decode.
   */
  private static Map<Integer, List<String>> typedParse(
      ParsingContext context, CyclicBarrier startGate) throws Exception {
    ChunkLines lines = new ChunkLines();
    TypedJafarParser parser = context.newTypedParser(RECORDING).withParserListener(lines);
    HandlerRegistration<JFRGCHeapSummary> registration =
        parser.handle(JFRGCHeapSummary.class, (event, ctl) -> lines.add(gcHeapSummaryLine(event)));
    awaitStart(startGate);
    try {
      parser.run();
    } finally {
      registration.destroy(parser);
      parser.close();
    }
    return lines.snapshot();
  }

  /**
   * Releases all N parses together. Everything a parse does before this point — parser construction
   * and handler registration — is per-parse state, so holding it back maximizes the number of
   * parses running their {@code run()} at the same instant. No sleeps anywhere: the barrier is the
   * only synchronization beyond the parser's own.
   */
  private static void awaitStart(CyclicBarrier startGate) throws Exception {
    if (startGate != null) {
      startGate.await();
    }
  }

  /** The typed reference line: every getter of the registered {@code @JfrType} interface. */
  private static String gcHeapSummaryLine(JFRGCHeapSummary event) {
    return TYPED_LINE_TYPE
        + " startTime="
        + event.startTime()
        + " gcId="
        + event.gcId()
        + " when="
        + event.when()
        + " heapUsed="
        + event.heapUsed();
  }

  /**
   * The per-chunk collector. The map is keyed by the chunk index handed to {@code onChunkStart};
   * each list is created there and written only by its own pool thread, which also delivers that
   * chunk's events — so within a chunk the order is exact and no lock is needed.
   */
  private static final class ChunkLines implements ChunkParserListener {

    private final ConcurrentHashMap<Integer, List<String>> chunks = new ConcurrentHashMap<>();

    /**
     * The chunk this pool thread is currently parsing; set in onChunkStart, read in the handler.
     */
    private final ThreadLocal<Integer> currentChunk = new ThreadLocal<>();

    @Override
    public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
      chunks.computeIfAbsent(chunkIndex, k -> new ArrayList<>());
      currentChunk.set(chunkIndex);
      return true;
    }

    void add(String line) {
      Integer chunk = currentChunk.get();
      List<String> lines = chunk == null ? null : chunks.get(chunk);
      if (lines == null) {
        // An event on a thread that never saw this chunk's onChunkStart would silently land
        // nowhere; make that assumption break loudly instead (R3).
        throw new IllegalStateException(
            "event delivered on a thread whose onChunkStart never ran (chunk " + chunk + ")");
      }
      lines.add(line);
    }

    /** Read after {@code run()} returned: the parse is over, no pool thread can still write. */
    Map<Integer, List<String>> snapshot() {
      Map<Integer, List<String>> out = new TreeMap<>();
      for (Map.Entry<Integer, List<String>> entry : chunks.entrySet()) {
        out.put(entry.getKey(), entry.getValue());
      }
      return out;
    }
  }

  /**
   * Asserts every chunk's ordered sequence equals the reference's, naming the first differing chunk
   * and index (the plan's requirement — a bare "differs" would send the reader digging through 454
   * MB of tck lines).
   */
  private static void assertSequences(
      String parse, Map<Integer, List<String>> reference, Map<Integer, List<String>> actual) {
    if (!reference.keySet().equals(actual.keySet())) {
      fail(
          parse
              + ": chunk indexes "
              + new TreeSet<>(actual.keySet())
              + " differ from the reference's "
              + new TreeSet<>(reference.keySet()));
    }
    for (Integer chunk : new TreeSet<>(reference.keySet())) {
      List<String> expected = reference.get(chunk);
      List<String> lines = actual.get(chunk);
      int shared = Math.min(expected.size(), lines.size());
      for (int i = 0; i < shared; i++) {
        if (!expected.get(i).equals(lines.get(i))) {
          fail(
              parse
                  + ": chunk "
                  + chunk
                  + " differs from the reference first at index "
                  + i
                  + ": expected "
                  + abbreviate(expected.get(i))
                  + " but was "
                  + abbreviate(lines.get(i)));
        }
      }
      if (expected.size() != lines.size()) {
        fail(
            parse
                + ": chunk "
                + chunk
                + " delivered "
                + lines.size()
                + " events, the reference has "
                + expected.size()
                + " (first difference at index "
                + shared
                + ")");
      }
    }
  }

  /**
   * Guards against a vacuous comparison (R6): the reference must be multi-chunk — the whole point
   * of AC-5.3 is a multi-chunk recording parsed by concurrent chunk threads — and must deliver the
   * recording's known event count, so a dropped or filtered-out type cannot shrink the compared
   * sequences to nothing.
   */
  private static void assertMultiChunkAndTotals(
      String name, Map<Integer, List<String>> reference, int expectedEvents) {
    assertTrue(
        reference.keySet().size() >= 2,
        name + ": " + RECORDING + " must parse as several chunks, got " + reference.keySet());
    assertEquals(
        expectedEvents, total(reference), name + ": event count of " + RECORDING + " changed");
  }

  private static int total(Map<Integer, List<String>> lines) {
    int total = 0;
    for (List<String> chunk : lines.values()) {
      total += chunk.size();
    }
    return total;
  }

  /** First 160 characters of a canonical line — full lines average ~6.7 KB. */
  private static String abbreviate(String line) {
    return line.length() <= 160 ? line : line.substring(0, 160) + "...";
  }
}

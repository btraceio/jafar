package io.jafar.parser.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.TimeRange;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.impl.UntypedJafarParserImpl;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.testsupport.work.WorkBudgets;
import io.jafar.testsupport.work.WorkCounters;
import io.jafar.testsupport.work.WorkProbes;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Work budgets of the untyped entry points read with the generic reader ({@code EventStream};
 * {@code parser-codegen} is not on this classpath): deterministic counts of what one parse does —
 * chunks read, events decoded, varints read, values skipped, constant-pool entries indexed and
 * decoded — checked exactly against {@code gradle/work-budgets.properties}.
 *
 * <p>Runs only in {@code workTest}, whose JVM carries the work-budget agent. Each cell parses three
 * times and requires the three counts to be identical, so a counter that depended on thread timing
 * or on what an earlier parse left in a cache would fail here, not just wobble the budget. The
 * counts and the meaning of each counter: {@code doc/plans/work-budgets.md}.
 */
class UntypedWorkBudgetTest {

  private static final Path TCK = Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");
  private static final int TCK_EVENTS = 67657;

  /** The generated recording: every type has startTime after stackTrace and eventThread. */
  private static final Path TEST_AP = Paths.get("../parser-core/src/test/resources/test-ap.jfr");

  private static final int TEST_AP_EVENTS = 8325;

  private static final String FILTERED_TYPE = "jdk.GCHeapSummary";
  private static final int FILTERED_EVENTS = 1892;

  /** Every event decoded into a map and handed to a counting handler. */
  @Test
  void untyped() throws Exception {
    check(
        "parser-core.untyped.tck-test.jfr",
        () -> {
          AtomicInteger events = new AtomicInteger();
          try (UntypedJafarParser parser = parser(TCK)) {
            parser.handle((type, value, ctl) -> events.incrementAndGet());
            parser.run();
          }
          assertEquals(TCK_EVENTS, events.get(), "tck-test.jfr event count changed");
        });
  }

  /**
   * The same parse with the handler registered for one type: 1,892 events decoded, the other 65,765
   * skipped before decoding. A filter that decoded what it then drops shows here as events.decoded
   * rising to 67,657.
   */
  @Test
  void untypedFiltered() throws Exception {
    check(
        "parser-core.untyped-filtered.tck-test.jfr",
        () -> {
          AtomicInteger events = new AtomicInteger();
          try (UntypedJafarParser parser = parser(TCK)) {
            parser.handle(
                TypeFilter.named(FILTERED_TYPE), (type, value, ctl) -> events.incrementAndGet());
            parser.run();
          }
          assertEquals(FILTERED_EVENTS, events.get(), FILTERED_TYPE + " count changed");
        });
  }

  /** {@code countEvents()}: per-type counts from the event headers, nothing decoded. */
  @Test
  void countEvents() throws Exception {
    check(
        "parser-core.count-events.tck-test.jfr",
        () -> {
          SortedMap<String, Long> counts;
          try (UntypedJafarParser parser = parser(TCK)) {
            counts = parser.countEvents();
          }
          long total = 0;
          for (long count : counts.values()) {
            total += count;
          }
          assertEquals(TCK_EVENTS, total, "tck-test.jfr event count changed");
        });
  }

  /**
   * Header handlers that use start times, on the generated recording: each header skips the two
   * fields before its startTime and reads one varint, and nothing is decoded.
   */
  @Test
  void headersWithStartTime() throws Exception {
    check(
        "parser-core.untyped-headers.test-ap.jfr",
        () -> {
          AtomicInteger events = new AtomicInteger();
          try (UntypedJafarParser parser = parser(TEST_AP)) {
            parser.handleHeaders(
                type -> true, (type, size, startTime, ctl) -> events.incrementAndGet());
            parser.run();
          }
          assertEquals(TEST_AP_EVENTS, events.get(), "test-ap.jfr event count changed");
        });
  }

  /**
   * A start-time range that begins just past the first chunk's end plus the pruning margin: no
   * event of the first chunk can start in it, so that chunk is skipped after its metadata, and only
   * the second chunk's constant pools and events are read.
   */
  @Test
  void timeRangeSkipsAChunk() throws Exception {
    SortedMap<Integer, long[]> chunks = chunkSpans(TCK);
    assertEquals(2, chunks.size(), "tck-test.jfr has 2 chunks");
    long[] first = chunks.get(1);
    long[] second = chunks.get(2);
    long margin = 1_000_000_000L; // ChunkPruning.MARGIN_NANOS
    long from = first[0] + first[1] + margin + 1;
    assertTrue(
        second[0] + second[1] + margin >= from,
        "the fixture's second chunk no longer reaches past the first one's end plus the margin");
    TimeRange range = TimeRange.since(from);
    Map<String, Long> counted =
        check(
            "parser-core.untyped-time-range.tck-test.jfr",
            () -> {
              try (UntypedJafarParser parser = parser(TCK)) {
                parser.handle(type -> true, range, (type, value, ctl) -> {});
                parser.run();
              }
            });
    assertEquals(
        1L,
        counted.get(WorkProbes.CHUNKS_STARTED) - counted.get(WorkProbes.CHUNKS_READ),
        "the range should skip exactly the first chunk");
  }

  /** A parser on a fresh context, asserted to read with the generic reader. */
  private static UntypedJafarParser parser(Path recording) {
    UntypedJafarParser parser = ParsingContext.create().newUntypedParser(recording);
    assertFalse(
        ((UntypedJafarParserImpl) parser).usesGeneratedReader(),
        "parse would use the generated reader, which parser-codegen budgets");
    return parser;
  }

  /** Chunk index to {start nanos, duration nanos}, read from the headers alone. */
  private static SortedMap<Integer, long[]> chunkSpans(Path recording) throws Exception {
    SortedMap<Integer, long[]> spans = new TreeMap<>();
    try (UntypedJafarParser parser =
        ParsingContext.create()
            .newUntypedParser(recording)
            .withParserListener(
                new ChunkParserListener() {
                  @Override
                  public boolean onChunkStart(
                      ParserContext context, int chunkIndex, ChunkHeader header) {
                    synchronized (spans) {
                      spans.put(chunkIndex, new long[] {header.startNanos, header.duration});
                    }
                    return false; // the header is all this needs
                  }
                })) {
      parser.run();
    }
    return spans;
  }

  private static Map<String, Long> check(String entry, WorkCounters.Parse parse) throws Exception {
    return WorkBudgets.measureAndCheck(entry, parse);
  }
}

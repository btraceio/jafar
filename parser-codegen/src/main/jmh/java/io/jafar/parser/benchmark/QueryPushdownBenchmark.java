package io.jafar.parser.benchmark;

import io.jafar.parser.TimeRange;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.impl.UntypedJafarParserImpl;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What a query for one event type costs today, and what it could cost at best.
 *
 * <p>A JfrPath query such as {@code events/jdk.ThreadSleep | count()} used to be answered by
 * decoding every event of every type into a map and comparing the type name afterwards. This
 * benchmark puts a number on that and on the type filter that replaced it, next to two ceilings no
 * implementation of pushdown can beat on the same file:
 *
 * <ul>
 *   <li>{@link #decodeAllFilterInHandler}: no type filter. The handler looks at one type and
 *       ignores the rest, as the JfrPath evaluator did before type filters existed.
 *   <li>{@link #typeFilterInParser}: the same query with {@code handle(TypeFilter, EventHandler)},
 *       which is what the evaluator does now.
 *   <li>{@link #ceilingSkipEveryEvent}: metadata and constant pools are read and every event header
 *       is walked, but no event is decoded. A type filter applied before decoding approaches this
 *       as the wanted type gets rarer.
 *   <li>{@link #ceilingSkipEveryChunk}: only chunk headers are read.
 *   <li>{@link #startTimeRangeSecondHalf} and {@link #startTimeRangeNothing}: queries with a
 *       start-time range, for which the parser skips the chunks that end before it.
 * </ul>
 *
 * <p>Every benchmark checks what it counted against the recording's known shape and fails if they
 * differ, so a run that silently did nothing cannot report a score.
 *
 * <p>The recording is generated per trial by {@link PushdownRecording}; see there for its shape.
 * See {@code doc/plans/jfrpath-predicate-pushdown.md} for how to run this and read the results.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class QueryPushdownBenchmark {

  /** The generated recording, shared by every benchmark of a trial. */
  @State(Scope.Benchmark)
  public static class RecordingState {
    /** How many segments the generated recording is made of. */
    @Param({"32"})
    int chunks;

    Path directory;
    Path recording;
    int chunkCount;
    long expectedEvents;

    /** The start of the second half of the chunks, after the generator's pause. */
    long secondHalfStartNanos;

    /** The end of the recording, give or take: a time no chunk reaches. */
    long afterEverythingNanos;

    /** How many {@code bench.Common} events start at or after {@link #secondHalfStartNanos}. */
    long commonInSecondHalf;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
      directory = Files.createTempDirectory("jafar-pushdown-bench");
      recording = directory.resolve("pushdown.jfr");
      chunkCount = PushdownRecording.generate(recording, chunks);
      expectedEvents =
          (long) chunks
              * (PushdownRecording.COMMON_PER_CHUNK
                  + PushdownRecording.SAMPLED_PER_CHUNK
                  + PushdownRecording.RARE_PER_CHUNK);
      long[] starts = PushdownRecording.chunkStartNanos(recording);
      secondHalfStartNanos = starts[starts.length / 2];
      afterEverythingNanos = starts[starts.length - 1] + 3_600_000_000_000L;
      // The reference for the start-time benchmarks, from a parse that skips nothing.
      LongAdder inSecondHalf = new LongAdder();
      long from = secondHalfStartNanos;
      try (UntypedJafarParser parser = ParsingContext.create().newUntypedParser(recording)) {
        parser.handle(
            TypeFilter.named(PushdownRecording.COMMON),
            (type, value, ctl) -> {
              if (((Number) value.get("startTime")).longValue() >= from) {
                inSecondHalf.increment();
              }
            });
        parser.run();
      }
      commonInSecondHalf = inSecondHalf.sum();
      System.out.println(
          "QueryPushdownBenchmark recording: "
              + Files.size(recording)
              + " bytes, "
              + chunkCount
              + " chunks, "
              + expectedEvents
              + " bench events, "
              + Runtime.getRuntime().availableProcessors()
              + " processors");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
      Files.deleteIfExists(recording);
      Files.deleteIfExists(directory);
    }
  }

  /** What the query asks for and which reader answers it. The ceilings depend on neither. */
  @State(Scope.Benchmark)
  public static class QueryState {
    /**
     * The event type the query asks for. {@code bench.Rare} is 10 events per segment, {@code
     * bench.Sampled} 10,000 with stack traces, {@code bench.Common} 100,000.
     */
    @Param({PushdownRecording.RARE, PushdownRecording.SAMPLED, PushdownRecording.COMMON})
    String targetType;

    /**
     * Which untyped reader decodes the events: {@code true} for the bytecode-generated one the
     * shell uses, {@code false} for the generic one. Both are live code paths.
     */
    @Param({"true", "false"})
    boolean generatedReader;

    long expectedTargetEvents;
    ParsingContext parsingContext;
    private String savedGeneratedProperty;

    @Setup(Level.Trial)
    public void setUp(RecordingState recording) throws Exception {
      expectedTargetEvents = (long) recording.chunks * perChunk(targetType);
      savedGeneratedProperty = System.getProperty(UntypedJafarParserImpl.GENERATED_PROPERTY);
      System.setProperty(
          UntypedJafarParserImpl.GENERATED_PROPERTY, String.valueOf(generatedReader));
      // One context for the whole trial, as an interactive session reuses its own between queries.
      parsingContext = ParsingContext.create();
      try (UntypedJafarParser parser = parsingContext.newUntypedParser(recording.recording)) {
        boolean actual = ((UntypedJafarParserImpl) parser).usesGeneratedReader();
        if (actual != generatedReader) {
          throw new IllegalStateException(
              "generatedReader=" + generatedReader + " but the parser reports " + actual);
        }
      }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      if (savedGeneratedProperty == null) {
        System.clearProperty(UntypedJafarParserImpl.GENERATED_PROPERTY);
      } else {
        System.setProperty(UntypedJafarParserImpl.GENERATED_PROPERTY, savedGeneratedProperty);
      }
    }
  }

  private static int perChunk(String type) {
    switch (type) {
      case PushdownRecording.COMMON:
        return PushdownRecording.COMMON_PER_CHUNK;
      case PushdownRecording.SAMPLED:
        return PushdownRecording.SAMPLED_PER_CHUNK;
      case PushdownRecording.RARE:
        return PushdownRecording.RARE_PER_CHUNK;
      default:
        throw new IllegalArgumentException("Unknown target type: " + type);
    }
  }

  /** Without a type filter: every event is decoded into a map; the handler keeps one type. */
  @Benchmark
  public void decodeAllFilterInHandler(RecordingState recording, QueryState query, Blackhole bh)
      throws Exception {
    LongAdder matched = new LongAdder();
    String wanted = query.targetType;
    try (UntypedJafarParser parser = query.parsingContext.newUntypedParser(recording.recording)) {
      parser.handle(
          (type, value, ctl) -> {
            if (!wanted.equals(type.getName())) {
              return;
            }
            bh.consume(value.get("seq"));
            matched.increment();
          });
      parser.run();
    }
    requireEqual("events of " + wanted, query.expectedTargetEvents, matched.sum());
  }

  /** With the type filter: only the wanted type is decoded. */
  @Benchmark
  public void typeFilterInParser(RecordingState recording, QueryState query, Blackhole bh)
      throws Exception {
    LongAdder matched = new LongAdder();
    String wanted = query.targetType;
    try (UntypedJafarParser parser = query.parsingContext.newUntypedParser(recording.recording)) {
      parser.handle(
          type -> wanted.equals(type.getName()),
          (type, value, ctl) -> {
            bh.consume(value.get("seq"));
            matched.increment();
          });
      parser.run();
    }
    requireEqual("events of " + wanted, query.expectedTargetEvents, matched.sum());
  }

  /**
   * A query for the events of the second half of the recording, by start time: the parser skips the
   * chunks of the first half. Compare with {@link #typeFilterInParser} for {@code bench.Common},
   * which reads every chunk.
   */
  @Benchmark
  public void startTimeRangeSecondHalf(RecordingState recording, Blackhole bh) throws Exception {
    long from = recording.secondHalfStartNanos;
    LongAdder matched = new LongAdder();
    LongAdder skipped = new LongAdder();
    try (UntypedJafarParser base = ParsingContext.create().newUntypedParser(recording.recording);
        UntypedJafarParser parser = base.withParserListener(countingSkipped(skipped))) {
      parser.handle(
          TypeFilter.named(PushdownRecording.COMMON),
          TimeRange.since(from),
          (type, value, ctl) -> {
            // the range is a hint: the handler still filters
            if (((Number) value.get("startTime")).longValue() >= from) {
              bh.consume(value.get("seq"));
              matched.increment();
            }
          });
      parser.run();
    }
    requireEqual(
        "bench.Common events in the second half", recording.commonInSecondHalf, matched.sum());
    requireEqual("skipped chunks", recording.chunkCount / 2, skipped.sum());
  }

  /**
   * A query whose start-time range no chunk reaches: every chunk is skipped after its metadata.
   * Compare with {@link #ceilingSkipEveryChunk}, which does not read the metadata either.
   */
  @Benchmark
  public void startTimeRangeNothing(RecordingState recording, Blackhole bh) throws Exception {
    LongAdder matched = new LongAdder();
    LongAdder skipped = new LongAdder();
    try (UntypedJafarParser base = ParsingContext.create().newUntypedParser(recording.recording);
        UntypedJafarParser parser = base.withParserListener(countingSkipped(skipped))) {
      parser.handle(
          TypeFilter.named(PushdownRecording.COMMON),
          TimeRange.since(recording.afterEverythingNanos),
          (type, value, ctl) -> matched.increment());
      parser.run();
    }
    bh.consume(matched.sum());
    requireEqual("delivered events", 0, matched.sum());
    requireEqual("skipped chunks", recording.chunkCount, skipped.sum());
  }

  private static ChunkParserListener countingSkipped(LongAdder skipped) {
    return new ChunkParserListener() {
      @Override
      public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean wasSkipped) {
        if (wasSkipped) {
          skipped.increment();
        }
        return true;
      }
    };
  }

  /**
   * Counting events per type by decoding every one of them: what a handler without header access
   * has to do. Compare with {@link #countEventsFromHeaders}.
   */
  @Benchmark
  public void countByDecoding(RecordingState recording, Blackhole bh) throws Exception {
    Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    try (UntypedJafarParser parser =
        ParsingContext.create().newUntypedParser(recording.recording)) {
      requireGeneratedReader(parser);
      parser.handle(
          (type, value, ctl) -> {
            // get() first, as countEvents does, so the two differ only in decoding
            LongAdder count = counts.get(type.getName());
            if (count == null) {
              count = counts.computeIfAbsent(type.getName(), k -> new LongAdder());
            }
            count.increment();
          });
      parser.run();
    }
    requireBenchCounts(recording, name -> counts.get(name) == null ? 0 : counts.get(name).sum());
    bh.consume(counts);
  }

  /** Counting events per type from their headers ({@code countEvents()}): nothing is decoded. */
  @Benchmark
  public void countEventsFromHeaders(RecordingState recording, Blackhole bh) throws Exception {
    Map<String, Long> counts;
    try (UntypedJafarParser parser =
        ParsingContext.create().newUntypedParser(recording.recording)) {
      requireGeneratedReader(parser);
      counts = parser.countEvents();
    }
    requireBenchCounts(recording, name -> counts.getOrDefault(name, 0L));
    bh.consume(counts);
  }

  /** Both counting benchmarks measure the generated reader, the default of jafar-parser. */
  private static void requireGeneratedReader(UntypedJafarParser parser) {
    if (!((UntypedJafarParserImpl) parser).usesGeneratedReader()) {
      throw new IllegalStateException("the counting benchmarks expect the generated reader");
    }
  }

  private static void requireBenchCounts(
      RecordingState recording, java.util.function.ToLongFunction<String> count) {
    requireEqual(
        PushdownRecording.COMMON + " events",
        (long) recording.chunks * PushdownRecording.COMMON_PER_CHUNK,
        count.applyAsLong(PushdownRecording.COMMON));
    requireEqual(
        PushdownRecording.SAMPLED + " events",
        (long) recording.chunks * PushdownRecording.SAMPLED_PER_CHUNK,
        count.applyAsLong(PushdownRecording.SAMPLED));
    requireEqual(
        PushdownRecording.RARE + " events",
        (long) recording.chunks * PushdownRecording.RARE_PER_CHUNK,
        count.applyAsLong(PushdownRecording.RARE));
  }

  /** Ceiling for a type filter: everything but event decoding. */
  @Benchmark
  public void ceilingSkipEveryEvent(RecordingState recording, Blackhole bh) throws Exception {
    LongAdder seen = new LongAdder();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          recording.recording,
          new ChunkParserListener() {
            @Override
            public boolean onEvent(
                ParserContext context,
                long typeId,
                long eventStartPos,
                long rawSize,
                long payloadSize) {
              bh.consume(typeId);
              seen.increment();
              return true;
            }
          });
    }
    // The JDK writes events of its own into every segment, so this is a lower bound.
    if (seen.sum() < recording.expectedEvents) {
      throw new IllegalStateException(
          "Walked " + seen.sum() + " events, expected at least " + recording.expectedEvents);
    }
  }

  /** Ceiling for chunk pruning: chunk headers only. */
  @Benchmark
  public void ceilingSkipEveryChunk(RecordingState recording, Blackhole bh) throws Exception {
    LongAdder seen = new LongAdder();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          recording.recording,
          new ChunkParserListener() {
            @Override
            public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
              bh.consume(header.startNanos);
              seen.increment();
              return false;
            }
          });
    }
    requireEqual("chunks", recording.chunkCount, seen.sum());
  }

  private static void requireEqual(String what, long expected, long actual) {
    if (expected != actual) {
      throw new IllegalStateException("Counted " + actual + " " + what + ", expected " + expected);
    }
  }
}

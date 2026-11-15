package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.TimeRange;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link UntypedJafarParser#handle(TypeFilter, TimeRange, UntypedJafarParser.EventHandler)} on both
 * untyped readers, against a recording made for the purpose with the running JDK: chunks more than
 * a second apart, with a {@value #SPAN} event that begins in the first and is committed in a later
 * one.
 *
 * <p>The property that matters is that skipping chunks never loses an event: for every range, the
 * events a ranged handler receives, cut down to the range, are exactly the events of a plain parse
 * cut down to the range.
 */
class UntypedTimeRangeTest {
  static final String TICK = "test.Tick";
  static final String SPAN = "test.Span";
  static final String CPU_LOAD = "jdk.CPULoad";

  private static final long SECOND = 1_000_000_000L;

  /** More than {@link ChunkPruning#MARGIN_NANOS}, so that neighbouring chunks can be told apart. */
  private static final long GAP_MILLIS = 1_300;

  @Name(TICK)
  @StackTrace(false)
  static final class Tick extends Event {
    long id;
  }

  @Name(SPAN)
  @StackTrace(false)
  static final class Span extends Event {
    long id;
  }

  private static Path dir;
  private static Path recording;

  /** Chunk index to {start, duration}, from the headers. */
  private static final Map<Integer, long[]> CHUNKS = new ConcurrentSkipListMap<>();

  /** Event type name to whether it has a {@code duration} field, from the metadata. */
  private static final Map<String, Boolean> HAS_DURATION = new ConcurrentHashMap<>();

  @BeforeAll
  static void record() throws Exception {
    dir = Files.createTempDirectory("jafar-time-range");
    recording = dir.resolve("three-chunks.jfr");
    long id = 0;
    try (Recording main = new Recording()) {
      main.enable(Tick.class);
      main.enable(Span.class);
      // a JVM event without a duration field, a few per chunk
      main.enable(CPU_LOAD).withPeriod(Duration.ofMillis(100));
      main.start();

      id = ticks(id);
      Span span = new Span();
      span.id = 1;
      span.begin();
      Thread.sleep(GAP_MILLIS);
      rotateChunk();
      // committed in the second chunk, begun more than a second before that chunk started
      span.commit();
      id = ticks(id);
      Thread.sleep(GAP_MILLIS);
      rotateChunk();
      ticks(id);
      Thread.sleep(300);

      main.stop();
      main.dump(recording);
    }
    scanHeadersAndMetadata();
  }

  private static long ticks(long id) throws InterruptedException {
    for (int i = 0; i < 20; i++) {
      Tick tick = new Tick();
      tick.id = id++;
      tick.commit();
      Thread.sleep(5);
    }
    return id;
  }

  /** Starting a second recording makes the JVM finish the current chunk and begin a new one. */
  private static void rotateChunk() {
    try (Recording other = new Recording()) {
      other.start();
      other.stop();
    }
  }

  private static void scanHeadersAndMetadata() throws Exception {
    try (UntypedJafarParser base = ParsingContext.create().newUntypedParser(recording);
        UntypedJafarParser parser =
            base.withParserListener(
                new ChunkParserListener() {
                  @Override
                  public boolean onChunkStart(ParserContext context, int index, ChunkHeader h) {
                    CHUNKS.put(index, new long[] {h.startNanos, h.duration});
                    return true;
                  }

                  @Override
                  public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                    for (MetadataClass type : metadata.getClasses()) {
                      boolean duration = false;
                      for (MetadataField field : type.getFields()) {
                        duration |= "duration".equals(field.getName());
                      }
                      HAS_DURATION.merge(type.getName(), duration, Boolean::logicalOr);
                    }
                    return true;
                  }
                })) {
      parser.handle((type, value, ctl) -> {});
      parser.run();
    }
  }

  @AfterAll
  static void cleanUp() throws Exception {
    Files.deleteIfExists(recording);
    Files.deleteIfExists(dir);
  }

  private String savedProperty;
  private boolean propertySaved;

  @AfterEach
  void restoreReader() {
    if (!propertySaved) {
      return;
    }
    if (savedProperty == null) {
      System.clearProperty(UntypedJafarParserImpl.GENERATED_PROPERTY);
    } else {
      System.setProperty(UntypedJafarParserImpl.GENERATED_PROPERTY, savedProperty);
    }
  }

  private UntypedJafarParser open(boolean generated) {
    return openFile(recording, generated);
  }

  private UntypedJafarParser openFile(Path file, boolean generated) {
    if (!propertySaved) {
      savedProperty = System.getProperty(UntypedJafarParserImpl.GENERATED_PROPERTY);
      propertySaved = true;
    }
    System.setProperty(UntypedJafarParserImpl.GENERATED_PROPERTY, String.valueOf(generated));
    UntypedJafarParser parser = ParsingContext.create().newUntypedParser(file);
    assertEquals(generated, ((UntypedJafarParserImpl) parser).usesGeneratedReader());
    return parser;
  }

  /** What one parse delivered and how many chunks it skipped. */
  private static final class Outcome {
    final List<Long> startTimes = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger skippedChunks = new AtomicInteger();
    final AtomicInteger chunks = new AtomicInteger();

    List<Long> within(TimeRange range) {
      List<Long> out = new ArrayList<>();
      synchronized (startTimes) {
        for (long t : startTimes) {
          if (t >= range.fromEpochNanos() && t <= range.toEpochNanos()) {
            out.add(t);
          }
        }
      }
      Collections.sort(out);
      return out;
    }
  }

  private static ChunkParserListener counting(Outcome outcome) {
    return new ChunkParserListener() {
      @Override
      public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
        outcome.chunks.incrementAndGet();
        if (skipped) {
          outcome.skippedChunks.incrementAndGet();
        }
        return true;
      }
    };
  }

  /** The start times of every {@code type} event, with or without a start-time range. */
  private Outcome parse(boolean generated, String type, TimeRange range) throws Exception {
    Outcome outcome = new Outcome();
    try (UntypedJafarParser base = open(generated);
        UntypedJafarParser parser = base.withParserListener(counting(outcome))) {
      UntypedJafarParser.EventHandler handler =
          (t, value, ctl) -> outcome.startTimes.add(((Number) value.get("startTime")).longValue());
      if (range == null) {
        parser.handle(TypeFilter.named(type), handler);
      } else {
        parser.handle(TypeFilter.named(type), range, handler);
      }
      parser.run();
    }
    return outcome;
  }

  private static long start(int chunk) {
    return chunkAt(chunk)[0];
  }

  private static long end(int chunk) {
    return chunkAt(chunk)[0] + chunkAt(chunk)[1];
  }

  private static int last() {
    return CHUNKS.size() - 1;
  }

  /** How many chunks the lower-bound rule skips for start times from {@code from} on. */
  private static int chunksEndingBefore(long from) {
    int count = 0;
    for (int i = 0; i < CHUNKS.size(); i++) {
      if (end(i) + ChunkPruning.MARGIN_NANOS < from) {
        count++;
      }
    }
    return count;
  }

  /** How many chunks start more than the margin after {@code to}. */
  private static int chunksStartingAfter(long to) {
    int count = 0;
    for (int i = 0; i < CHUNKS.size(); i++) {
      if (start(i) - ChunkPruning.MARGIN_NANOS > to) {
        count++;
      }
    }
    return count;
  }

  private static long[] chunkAt(int position) {
    return new ArrayList<>(CHUNKS.values()).get(position);
  }

  /** Bounds around every chunk edge, at and just past the margin on both sides. */
  private static Set<Long> interestingTimes() {
    Set<Long> times = new TreeSet<>();
    long margin = ChunkPruning.MARGIN_NANOS;
    for (int i = 0; i < CHUNKS.size(); i++) {
      for (long edge : new long[] {start(i), end(i)}) {
        for (long delta : new long[] {0, 1, margin, margin + 1, margin + 2, 2 * margin}) {
          times.add(edge + delta);
          times.add(edge - delta);
        }
      }
    }
    return times;
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void theRecordingHasTheShapeTheseTestsNeed(boolean generated) throws Exception {
    // Each rotation leaves a short chunk of its own behind, so there are more than three.
    assertTrue(CHUNKS.size() >= 3, "chunks, by header: " + CHUNKS.keySet());
    for (int i = 0; i < CHUNKS.size(); i++) {
      assertTrue(chunkAt(i)[1] > 0, "chunk " + i + " is finished");
    }
    assertTrue(start(1) - start(0) > SECOND, "the first chunk lasts more than a second");
    assertTrue(start(last()) - end(0) > SECOND, "the last chunk starts long after the first ends");
    assertEquals(Boolean.TRUE, HAS_DURATION.get(SPAN), SPAN + " has a duration field");

    List<Long> span = parse(generated, SPAN, null).startTimes;
    assertEquals(1, span.size(), "one " + SPAN + " event");
    // why a chunk that starts after the range is never skipped: begun in the first chunk's
    // time, and more than the margin before the later chunk it was written into
    assertTrue(span.get(0) < start(1) - ChunkPruning.MARGIN_NANOS, SPAN + " begins long before");
    assertTrue(parse(generated, CPU_LOAD, null).startTimes.size() >= 6, "a few CPU load events");
    assertEquals(60, parse(generated, TICK, null).startTimes.size());
  }

  /** Skipping chunks must never lose an event that starts in the range. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void noRangeEverLosesAnEventThatStartsInIt(boolean generated) throws Exception {
    List<Long> times = new ArrayList<>(interestingTimes());
    for (String type : new String[] {SPAN, TICK, CPU_LOAD}) {
      Outcome all = parse(generated, type, null);
      assertEquals(0, all.skippedChunks.get(), "no range, no skipping");
      List<TimeRange> ranges = new ArrayList<>();
      for (long t : times) {
        ranges.add(TimeRange.since(t));
        ranges.add(TimeRange.until(t));
      }
      for (int i = 0; i < times.size(); i += 3) {
        for (int j = i; j < times.size(); j += 3) {
          ranges.add(TimeRange.between(times.get(i), times.get(j)));
        }
      }
      for (TimeRange range : ranges) {
        Outcome ranged = parse(generated, type, range);
        assertEquals(all.within(range), ranged.within(range), type + " in " + range);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void chunksThatEndBeforeTheRangeAreSkipped(boolean generated) throws Exception {
    // from the last chunk on: the first chunk ended more than the margin earlier
    long from = start(last());
    int expected = chunksEndingBefore(from);
    assertTrue(expected >= 1 && expected < CHUNKS.size(), "skippable chunks: " + expected);
    Outcome ticks = parse(generated, TICK, TimeRange.since(from));
    assertEquals(CHUNKS.size(), ticks.chunks.get());
    assertEquals(expected, ticks.skippedChunks.get());
    assertTrue(
        ticks.startTimes.size() >= 20 && ticks.startTimes.size() < 60,
        "only the ticks of the chunks that were read: " + ticks.startTimes.size());

    Outcome none = parse(generated, TICK, TimeRange.since(end(last()) + 2 * SECOND));
    assertEquals(CHUNKS.size(), none.skippedChunks.get(), "a range after the recording");
    assertTrue(none.startTimes.isEmpty());
  }

  /**
   * Not even for a type without a duration field: see {@link
   * #aDatadogRecordingHoldsEventsThatStartLongBeforeTheirChunk}.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void chunksThatStartAfterTheRangeAreNeverSkipped(boolean generated) throws Exception {
    TimeRange firstChunkOnly = TimeRange.until(start(0) + 1);
    assertTrue(chunksStartingAfter(firstChunkOnly.toEpochNanos()) >= 2, "later chunks exist");

    for (String type : new String[] {CPU_LOAD, TICK, SPAN}) {
      Outcome outcome = parse(generated, type, firstChunkOnly);
      assertEquals(0, outcome.skippedChunks.get(), type);
      assertEquals(parse(generated, type, null).startTimes.size(), outcome.startTimes.size(), type);
    }
  }

  /**
   * The recording that refuted "a type without a duration field starts within its chunk": 14 {@code
   * datadog.HeapLiveObject} events, no {@code duration} field, written when the chunk was finished
   * and stamped with the time of the allocation, up to 36 minutes earlier. A range that ends before
   * the chunk starts must still return them.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aDatadogRecordingHoldsEventsThatStartLongBeforeTheirChunk(boolean generated)
      throws Exception {
    Path tck = Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr");
    String type = "datadog.HeapLiveObject";
    List<long[]> chunks = Collections.synchronizedList(new ArrayList<>());
    List<Long> all = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser base = openFile(tck, generated);
        UntypedJafarParser parser =
            base.withParserListener(
                new ChunkParserListener() {
                  @Override
                  public boolean onChunkStart(ParserContext context, int index, ChunkHeader h) {
                    chunks.add(new long[] {h.startNanos, h.duration});
                    return true;
                  }
                })) {
      parser.handle(
          TypeFilter.named(type),
          (t, value, ctl) -> all.add(((Number) value.get("startTime")).longValue()));
      parser.run();
    }
    long earliestChunkStart = chunks.stream().mapToLong(c -> c[0]).min().getAsLong();
    long before = earliestChunkStart - 2 * ChunkPruning.MARGIN_NANOS;
    long expected = all.stream().filter(t -> t <= before).count();
    assertTrue(expected > 0, type + " events starting before every chunk: " + expected);

    List<Long> ranged = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser parser = openFile(tck, generated)) {
      parser.handle(
          TypeFilter.named(type),
          TimeRange.until(before),
          (t, value, ctl) -> ranged.add(((Number) value.get("startTime")).longValue()));
      parser.run();
    }
    assertEquals(expected, ranged.stream().filter(t -> t <= before).count());
  }

  /**
   * The assumption the one rule rests on, checked on every recording at hand: no event starts after
   * its chunk ends (give or take the margin). If a writer ever breaks it, this names the event
   * type.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void noEventStartsAfterItsChunkEnds(boolean generated) throws Exception {
    List<Path> files =
        List.of(
            recording,
            Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr"),
            Path.of(UntypedTimeRangeTest.class.getClassLoader().getResource("test-ap.jfr").toURI()),
            Path.of(
                UntypedTimeRangeTest.class.getClassLoader().getResource("test-jfr.jfr").toURI()));
    for (Path file : files) {
      Map<String, Long> late = new ConcurrentHashMap<>();
      AtomicInteger checked = new AtomicInteger();
      // Chunk start to the header's duration, which is in nanoseconds. Control.ChunkInfo's
      // duration() is not used: it converts the field as if it were ticks.
      Map<Long, Long> durations = new ConcurrentHashMap<>();
      try (UntypedJafarParser base = openFile(file, generated);
          UntypedJafarParser parser =
              base.withParserListener(
                  new ChunkParserListener() {
                    @Override
                    public boolean onChunkStart(ParserContext context, int index, ChunkHeader h) {
                      durations.put(h.startNanos, h.duration);
                      return true;
                    }
                  })) {
        parser.handle(
            (type, value, ctl) -> {
              java.time.Instant chunkStart = ctl.chunkInfo().startTime();
              long startNanos = chunkStart.getEpochSecond() * SECOND + chunkStart.getNano();
              Long duration = durations.get(startNanos);
              if (!(value.get("startTime") instanceof Number)
                  || duration == null
                  || duration <= 0) {
                return;
              }
              long start = ((Number) value.get("startTime")).longValue();
              long chunkEnd = startNanos + duration;
              checked.incrementAndGet();
              if (start > chunkEnd + ChunkPruning.MARGIN_NANOS) {
                late.merge(type.getName(), start - chunkEnd, Math::max);
              }
            });
        parser.run();
      }
      assertTrue(checked.get() > 0, file + ": no event was checked");
      assertTrue(late.isEmpty(), file + ": events starting after their chunk's end, ns: " + late);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aHandlerWithoutARangeKeepsEveryChunk(boolean generated) throws Exception {
    Outcome outcome = new Outcome();
    AtomicInteger plain = new AtomicInteger();
    try (UntypedJafarParser base = open(generated);
        UntypedJafarParser parser = base.withParserListener(counting(outcome))) {
      parser.handle(
          TypeFilter.named(TICK),
          TimeRange.since(end(last()) + 2 * SECOND),
          (t, value, ctl) -> outcome.startTimes.add(0L));
      parser.handle(TypeFilter.named(TICK), (t, value, ctl) -> plain.incrementAndGet());
      parser.run();
    }
    assertEquals(0, outcome.skippedChunks.get());
    assertEquals(60, plain.get());
    assertFalse(outcome.startTimes.isEmpty(), "the ranged handler sees the chunks that were read");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void anUnboundedRangeSkipsNothing(boolean generated) throws Exception {
    Outcome outcome = parse(generated, TICK, TimeRange.all());
    assertEquals(0, outcome.skippedChunks.get());
    assertEquals(60, outcome.startTimes.size());
  }
}

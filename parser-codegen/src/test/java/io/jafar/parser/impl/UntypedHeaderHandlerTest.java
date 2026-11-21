package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.EventHeaderHandler;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkParserListener;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link UntypedJafarParser#handleHeaders} and {@link UntypedJafarParser#countEvents} on both
 * untyped readers: the generated one ({@link EventStreamGenerated}) and the generic one ({@link
 * EventStream}), chosen with {@value UntypedJafarParserImpl#GENERATED_PROPERTY}.
 */
class UntypedHeaderHandlerTest {
  private static final int COPIES = 4;

  /** Real Datadog profiler recording: 67,657 events in 2 chunks. */
  private static final Path TCK = Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  @TempDir Path dir;

  private String savedProperty;
  private boolean propertySaved;

  private void useReader(boolean generated) {
    if (!propertySaved) {
      savedProperty = System.getProperty(UntypedJafarParserImpl.GENERATED_PROPERTY);
      propertySaved = true;
    }
    System.setProperty(UntypedJafarParserImpl.GENERATED_PROPERTY, String.valueOf(generated));
  }

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

  private UntypedJafarParser open(Path recording, boolean generated) {
    useReader(generated);
    UntypedJafarParser parser = ParsingContext.create().newUntypedParser(recording);
    // R9: the property must have selected the reader this run claims to test
    assertEquals(generated, ((UntypedJafarParserImpl) parser).usesGeneratedReader());
    return parser;
  }

  /** test-ap.jfr several times over: a chunk is self-contained, so this is a multi-chunk file. */
  private Path multiChunk() throws Exception {
    Path source =
        Paths.get(
            UntypedHeaderHandlerTest.class.getClassLoader().getResource("test-ap.jfr").toURI());
    Path out = dir.resolve("multi.jfr");
    try (OutputStream os = Files.newOutputStream(out)) {
      for (int i = 0; i < COPIES; i++) {
        Files.copy(source, os);
      }
    }
    return out;
  }

  /** Event counts by type name, from a decoding handler with no filter: the oracle. */
  private Map<String, Long> decodedCounts(Path recording, boolean generated) throws Exception {
    Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    try (UntypedJafarParser parser = open(recording, generated)) {
      parser.handle(
          (type, value, ctl) ->
              counts.computeIfAbsent(type.getName(), k -> new LongAdder()).increment());
      parser.run();
    }
    Map<String, Long> result = new TreeMap<>();
    counts.forEach((name, adder) -> result.put(name, adder.sum()));
    return result;
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void countEventsMatchesTheDecodedCounts(boolean generated) throws Exception {
    for (Path recording : new Path[] {TCK, multiChunk()}) {
      Map<String, Long> expected = decodedCounts(recording, generated);
      assertFalse(expected.isEmpty(), recording + " delivered no event");
      try (UntypedJafarParser parser = open(recording, generated)) {
        assertEquals(expected, parser.countEvents(type -> true), recording.toString());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void countEventsCountsOnlyTheAcceptedTypes(boolean generated) throws Exception {
    Map<String, Long> all = decodedCounts(TCK, generated);
    String wanted = "jdk.GCHeapSummary";
    assertTrue(all.containsKey(wanted), "tck-test.jfr has no " + wanted);
    try (UntypedJafarParser parser = open(TCK, generated)) {
      Map<String, Long> counted = parser.countEvents(TypeFilter.named(wanted));
      assertEquals(Map.of(wanted, all.get(wanted)), counted);
    }
  }

  /**
   * One parse with a header handler, a decoding handler and a parser listener: for each event the
   * three are called in that order on the chunk's thread. The header must name the decoded event's
   * type, carry its decoded {@code startTime} (or {@code NO_START_TIME} where the type has none),
   * and the size the listener sees.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void headerMatchesTheDecodedEvent(boolean generated) throws Exception {
    for (Path recording : new Path[] {TCK, multiChunk()}) {
      ThreadLocal<Object[]> pending = new ThreadLocal<>();
      AtomicReference<String> mismatch = new AtomicReference<>();
      LongAdder headers = new LongAdder();
      LongAdder values = new LongAdder();
      LongAdder withStartTime = new LongAdder();
      Set<String> typesWithoutStartTime = ConcurrentHashMap.newKeySet();

      ChunkParserListener sizes =
          new ChunkParserListener() {
            @Override
            public boolean onEvent(
                ParserContext context, long typeId, long start, long rawSize, long payloadSize) {
              Object[] header = pending.get();
              pending.remove();
              if (header != null && (long) header[1] != rawSize) {
                mismatch.compareAndSet(
                    null, "size " + header[1] + " but the event is " + rawSize + " bytes");
              }
              return true;
            }
          };
      try (UntypedJafarParser base = open(recording, generated);
          UntypedJafarParser parser = base.withParserListener(sizes)) {
        parser.handleHeaders(
            type -> true,
            (type, size, startTime, ctl) -> {
              headers.increment();
              if (pending.get() != null) {
                mismatch.compareAndSet(null, "two headers before one value: " + type.getName());
              }
              pending.set(new Object[] {type, size, startTime});
            });
        parser.handle(
            (type, value, ctl) -> {
              values.increment();
              Object[] header = pending.get();
              if (header == null) {
                mismatch.compareAndSet(null, "no header before the value of " + type.getName());
                return;
              }
              if (header[0] != type) {
                mismatch.compareAndSet(
                    null, "header type " + header[0] + " for an event of " + type.getName());
              }
              long startTime = (long) header[2];
              if (UntypedJafarParserImpl.fieldsBeforeStartTime(type) != null) {
                withStartTime.increment();
                Object decoded = value.get("startTime");
                if (!(decoded instanceof Long) || (Long) decoded != startTime) {
                  mismatch.compareAndSet(
                      null,
                      type.getName() + ": header startTime " + startTime + ", decoded " + decoded);
                }
              } else {
                typesWithoutStartTime.add(type.getName());
                if (startTime != EventHeaderHandler.NO_START_TIME) {
                  mismatch.compareAndSet(
                      null, type.getName() + " has no startTime field but got " + startTime);
                }
              }
            });
        parser.run();
      }
      assertNull(mismatch.get(), recording.toString());
      assertTrue(values.sum() > 0, recording + " delivered no event");
      assertEquals(values.sum(), headers.sum(), "one header per decoded event");
      assertTrue(
          withStartTime.sum() > 0,
          recording + ": no event has a startTime, so its value went unchecked");
      System.out.println(
          recording.getFileName()
              + " ("
              + (generated ? "generated" : "generic")
              + "): "
              + values.sum()
              + " events, "
              + withStartTime.sum()
              + " with a startTime; types without one: "
              + new TreeMap<>(Map.of("types", new java.util.TreeSet<>(typesWithoutStartTime))));
    }
  }

  /**
   * The proof that an event only a header handler wants is not decoded: the recording holds an
   * event that cannot be decoded. A decoding handler fails the parse on it (shown in {@code
   * UntypedTypeFilterTest#rejectedTypeIsNotDecoded}); the header handler receives it and the parse
   * succeeds.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void headerOnlyEventIsNotDecoded(boolean generated) throws Exception {
    EventDecodeFailureTest.Corruption corruption = EventDecodeFailureTest.Corruption.values()[0];
    Path recording = EventDecodeFailureTest.corrupted(dir, corruption);

    try (UntypedJafarParser parser = open(recording, generated)) {
      Map<String, Long> counts = parser.countEvents(type -> true);
      assertTrue(
          counts.getOrDefault(EventDecodeFailureTest.TYPE, 0L) > 0,
          "the undecodable event was not counted: " + counts);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void headerHandlerRegisteredTwiceIsCalledOncePerEventAndCloseStopsIt(boolean generated)
      throws Exception {
    long total = decodedCounts(TCK, generated).values().stream().mapToLong(Long::longValue).sum();
    LongAdder calls = new LongAdder();
    EventHeaderHandler handler = (type, size, startTime, ctl) -> calls.increment();
    TypeFilter all = TypeFilter.named(decodedCounts(TCK, generated).keySet());
    try (UntypedJafarParser parser = open(TCK, generated)) {
      HandlerRegistration<?> first = parser.handleHeaders(all, handler);
      HandlerRegistration<?> second = parser.handleHeaders(all, handler);
      parser.run();
      assertEquals(total, calls.sum(), "called once per event, not once per registration");

      first.close();
      calls.reset();
      parser.run();
      assertEquals(total, calls.sum(), "still registered once");

      second.close();
      calls.reset();
      parser.run();
      assertEquals(0, calls.sum(), "every registration closed");
    }
  }

  /**
   * The rule for which types carry a {@code startTime} in the header, on the metadata of
   * tck-test.jfr: every event type has one; the non-event types, which have no {@code startTime}
   * field, have none. No checked-in recording holds an event type without one, so this is where the
   * {@code NO_START_TIME} side of the rule is checked.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void onlyTypesWithALongStartTimeFieldCarryOne(boolean generated) throws Exception {
    Map<String, Boolean> carries = new ConcurrentHashMap<>();
    Map<String, Boolean> hasField = new ConcurrentHashMap<>();
    ChunkParserListener collect =
        new ChunkParserListener() {
          @Override
          public boolean onMetadata(
              ParserContext context, io.jafar.parser.internal_api.metadata.MetadataEvent md) {
            for (io.jafar.parser.internal_api.metadata.MetadataClass type : md.getClasses()) {
              carries.put(
                  type.getName(), UntypedJafarParserImpl.fieldsBeforeStartTime(type) != null);
              hasField.put(
                  type.getName(),
                  type.getFields().stream().anyMatch(f -> "startTime".equals(f.getName())));
            }
            return false;
          }
        };
    try (UntypedJafarParser base = open(TCK, generated);
        UntypedJafarParser parser = base.withParserListener(collect)) {
      parser.run();
    }
    Set<String> events = decodedCounts(TCK, generated).keySet();
    for (String event : events) {
      assertTrue(carries.get(event), event + " is an event type and has a startTime");
    }
    long without = hasField.entrySet().stream().filter(e -> !e.getValue()).count();
    assertTrue(without > 0, "tck-test.jfr declares no type without a startTime field");
    hasField.forEach(
        (name, has) -> {
          if (!has) {
            assertFalse(carries.get(name), name + " has no startTime field");
          }
        });
  }

  /**
   * The sizes of test-ap.jfr's events, per type, as the JDK's {@code jfr summary} prints them
   * (OpenJDK 21.0.x, Count and Size (bytes) columns). The Go parser's test checks the same figures.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void headerSizesMatchJfrSummary(boolean generated) throws Exception {
    Path recording =
        Paths.get(
            UntypedHeaderHandlerTest.class.getClassLoader().getResource("test-ap.jfr").toURI());
    Map<String, LongAdder> sizes = new ConcurrentHashMap<>();
    Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    try (UntypedJafarParser parser = open(recording, generated)) {
      parser.handleHeaders(
          type -> true,
          (type, size, startTime, ctl) -> {
            sizes.computeIfAbsent(type.getName(), k -> new LongAdder()).add(size);
            counts.computeIfAbsent(type.getName(), k -> new LongAdder()).increment();
          });
      parser.run();
    }
    Map<String, Long> sizeSums = new TreeMap<>();
    sizes.forEach((name, adder) -> sizeSums.put(name, adder.sum()));
    Map<String, Long> countSums = new TreeMap<>();
    counts.forEach((name, adder) -> countSums.put(name, adder.sum()));
    assertEquals(
        Map.of(
            "jdk.ExecutionSample", 91318L,
            "jdk.CPULoad", 5985L,
            "jdk.GCHeapSummary", 10064L,
            "jdk.InitialSystemProperty", 216L),
        sizeSums);
    assertEquals(
        Map.of(
            "jdk.ExecutionSample", 8000L,
            "jdk.CPULoad", 160L,
            "jdk.GCHeapSummary", 160L,
            "jdk.InitialSystemProperty", 5L),
        countSums);
  }

  /**
   * A start-time range registered next to a header handler skips no chunk: the header handler asks
   * for every chunk. Alone, the same range (after the recording) skips them all.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void headerHandlerKeepsEveryChunkNextToARange(boolean generated) throws Exception {
    Path recording = multiChunk();
    long total =
        decodedCounts(recording, generated).values().stream().mapToLong(Long::longValue).sum();
    io.jafar.parser.TimeRange after = io.jafar.parser.TimeRange.since(1L << 62);

    LongAdder ranged = new LongAdder();
    try (UntypedJafarParser parser = open(recording, generated)) {
      parser.handle(type -> true, after, (type, value, ctl) -> ranged.increment());
      parser.run();
    }
    assertEquals(0, ranged.sum(), "alone, the range skips every chunk");

    LongAdder headers = new LongAdder();
    try (UntypedJafarParser parser = open(recording, generated)) {
      parser.handle(type -> true, after, (type, value, ctl) -> {});
      parser.handleHeaders(type -> true, (type, size, startTime, ctl) -> headers.increment());
      parser.run();
    }
    assertEquals(total, headers.sum(), "every event's header, from every chunk");
  }
}

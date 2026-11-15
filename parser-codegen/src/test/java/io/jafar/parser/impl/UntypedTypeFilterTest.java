package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.EventDecodingException;
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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link UntypedJafarParser#handle(TypeFilter, UntypedJafarParser.EventHandler)} on both untyped
 * readers: the generated one ({@link EventStreamGenerated}) and the generic one ({@link
 * EventStream}), chosen with {@value UntypedJafarParserImpl#GENERATED_PROPERTY}.
 */
class UntypedTypeFilterTest {
  private static final int COPIES = 4;

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
        Paths.get(UntypedTypeFilterTest.class.getClassLoader().getResource("test-ap.jfr").toURI());
    Path out = dir.resolve("multi.jfr");
    try (OutputStream os = Files.newOutputStream(out)) {
      for (int i = 0; i < COPIES; i++) {
        Files.copy(source, os);
      }
    }
    return out;
  }

  /** Event counts by type name, from a handler with no filter. */
  private Map<String, Long> countsByType(Path recording, boolean generated) throws Exception {
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

  /** The type with the fewest events, so that almost everything else must be skipped. */
  private static String rarest(Map<String, Long> counts) {
    String rarest = null;
    for (Map.Entry<String, Long> e : counts.entrySet()) {
      if (rarest == null || e.getValue() < counts.get(rarest)) {
        rarest = e.getKey();
      }
    }
    return rarest;
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void filteredHandlerGetsExactlyTheAcceptedType(boolean generated) throws Exception {
    Path recording = multiChunk();
    Map<String, Long> all = countsByType(recording, generated);
    assertTrue(all.size() > 1, "the recording must hold several event types: " + all);
    String wanted = rarest(all);

    LongAdder delivered = new LongAdder();
    LongAdder wrongType = new LongAdder();
    LongAdder walked = new LongAdder();
    AtomicInteger filterCalls = new AtomicInteger();
    TypeFilter filter =
        type -> {
          filterCalls.incrementAndGet();
          return wanted.equals(type.getName());
        };
    try (UntypedJafarParser base = open(recording, generated);
        UntypedJafarParser parser =
            base.withParserListener(
                new ChunkParserListener() {
                  @Override
                  public boolean onEvent(
                      ParserContext context,
                      long typeId,
                      long eventStartPos,
                      long rawSize,
                      long payloadSize) {
                    walked.increment();
                    return true;
                  }
                })) {
      parser.handle(
          filter,
          (type, value, ctl) -> {
            if (!wanted.equals(type.getName())) {
              wrongType.increment();
            }
            assertNotNull(value.get("startTime"), "a delivered event is fully decoded");
            delivered.increment();
          });
      parser.run();
    }

    long total = all.values().stream().mapToLong(Long::longValue).sum();
    assertEquals(0, wrongType.sum(), "only " + wanted + " may be delivered");
    assertEquals(all.get(wanted).longValue(), delivered.sum(), "every " + wanted + " event");
    assertEquals(total, walked.sum(), "the parser listener still sees skipped events");
    // Once per chunk and type, not once per event. Every type of the recording has events here, so
    // "types declared per chunk" is bounded by the event types seen.
    assertTrue(
        filterCalls.get() <= COPIES * all.size(),
        filterCalls.get() + " filter calls for " + COPIES + " chunks of " + all.size() + " types");
    assertTrue(filterCalls.get() < total, "the filter must not be asked per event");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unfilteredHandlerStillGetsEverythingNextToAFilteredOne(boolean generated) throws Exception {
    Path recording = multiChunk();
    Map<String, Long> all = countsByType(recording, generated);
    String wanted = rarest(all);
    long total = all.values().stream().mapToLong(Long::longValue).sum();

    LongAdder filtered = new LongAdder();
    LongAdder unfiltered = new LongAdder();
    try (UntypedJafarParser parser = open(recording, generated)) {
      parser.handle(TypeFilter.named(wanted), (t, v, c) -> filtered.increment());
      parser.handle((t, v, c) -> unfiltered.increment());
      parser.run();
    }
    assertEquals(all.get(wanted).longValue(), filtered.sum());
    assertEquals(total, unfiltered.sum());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void handlerRegisteredWithAndWithoutAFilterIsCalledOncePerEvent(boolean generated)
      throws Exception {
    Path recording = multiChunk();
    Map<String, Long> all = countsByType(recording, generated);
    long total = all.values().stream().mapToLong(Long::longValue).sum();

    LongAdder calls = new LongAdder();
    UntypedJafarParser.EventHandler handler = (t, v, c) -> calls.increment();
    try (UntypedJafarParser parser = open(recording, generated)) {
      parser.handle(handler);
      parser.handle(type -> true, handler);
      // equal filters and the same handler: one more registration of the same slot
      parser.handle(TypeFilter.named(all.keySet()), handler);
      parser.handle(TypeFilter.named(all.keySet()), handler);
      parser.run();
    }
    assertEquals(total, calls.sum());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void closingAFilteredRegistrationStopsDelivery(boolean generated) throws Exception {
    Path recording = multiChunk();
    LongAdder calls = new LongAdder();
    try (UntypedJafarParser parser = open(recording, generated)) {
      HandlerRegistration<?> registration =
          parser.handle(type -> true, (t, v, c) -> calls.increment());
      registration.close();
      parser.run();
    }
    assertEquals(0, calls.sum());
  }

  /**
   * The proof that a rejected type is not decoded: the recording holds an event that cannot be
   * decoded. A handler that wants its type fails the parse on it; a handler that does not want it
   * never finds out.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rejectedTypeIsNotDecoded(boolean generated) throws Exception {
    EventDecodeFailureTest.Corruption corruption = EventDecodeFailureTest.Corruption.values()[0];
    Path recording = EventDecodeFailureTest.corrupted(dir, corruption);

    LongAdder delivered = new LongAdder();
    try (UntypedJafarParser parser = open(recording, generated)) {
      parser.handle(
          type -> !EventDecodeFailureTest.TYPE.equals(type.getName()),
          (t, v, c) -> delivered.increment());
      parser.run();
    }
    assertEquals(0, delivered.sum(), "the recording holds no other event type");

    try (UntypedJafarParser parser = open(recording, generated)) {
      parser.handle(type -> EventDecodeFailureTest.TYPE.equals(type.getName()), (t, v, c) -> {});
      Exception failure = assertThrows(Exception.class, parser::run);
      assertTrue(hasDecodingFailure(failure), "expected an EventDecodingException: " + failure);
    }
    assertFalse(delivered.sum() > 0);
  }

  private static boolean hasDecodingFailure(Throwable t) {
    for (Throwable cause = t; cause != null; cause = cause.getCause()) {
      if (cause instanceof EventDecodingException) {
        return true;
      }
    }
    return false;
  }
}

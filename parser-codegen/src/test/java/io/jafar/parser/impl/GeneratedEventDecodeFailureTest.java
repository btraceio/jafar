package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.JfrTestHelper;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.ParserContextFactory;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The generated untyped path ({@link EventStreamGenerated}) on an undecodable event: the same
 * contract as the generic {@code EventStream}, using {@link EventDecodeFailureTest}'s fixtures.
 *
 * <p>It used to fall back to the delegate for an {@code IOException}, which returned {@code false}
 * with no delegate, and {@code StreamingChunkParser} then skipped the rest of the chunk silently:
 * events 8–19 lost and the parse "succeeded".
 */
class GeneratedEventDecodeFailureTest {
  private static final String TYPE = EventDecodeFailureTest.TYPE;
  private static final int EVENTS = EventDecodeFailureTest.EVENTS;
  private static final int BAD = EventDecodeFailureTest.BAD;

  @TempDir Path dir;

  @ParameterizedTest
  @EnumSource(EventDecodeFailureTest.Corruption.class)
  void failsTheParseNamingTheEventAndDeliversNothingHalfBuilt(
      EventDecodeFailureTest.Corruption corruption) throws Exception {
    Path jfr = EventDecodeFailureTest.corrupted(dir, corruption);
    for (UntypedStrategy strategy : UntypedStrategy.values()) {
      List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
      IOException thrown =
          assertThrows(
              IOException.class,
              () -> parse(jfr, strategy, seen, null),
              () -> strategy + "/" + corruption + ": " + seqs(seen));
      EventDecodeFailureTest.assertFailedCleanly(corruption, seen, thrown);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = EventDecodeFailureTest.Corruption.class,
      names = {"EVENT_PAYLOAD", "ARRAY_ELEMENT", "DANGLING_STRING_REF"})
  void onDecodeErrorReturningTrueSkipsOnlyTheBadEvent(EventDecodeFailureTest.Corruption corruption)
      throws Exception {
    Path jfr = EventDecodeFailureTest.corrupted(dir, corruption);
    long[] bad =
        EventDecodeFailureTest.eventExtents(dir.resolve("clean-" + corruption + ".jfr")).get(BAD);
    for (UntypedStrategy strategy : UntypedStrategy.values()) {
      List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
      List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
      parse(jfr, strategy, seen, skipped);
      String ctx = strategy + "/" + corruption + ": " + seqs(seen);

      List<Integer> expected = new ArrayList<>();
      for (int i = 0; i < EVENTS; i++) {
        if (i != BAD) {
          expected.add(i);
        }
      }
      assertEquals(expected, seqs(seen), ctx);
      for (Map<String, Object> e : seen) {
        assertEquals("label-" + e.get("seq"), e.get("label"), ctx);
      }
      assertEquals(1, skipped.size(), ctx + " decode errors: " + skipped);
      EventDecodingException f = skipped.get(0);
      assertEquals(TYPE, f.getTypeName(), ctx);
      assertEquals(bad[0], f.getPosition(), ctx);
      assertEquals(bad[1], f.getSize(), ctx);
    }
  }

  // --- the lazy generated deserializer -------------------------------------------------------
  //
  // EventDecodeFailureTest's fixtures have at most 6 fields, so every strategy generates the eager
  // (HashMap) deserializer. WIDE events have more fields than UntypedCodeGenerator's eager
  // threshold (10), so SPARSE_ACCESS generates the lazy one (thread-local ArrayPool, LazyEventMap).

  /** Field layout; {@code label} is always the last field. */
  enum Shape {
    /** EventDecodeFailureTest's fixture: {@code seq, label}. Eager under every strategy. */
    NARROW,
    /** {@code seq}, {@value #WIDE_PADDING} int pads, {@code label}: lazy under SPARSE_ACCESS. */
    WIDE
  }

  private static final int WIDE_PADDING = 9;

  static List<Arguments> shapesAndStrategies() {
    List<Arguments> args = new ArrayList<>();
    for (Shape shape : Shape.values()) {
      for (UntypedStrategy strategy :
          new UntypedStrategy[] {UntypedStrategy.SPARSE_ACCESS, UntypedStrategy.FULL_ITERATION}) {
        args.add(Arguments.of(shape, strategy));
      }
    }
    return args;
  }

  @Test
  void lazyDeserializerFailsAndSkipsLikeTheEagerOne() throws Exception {
    UntypedStrategy strategy = UntypedStrategy.SPARSE_ACCESS;
    Path clean = dir.resolve("clean-wide.jfr");
    writeWide(clean);
    List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
    List<String> mapTypes = Collections.synchronizedList(new ArrayList<>());
    parseWith(clean, new UntypedParserContextFactory(strategy), null, seen, null, mapTypes);
    assertEquals(EVENTS, seen.size(), EventDecodeFailureTest.describe(seen));
    for (String t : mapTypes) {
      assertEquals(LazyEventMap.class.getName(), t, "the fixture must reach the lazy path");
    }

    Path jfr = corrupted(Shape.WIDE);
    long[] bad = EventDecodeFailureTest.eventExtents(clean).get(BAD);
    List<Map<String, Object>> failed = Collections.synchronizedList(new ArrayList<>());
    IOException thrown =
        assertThrows(
            IOException.class,
            () -> parse(jfr, strategy, failed, null),
            () -> EventDecodeFailureTest.describe(failed));
    assertEquals(range(0, BAD), seqs(failed));
    EventDecodingException f = find(thrown, EventDecodingException.class);
    assertNotNull(f, chain(thrown));
    assertNamesTheBadEvent(f, TYPE, bad);

    List<Map<String, Object>> rest = Collections.synchronizedList(new ArrayList<>());
    List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
    parse(jfr, strategy, rest, skipped);
    assertEquals(allButBad(), seqs(rest));
    for (Map<String, Object> e : rest) {
      assertEquals("label-" + e.get("seq"), e.get("label"), "incomplete event: " + e);
    }
    assertEquals(1, skipped.size(), String.valueOf(skipped));
    assertNamesTheBadEvent(skipped.get(0), TYPE, bad);
  }

  // --- what is and is not a decode error ------------------------------------------------------

  /**
   * A corrupt type id in an event header: an {@link EventDecodingException} without a type name,
   * which the decode error handler may skip, as in {@code EventStream}.
   */
  @Test
  void undeclaredTypeIdIsADecodeErrorThatCanBeSkipped() throws Exception {
    EventDecodeFailureTest.corrupted(dir, EventDecodeFailureTest.Corruption.EVENT_PAYLOAD);
    Path clean = dir.resolve("clean-" + EventDecodeFailureTest.Corruption.EVENT_PAYLOAD + ".jfr");
    long[] bad = EventDecodeFailureTest.eventExtents(clean).get(BAD);
    byte[] bytes = Files.readAllBytes(clean);
    int typeAt = (int) bad[0] + varintLength(bytes, (int) bad[0]);
    int typeLen = varintLength(bytes, typeAt);
    // The largest id of the same encoded length, so every offset in the chunk stays the same.
    long undeclared = (1L << (7 * typeLen)) - 1;
    for (int i = 0; i < typeLen - 1; i++) {
      bytes[typeAt + i] = (byte) 0xff;
    }
    bytes[typeAt + typeLen - 1] = 0x7f;
    assertFalse(isDeclared(clean, undeclared), "id " + undeclared + " is declared");
    Path jfr = dir.resolve("corrupt-TYPE_ID.jfr");
    Files.write(jfr, bytes);

    List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
    IOException thrown =
        assertThrows(
            IOException.class,
            () -> parse(jfr, UntypedStrategy.SPARSE_ACCESS, seen, null),
            () -> EventDecodeFailureTest.describe(seen));
    assertEquals(range(0, BAD), seqs(seen));
    EventDecodingException f = find(thrown, EventDecodingException.class);
    assertNotNull(f, chain(thrown));
    assertNamesTheBadEvent(f, null, bad);
    assertEquals(undeclared, f.getTypeId());
    assertTrue(chain(f).contains("No metadata for event type id " + undeclared), chain(f));

    seen.clear();
    List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
    parse(jfr, UntypedStrategy.SPARSE_ACCESS, seen, skipped);
    assertEquals(allButBad(), seqs(seen));
    assertEquals(1, skipped.size(), String.valueOf(skipped));
    assertNamesTheBadEvent(skipped.get(0), null, bad);
  }

  /**
   * Without an untyped context no event can be decoded: an {@link IllegalStateException}, not a
   * decode error that a skipping handler would report and skip for every event.
   */
  @Test
  void missingDeserializerCacheFailsTheParseEvenWhenDecodeErrorsAreSkipped() throws Exception {
    Path jfr =
        EventDecodeFailureTest.corrupted(dir, EventDecodeFailureTest.Corruption.EVENT_PAYLOAD);
    List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
    List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                parseWith(jfr, new TypedParserContextFactory(null), null, seen, skipped::add, null),
            () -> EventDecodeFailureTest.describe(seen) + "; skipped " + skipped.size());
    assertTrue(skipped.isEmpty(), "reported as decode errors: " + skipped.size());
    assertNull(find(thrown, EventDecodingException.class), chain(thrown));
    IllegalStateException ise = find(thrown, IllegalStateException.class);
    assertNotNull(ise, chain(thrown));
    assertTrue(ise.getMessage().contains("UntypedDeserializerCache"), chain(thrown));
    assertTrue(seen.isEmpty(), EventDecodeFailureTest.describe(seen));
  }

  @Test
  void exceptionFromTheHandlerIsNotADecodeError() throws Exception {
    Path clean = dir.resolve("clean-handler.jfr");
    writeWide(clean);
    IllegalStateException mine = new IllegalStateException("handler's own failure");
    List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
    IOException thrown =
        assertThrows(
            IOException.class,
            () -> {
              try (StreamingChunkParser parser =
                  new StreamingChunkParser(new UntypedParserContextFactory())) {
                parser.parse(
                    clean,
                    new EventStreamGenerated(null) {
                      @Override
                      protected void onEventValue(
                          MetadataClass type, Map<String, Object> value, Control ctl) {
                        if (TYPE.equals(type.getName())
                            && ((Number) value.get("seq")).intValue() == 3) {
                          throw mine;
                        }
                      }

                      @Override
                      protected boolean onDecodeError(EventDecodingException failure) {
                        return skipped.add(failure);
                      }
                    });
              }
            });
    assertTrue(skipped.isEmpty(), "handler failure reported as a decode error: " + skipped);
    assertSame(mine, find(thrown, IllegalStateException.class), chain(thrown));
    assertNull(find(thrown, EventDecodingException.class), chain(thrown));
  }

  // --- concurrent chunks ----------------------------------------------------------------------

  /**
   * Several chunks, each with one bad event, decoded on the parser's thread pool and sharing one
   * deserializer cache (the chunks are identical, so they share the type's deserializer and, for
   * the lazy one, its thread-local array pool). Every bad event is reported exactly once, with its
   * own chunk index, and every other event arrives complete. When the pool has at least two threads
   * the decode error handler is also shown to run on two threads at once; with one thread (fewer
   * than four cores) there is no concurrency to prove.
   */
  @ParameterizedTest
  @MethodSource("shapesAndStrategies")
  void decodeErrorHandlerIsCalledOncePerBadEventAcrossConcurrentChunks(
      Shape shape, UntypedStrategy strategy) throws Exception {
    int chunks = 6;
    Path oneChunk = corrupted(shape);
    long[] bad = EventDecodeFailureTest.eventExtents(cleanOf(shape)).get(BAD);
    Path jfr = multiChunk(oneChunk, chunks);
    // Mirrors StreamingChunkParser's pool size.
    int threads = Math.max(Runtime.getRuntime().availableProcessors() - 2, 1);
    CountDownLatch bothInside = new CountDownLatch(Math.min(threads, 2));
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger peakInside = new AtomicInteger();
    Set<String> handlerThreads = ConcurrentHashMap.newKeySet();
    List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
    List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());

    parseWith(
        jfr,
        new UntypedParserContextFactory(strategy),
        null,
        seen,
        f -> {
          int now = inside.incrementAndGet();
          peakInside.accumulateAndGet(now, Math::max);
          try {
            skipped.add(f);
            handlerThreads.add(Thread.currentThread().getName());
            bothInside.countDown();
            // Holds the first callers so that concurrent ones overlap; times out instead of
            // hanging if the chunks are not decoded concurrently. Overlap is measured by the peak
            // number of callers inside at once, not by this latch.
            bothInside.await(10, TimeUnit.SECONDS);
            return true;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
          } finally {
            inside.decrementAndGet();
          }
        },
        null);

    String ctx = shape + "/" + strategy;
    assertEquals(chunks, skipped.size(), ctx + ": decode errors: " + skipped);
    Set<Integer> chunkIndexes = new TreeSet<>();
    for (EventDecodingException f : skipped) {
      // Positions are chunk-relative and the chunks are identical.
      assertNamesTheBadEvent(f, TYPE, bad);
      chunkIndexes.add(f.getChunkIndex());
    }
    assertEquals(
        IntStream.rangeClosed(1, chunks).boxed().collect(Collectors.toCollection(TreeSet::new)),
        chunkIndexes,
        ctx + ": one report per chunk");

    assertEquals(
        chunks * (EVENTS - 1), seen.size(), ctx + ": " + EventDecodeFailureTest.describe(seen));
    Map<Integer, Integer> perSeq = new TreeMap<>();
    synchronized (seen) {
      for (Map<String, Object> e : seen) {
        int seq = ((Number) e.get("seq")).intValue();
        assertTrue(seq != BAD, ctx + ": corrupted event delivered: " + e);
        assertEquals("label-" + seq, e.get("label"), ctx + ": incomplete event: " + e);
        perSeq.merge(seq, 1, Integer::sum);
      }
    }
    for (int seq : allButBad()) {
      assertEquals(chunks, perSeq.getOrDefault(seq, 0), ctx + ": seq " + seq + ": " + perSeq);
    }

    if (threads >= 2) {
      assertTrue(
          peakInside.get() >= 2,
          ctx
              + ": handler never ran on two threads at once; peak "
              + peakInside.get()
              + ", threads "
              + handlerThreads);
      assertTrue(handlerThreads.size() >= 2, ctx + ": " + handlerThreads);
    }
  }

  /** With the default policy, a multi-chunk parse fails with one of the chunks' failures. */
  @Test
  void concurrentChunksFailTheParseByDefault() throws Exception {
    Path jfr = multiChunk(corrupted(Shape.WIDE), 6);
    List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
    IOException thrown =
        assertThrows(
            IOException.class,
            () -> parse(jfr, UntypedStrategy.SPARSE_ACCESS, seen, null),
            () -> EventDecodeFailureTest.describe(seen));
    EventDecodingException f = find(thrown, EventDecodingException.class);
    assertNotNull(f, chain(thrown));
    assertEquals(TYPE, f.getTypeName());
    synchronized (seen) {
      for (Map<String, Object> e : seen) {
        assertTrue(((Number) e.get("seq")).intValue() != BAD, "corrupted event delivered: " + e);
      }
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  @FunctionalInterface
  private interface DecodeErrors {
    boolean on(EventDecodingException failure);
  }

  private static void assertNamesTheBadEvent(EventDecodingException f, String type, long[] bad) {
    assertEquals(type, f.getTypeName(), String.valueOf(f));
    assertEquals(bad[0], f.getPosition(), String.valueOf(f));
    assertEquals(bad[1], f.getSize(), String.valueOf(f));
    String msg = f.getMessage();
    assertTrue(msg.contains("chunk " + f.getChunkIndex()) && msg.contains("position "), msg);
  }

  private Path cleanOf(Shape shape) {
    return shape == Shape.WIDE
        ? dir.resolve("clean-wide-for-corruption.jfr")
        : dir.resolve("clean-" + EventDecodeFailureTest.Corruption.EVENT_PAYLOAD + ".jfr");
  }

  /** The label of event {@value #BAD} refers to a string constant id no encoding defines. */
  private Path corrupted(Shape shape) throws Exception {
    if (shape == Shape.NARROW) {
      return EventDecodeFailureTest.corrupted(dir, EventDecodeFailureTest.Corruption.EVENT_PAYLOAD);
    }
    Path clean = cleanOf(shape);
    writeWide(clean);
    byte[] bytes = Files.readAllBytes(clean);
    long[] bad = EventDecodeFailureTest.eventExtents(clean).get(BAD);
    // The label is the last field: a pooled-string reference, encoding id 2 + one-byte index.
    int at = (int) (bad[0] + bad[1] - 2);
    assertEquals(2, bytes[at], "label must be a constant pool reference (encoding id 2)");
    bytes[at] = 0x7f;
    Path out = dir.resolve("corrupt-wide.jfr");
    Files.write(out, bytes);
    return out;
  }

  private static void writeWide(Path jfr) throws Exception {
    JfrTestHelper.EventTypeBuilder type = JfrTestHelper.create(jfr).eventType(TYPE).intField("seq");
    for (int i = 0; i < WIDE_PADDING; i++) {
      type.intField("pad" + i);
    }
    type.stringField("label");
    for (int i = 0; i < EVENTS; i++) {
      Object[] values = new Object[WIDE_PADDING + 2];
      values[0] = i;
      for (int j = 0; j < WIDE_PADDING; j++) {
        values[1 + j] = j;
      }
      values[WIDE_PADDING + 1] = "label-" + i;
      type.event(values);
    }
    type.build();
  }

  /**
   * {@code copies} concatenated copies of a one-chunk recording: a recording of that many chunks.
   */
  private Path multiChunk(Path oneChunk, int copies) throws Exception {
    byte[] chunk = Files.readAllBytes(oneChunk);
    byte[] all = new byte[chunk.length * copies];
    for (int i = 0; i < copies; i++) {
      System.arraycopy(chunk, 0, all, i * chunk.length, chunk.length);
    }
    Path out = dir.resolve("multi-" + copies + "-" + oneChunk.getFileName());
    Files.write(out, all);
    return out;
  }

  /** Bytes taken by the LEB128 varint at {@code at}. */
  private static int varintLength(byte[] bytes, int at) {
    int len = 1;
    while ((bytes[at + len - 1] & 0x80) != 0) {
      len++;
    }
    return len;
  }

  private static boolean isDeclared(Path jfr, long typeId) throws Exception {
    AtomicInteger declared = new AtomicInteger(-1);
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.withParserListener(
              new ChunkParserListener() {
                @Override
                public boolean onEvent(
                    ParserContext c, long id, long start, long rawSize, long payloadSize) {
                  declared.compareAndSet(
                      -1, c.getMetadataLookup().getClass(typeId) != null ? 1 : 0);
                  return true;
                }
              })
          .run();
    }
    assertTrue(declared.get() >= 0, "no event seen");
    return declared.get() == 1;
  }

  private static List<Integer> range(int from, int to) {
    return IntStream.range(from, to).boxed().collect(Collectors.toList());
  }

  private static List<Integer> allButBad() {
    return IntStream.range(0, EVENTS).filter(i -> i != BAD).boxed().collect(Collectors.toList());
  }

  /** {@code skipped == null}: default policy; otherwise skip and record each failure. */
  private static void parse(
      Path jfr,
      UntypedStrategy strategy,
      List<Map<String, Object>> seen,
      List<EventDecodingException> skipped)
      throws Exception {
    parseWith(
        jfr,
        new UntypedParserContextFactory(strategy),
        null,
        seen,
        skipped == null ? null : skipped::add,
        null);
  }

  private static void parseWith(
      Path jfr,
      ParserContextFactory factory,
      ChunkParserListener delegate,
      List<Map<String, Object>> seen,
      DecodeErrors decodeErrors,
      List<String> mapTypes)
      throws Exception {
    try (StreamingChunkParser parser = new StreamingChunkParser(factory)) {
      parser.parse(
          jfr,
          new EventStreamGenerated(delegate) {
            @Override
            protected void onEventValue(MetadataClass type, Map<String, Object> v, Control ctl) {
              if (TYPE.equals(type.getName())) {
                if (mapTypes != null) {
                  mapTypes.add(v.getClass().getName());
                }
                seen.add(new HashMap<>(v)); // a lazy map may be backed by reused storage
              }
            }

            @Override
            protected boolean onDecodeError(EventDecodingException failure) {
              return decodeErrors != null && decodeErrors.on(failure);
            }
          });
    }
  }

  private static List<Integer> seqs(List<Map<String, Object>> seen) {
    List<Integer> out = new ArrayList<>();
    synchronized (seen) {
      for (Map<String, Object> e : seen) {
        out.add(((Number) e.get("seq")).intValue());
      }
    }
    return out;
  }

  private static <T extends Throwable> T find(Throwable t, Class<T> type) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (type.isInstance(c)) {
        return type.cast(c);
      }
    }
    return null;
  }

  private static String chain(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append("\n  ").append(c);
    }
    return sb.toString();
  }
}

package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.jafar.parser.JfrTestHelper;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedFieldBuilder;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * What the generic untyped value path does when one event's payload cannot be decoded.
 *
 * <p>The fixture has {@value #EVENTS} {@value #TYPE} events in one chunk; event {@value #BAD} is
 * hand-corrupted in one of two ways ({@link Corruption}). Before the fix, all three {@code
 * EventStream} variants delivered event {@value #BAD} to the handler half-built (no {@code label}),
 * from a {@code finally}. For {@link Corruption#EVENT_PAYLOAD} the decoder's {@code IOException}
 * was then caught and {@code onEvent} returned {@code false}, which {@code StreamingChunkParser}
 * reads as "skip the rest of the chunk": events {@value #BAD}+1 to {@value #EVENTS}-1 vanished and
 * {@code run()} returned normally. With a {@code withParserListener} delegate the same failure was
 * instead ignored and parsing went on.
 *
 * <p>Now the parse fails with {@link EventDecodingException} naming type, chunk and position, and
 * only the complete events before the bad one have been delivered. With {@link
 * UntypedJafarParser#onDecodeError} returning {@code true} the bad event alone is skipped.
 */
class EventDecodeFailureTest {
  private static final String TYPE = "test.Corruptible";
  private static final int EVENTS = 20;
  private static final int BAD = 7;
  private static final String MARKER = "CORRUPT_THIS_LABEL";

  /** No JFR string encoding uses this id (0..5 are defined). */
  private static final byte BAD_ENCODING = 0x7f;

  @TempDir Path dir;

  enum Corruption {
    /**
     * The encoding byte of the event's own {@code label} field: the JMC writer stores event strings
     * in the {@code java.lang.String} constant pool, so the event carries id 2 (pool reference) + a
     * one-byte index as its last two bytes. {@code ParsingUtils.readUTF8} throws {@code
     * IOException("Unexpected string constant id: 127")} while reading the event.
     */
    EVENT_PAYLOAD,
    /**
     * The encoding byte of the pooled string the event points to. The pool entry is decoded lazily
     * when the event reads it, and {@code MutableConstantPool.get} rethrows the {@code IOException}
     * wrapped in a {@code RuntimeException}.
     */
    CONSTANT_POOL,
    /**
     * Like {@link #EVENT_PAYLOAD}, but the string is inside the last element of an array of inline
     * (not pooled) complex values, so the failure happens with an unfinished element map above the
     * array holder on the value builder's stack. {@code GenericValueReader} used to call {@code
     * onArrayEnd} from a {@code finally}; with assertions enabled its {@code assert arr != null}
     * then threw an {@link AssertionError} that replaced the decoding exception and, being an
     * {@code Error}, bypassed {@code onDecodeError}.
     */
    ARRAY_ELEMENT
  }

  /** The three generic value paths; only {@link #DEFAULT} is reachable from the public API. */
  enum Stream {
    DEFAULT(EventStream.class),
    LAZY(EventStreamLazy.class),
    BASELINE(EventStreamBaseline.class);

    final Class<?> type;

    Stream(Class<?> type) {
      this.type = type;
    }

    ChunkParserListener listener(List<Map<String, Object>> sink) {
      return listener(sink, null);
    }

    /** {@code skipped == null}: default policy; otherwise skip and record each failure there. */
    ChunkParserListener listener(
        List<Map<String, Object>> sink, List<EventDecodingException> skipped) {
      switch (this) {
        case DEFAULT:
          return new EventStream(null) {
            @Override
            protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {
              collect(t, v, sink);
            }

            @Override
            protected boolean onDecodeError(EventDecodingException failure) {
              return skipped != null && skipped.add(failure);
            }
          };
        case LAZY:
          return new EventStreamLazy(null) {
            @Override
            protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {
              collect(t, v, sink);
            }

            @Override
            protected boolean onDecodeError(EventDecodingException failure) {
              return skipped != null && skipped.add(failure);
            }
          };
        case BASELINE:
          return new EventStreamBaseline(null) {
            @Override
            protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {
              collect(t, v, sink);
            }

            @Override
            protected boolean onDecodeError(EventDecodingException failure) {
              return skipped != null && skipped.add(failure);
            }
          };
        default:
          throw new AssertionError(this);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Corruption.class)
  void publicApiFailsTheParseAndNeverDeliversTheEventHalfBuilt(Corruption corruption)
      throws Exception {
    assertFailsCleanly(
        corruption,
        jfr -> {
          List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
          IOException thrown = parseWithPublicApi(jfr, seen, false);
          return new Outcome(seen, thrown);
        });
  }

  @ParameterizedTest
  @EnumSource(Corruption.class)
  void publicApiWithParserListenerFailsToo(Corruption corruption) throws Exception {
    // A delegate used to turn the IOException into "carry on" rather than "skip the chunk".
    assertFailsCleanly(
        corruption,
        jfr -> {
          List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
          IOException thrown = parseWithPublicApi(jfr, seen, true);
          return new Outcome(seen, thrown);
        });
  }

  @ParameterizedTest
  @EnumSource(Stream.class)
  void everyGenericValuePathFailsTheParse(Stream stream) throws Exception {
    for (Corruption corruption : Corruption.values()) {
      assertFailsCleanly(
          corruption,
          jfr -> {
            List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
            IOException thrown =
                assertThrows(
                    IOException.class,
                    () -> {
                      try (StreamingChunkParser p =
                          new StreamingChunkParser(
                              ((ParsingContextImpl) ParsingContext.create())
                                  .untypedContextFactory())) {
                        p.parse(jfr, stream.listener(seen));
                      }
                    },
                    () -> stream + "/" + corruption + ": " + describe(seen));
            return new Outcome(seen, thrown);
          });
    }
  }

  // --- opt-in skipping: UntypedJafarParser.onDecodeError -------------------------------------

  @ParameterizedTest
  @EnumSource(
      value = Corruption.class,
      names = {"EVENT_PAYLOAD", "ARRAY_ELEMENT"})
  void decodeErrorHandlerReturningTrueSkipsOnlyTheBadEvent(Corruption corruption) throws Exception {
    Path jfr = corrupted(corruption);
    List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
    List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.handle((type, value, ctl) -> collect(type, value, seen));
      p.onDecodeError(skipped::add).run();
    }
    assertSkippedOnlyTheBadEvent(corruption, jfr, seen, skipped);
  }

  @Test
  void decodeErrorHandlerSurvivesWithParserListener() throws Exception {
    Corruption corruption = Corruption.EVENT_PAYLOAD;
    Path jfr = corrupted(corruption);
    List<Long> delegateSaw = Collections.synchronizedList(new ArrayList<>());
    List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
    List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.onDecodeError(skipped::add);
      UntypedJafarParser q =
          p.withParserListener(
              new ChunkParserListener() {
                @Override
                public boolean onEvent(
                    ParserContext c, long typeId, long start, long rawSize, long payloadSize) {
                  if (TYPE.equals(c.getMetadataLookup().getClass(typeId).getName())) {
                    delegateSaw.add(start);
                  }
                  return true;
                }
              });
      q.handle((type, value, ctl) -> collect(type, value, seen));
      q.run();
    }
    assertSkippedOnlyTheBadEvent(corruption, jfr, seen, skipped);
    // The delegate works at the chunk level and still sees the skipped event, as documented.
    assertEquals(EVENTS, delegateSaw.size(), String.valueOf(delegateSaw));
    assertTrue(delegateSaw.contains(skipped.get(0).getPosition()), String.valueOf(delegateSaw));
  }

  @Test
  void decodeErrorHandlerReturningFalseOrResetToNullFailsTheParse() throws Exception {
    for (Corruption corruption : Corruption.values()) {
      for (boolean reset : new boolean[] {false, true}) {
        assertFailsCleanly(
            corruption,
            jfr -> {
              List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
              try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
                p.handle((type, value, ctl) -> collect(type, value, seen));
                if (reset) {
                  p.onDecodeError(f -> true).onDecodeError(null);
                } else {
                  p.onDecodeError(f -> false);
                }
                return new Outcome(seen, assertThrows(IOException.class, p::run));
              }
            });
      }
    }
  }

  @Test
  void exceptionFromDecodeErrorHandlerFailsTheParseWithIt() throws Exception {
    Path jfr = corrupted(Corruption.EVENT_PAYLOAD);
    IllegalStateException mine = new IllegalStateException("enough");
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.onDecodeError(
          f -> {
            throw mine;
          });
      IOException thrown = assertThrows(IOException.class, p::run);
      assertTrue(find(thrown, IllegalStateException.class) == mine, chain(thrown));
    }
  }

  @ParameterizedTest
  @EnumSource(Stream.class)
  void everyGenericValuePathCanSkip(Stream stream) throws Exception {
    for (Corruption corruption :
        new Corruption[] {Corruption.EVENT_PAYLOAD, Corruption.ARRAY_ELEMENT}) {
      Path jfr = corrupted(corruption);
      List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
      List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
      try (StreamingChunkParser p =
          new StreamingChunkParser(
              ((ParsingContextImpl) ParsingContext.create()).untypedContextFactory())) {
        p.parse(jfr, stream.listener(seen, skipped));
      }
      assertSkippedOnlyTheBadEvent(corruption, jfr, seen, skipped);
    }
  }

  /**
   * Skip mode with a corrupted constant pool entry. Only part of the outcome is right, and only
   * that part is asserted: the event that reads the bad entry is reported once and not delivered.
   *
   * <p>Not asserted, because it is wrong: later events in the chunk can then read other pooled
   * strings as {@code null} without any error. {@code ParsingUtils.skipUTF8}, which indexes the
   * pool, treats an unknown encoding id as having no payload, so every later entry's offset is
   * misaligned, and {@code readUTF8} returns {@code null} for a reference it cannot resolve. The Go
   * parser's {@code skipString} has the same default. Fixing it changes constant pool error policy
   * in both parsers and is left to a separate change.
   */
  @Test
  void skippingAnEventWithACorruptedPoolEntryReportsItOnce() throws Exception {
    Path jfr = corrupted(Corruption.CONSTANT_POOL);
    List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
    List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.handle((type, value, ctl) -> collect(type, value, seen));
      p.onDecodeError(skipped::add).run();
    }
    assertEquals(1, skipped.size(), "decode errors: " + skipped);
    assertEquals(TYPE, skipped.get(0).getTypeName());
    assertEquals(EVENTS - 1, seen.size(), describe(seen));
    for (Map<String, Object> e : seen) {
      assertTrue(((Number) e.get("seq")).intValue() != BAD, "corrupted event delivered: " + e);
    }
  }

  /**
   * Several chunks, each with one bad event, decoded on the parser's thread pool. Every bad event
   * is reported exactly once, with its own chunk index, and every other event arrives complete.
   * When the pool has at least two threads the handler is also shown to run on two threads at the
   * same time; with one thread (fewer than four cores) there is no concurrency to prove.
   */
  @Test
  void decodeErrorHandlerIsCalledOncePerBadEventAcrossConcurrentChunks() throws Exception {
    int chunks = 6;
    Path jfr = multiChunk(corrupted(Corruption.EVENT_PAYLOAD), chunks);
    // Mirrors StreamingChunkParser's pool size.
    int threads = Math.max(Runtime.getRuntime().availableProcessors() - 2, 1);
    CountDownLatch bothInside = new CountDownLatch(Math.min(threads, 2));
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger peakInside = new AtomicInteger();
    Set<String> handlerThreads = ConcurrentHashMap.newKeySet();

    List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
    List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.handle((type, value, ctl) -> collect(type, value, seen));
      p.onDecodeError(
          f -> {
            int now = inside.incrementAndGet();
            peakInside.accumulateAndGet(now, Math::max);
            try {
              skipped.add(f);
              handlerThreads.add(Thread.currentThread().getName());
              bothInside.countDown();
              // Holds the first callers so that concurrent ones overlap; times out instead of
              // hanging if the chunks are not decoded concurrently. Overlap is measured by the
              // peak number of callers inside at once, not by this latch.
              bothInside.await(10, TimeUnit.SECONDS);
              return true;
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return false;
            } finally {
              inside.decrementAndGet();
            }
          });
      p.run();
    }

    assertEquals(chunks, skipped.size(), "decode errors: " + skipped);
    Set<Integer> chunkIndexes = new TreeSet<>();
    for (EventDecodingException f : skipped) {
      assertEquals(TYPE, f.getTypeName());
      chunkIndexes.add(f.getChunkIndex());
    }
    assertEquals(chunks, chunkIndexes.size(), "one report per chunk: " + chunkIndexes);

    assertEquals(chunks * (EVENTS - 1), seen.size(), describe(seen));
    Map<Integer, Integer> perSeq = new TreeMap<>();
    synchronized (seen) {
      for (Map<String, Object> e : seen) {
        int seq = ((Number) e.get("seq")).intValue();
        assertTrue(seq != BAD, "corrupted event delivered: " + e);
        assertEquals("label-" + seq, e.get("label"), "incomplete event: " + e);
        perSeq.merge(seq, 1, Integer::sum);
      }
    }
    for (int seq = 0; seq < EVENTS; seq++) {
      if (seq != BAD) {
        assertEquals(chunks, perSeq.getOrDefault(seq, 0), "seq " + seq + " per chunk: " + perSeq);
      }
    }

    if (threads >= 2) {
      assertTrue(
          peakInside.get() >= 2,
          "handler never ran on two threads at once; peak "
              + peakInside.get()
              + ", threads "
              + handlerThreads);
      assertTrue(handlerThreads.size() >= 2, String.valueOf(handlerThreads));
    }
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
    Path out = dir.resolve("multi-" + copies + ".jfr");
    Files.write(out, all);
    return out;
  }

  private static void assertNoOwnStackTraceButCauseHasTheDecodeFrames(EventDecodingException f) {
    assertEquals(0, f.getStackTrace().length, "EventDecodingException should not fill a trace");
    boolean decodeFrame = false;
    for (StackTraceElement el : f.getCause().getStackTrace()) {
      decodeFrame |=
          el.getClassName().startsWith("io.jafar.parser.impl.EventStream")
              && el.getMethodName().equals("onEvent");
    }
    assertTrue(decodeFrame, "cause trace lacks EventStream*.onEvent");
  }

  private void assertSkippedOnlyTheBadEvent(
      Corruption corruption,
      Path jfr,
      List<Map<String, Object>> seen,
      List<EventDecodingException> skipped)
      throws Exception {
    String ctx = corruption + ": " + describe(seen);
    List<Integer> expected =
        IntStream.range(0, EVENTS).filter(i -> i != BAD).boxed().collect(Collectors.toList());
    List<Integer> got = new ArrayList<>();
    synchronized (seen) {
      for (Map<String, Object> e : seen) {
        int seq = ((Number) e.get("seq")).intValue();
        assertEquals("label-" + seq, e.get("label"), "incomplete event; " + ctx);
        if (corruption == Corruption.ARRAY_ELEMENT) {
          assertTrue(e.containsKey("holder"), "incomplete event; " + ctx);
        }
        got.add(seq);
      }
    }
    // One chunk is decoded by one thread, in file order.
    assertEquals(expected, got, ctx);

    assertEquals(1, skipped.size(), "decode errors: " + skipped);
    EventDecodingException f = skipped.get(0);
    // Corruption changes one byte in place, so the clean twin has the same positions and sizes.
    long[] bad = eventExtents(dir.resolve("clean-" + corruption + ".jfr")).get(BAD);
    assertEquals(TYPE, f.getTypeName());
    assertEquals(1, f.getChunkIndex());
    assertEquals(bad[0], f.getPosition());
    assertEquals(bad[1], f.getSize());
    assertTrue(chain(f).contains("Unexpected string constant id: 127"), chain(f));
    assertNoOwnStackTraceButCauseHasTheDecodeFrames(f);
  }

  @Test
  void uncorruptedFixtureDeliversEveryEventComplete() throws Exception {
    Path jfr = dir.resolve("clean.jfr");
    write(jfr);
    List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.handle((type, value, ctl) -> collect(type, value, seen));
      p.run();
    }
    assertEquals(EVENTS, seen.size(), describe(seen));
    for (Map<String, Object> e : seen) {
      int seq = ((Number) e.get("seq")).intValue();
      assertEquals(seq == BAD ? MARKER : "label-" + seq, e.get("label"), String.valueOf(e));
    }
  }

  @Test
  void uncorruptedItemsFixtureDecodesBothInlineElements() throws Exception {
    Path jfr = dir.resolve("clean-items.jfr");
    writeWithItems(jfr);
    List<String> seconds = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.handle(
          (type, value, ctl) -> {
            if (TYPE.equals(type.getName())) {
              Map<?, ?> h = (Map<?, ?>) value.get("holder");
              Object[] items =
                  (Object[]) ((io.jafar.parser.api.ArrayType) h.get("items")).getArray();
              assertEquals(2, items.length, String.valueOf(value));
              seconds.add(String.valueOf(((Map<?, ?>) items[1]).get("s")));
            }
          });
      p.run();
    }
    assertEquals(EVENTS, seconds.size());
    assertTrue(seconds.contains(MARKER), String.valueOf(seconds));
  }

  // --- helpers -------------------------------------------------------------------------------

  private static final class Outcome {
    final List<Map<String, Object>> seen;
    final Throwable thrown;

    Outcome(List<Map<String, Object>> seen, Throwable thrown) {
      this.seen = seen;
      this.thrown = thrown;
    }
  }

  @FunctionalInterface
  private interface Run {
    Outcome on(Path jfr) throws Exception;
  }

  private void assertFailsCleanly(Corruption corruption, Run run) throws Exception {
    Path jfr = corrupted(corruption);
    Outcome o = run.on(jfr);
    String ctx = corruption + ": " + describe(o.seen);

    // No partial map, and the bad event is not among the delivered ones.
    for (Map<String, Object> e : o.seen) {
      assertTrue(e.containsKey("seq") && e.containsKey("label"), "partial event delivered; " + ctx);
      assertTrue(((Number) e.get("seq")).intValue() != BAD, "corrupted event delivered; " + ctx);
    }
    // Events are decoded in file order within a chunk: exactly those before the bad one arrived.
    assertEquals(BAD, o.seen.size(), ctx);

    EventDecodingException decoding = find(o.thrown, EventDecodingException.class);
    assertNotNull(decoding, "no EventDecodingException in" + chain(o.thrown));
    String msg = decoding.getMessage();
    assertTrue(msg.contains(TYPE) && msg.contains("chunk 1") && msg.contains("position "), msg);
    assertTrue(chain(decoding).contains("Unexpected string constant id: 127"), chain(o.thrown));
  }

  private static IOException parseWithPublicApi(
      Path jfr, List<Map<String, Object>> seen, boolean withDelegate) throws Exception {
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      UntypedJafarParser q = withDelegate ? p.withParserListener(new ChunkParserListener() {}) : p;
      q.handle((type, value, ctl) -> collect(type, value, seen));
      return assertThrows(IOException.class, q::run, () -> describe(seen));
    }
  }

  private static void collect(
      MetadataClass type, Map<String, Object> value, List<Map<String, Object>> sink) {
    if (TYPE.equals(type.getName())) {
      sink.add(new HashMap<>(value)); // the parser may reuse the map after the callback
    }
  }

  private Path corrupted(Corruption corruption) throws Exception {
    Path clean = dir.resolve("clean-" + corruption + ".jfr");
    if (corruption == Corruption.ARRAY_ELEMENT) {
      writeWithItems(clean);
    } else {
      write(clean);
    }
    byte[] bytes = Files.readAllBytes(clean);
    int at;
    if (corruption == Corruption.EVENT_PAYLOAD || corruption == Corruption.ARRAY_ELEMENT) {
      long[] bad = eventExtents(clean).get(BAD);
      at = (int) (bad[0] + bad[1] - 2);
      assertEquals(2, bytes[at], "label must be a constant pool reference (encoding id 2)");
    } else {
      byte[] marker = MARKER.getBytes(StandardCharsets.UTF_8);
      int m = indexOf(bytes, marker, 0);
      if (m < 2 || indexOf(bytes, marker, m + 1) >= 0) {
        fail("expected exactly one occurrence of the marker, first at " + m);
      }
      // <encoding byte> <one-byte varint length> <bytes>
      assertEquals(marker.length, bytes[m - 1], "length byte before the marker");
      at = m - 2;
      assertTrue(bytes[at] == 3 || bytes[at] == 5, "encoding byte before marker: " + bytes[at]);
    }
    bytes[at] = BAD_ENCODING;
    Path out = dir.resolve("corrupt-" + corruption + ".jfr");
    Files.write(out, bytes);
    return out;
  }

  /** File offset and size of each {@value #TYPE} event, in order (one chunk, starting at 0). */
  private static List<long[]> eventExtents(Path jfr) throws Exception {
    List<long[]> extents = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.withParserListener(
              new ChunkParserListener() {
                @Override
                public boolean onEvent(
                    ParserContext c, long typeId, long start, long rawSize, long payloadSize) {
                  if (TYPE.equals(c.getMetadataLookup().getClass(typeId).getName())) {
                    extents.add(new long[] {start, rawSize});
                  }
                  return true;
                }
              })
          .run();
    }
    assertEquals(EVENTS, extents.size());
    return extents;
  }

  /**
   * Same events as {@link #write}, plus a last field {@code holder}: an inline {@code test.Holder}
   * whose {@code items} is an array of two inline {@code test.Item} values, each with a string
   * {@code s}. Event {@value #BAD}'s second item carries the marker, so its pooled-string reference
   * is the event's last two bytes. (The array sits in a holder because the JMC writer cannot write
   * an array field directly on an event: {@code Chunk.writeEvent} calls {@code getValue()} on every
   * top-level field.)
   */
  private static void writeWithItems(Path jfr) throws Exception {
    try (Recording recording = Recordings.newRecording(jfr)) {
      Types types = recording.getTypes();
      Type stackTraceType = types.getType(Types.JDK.STACK_TRACE);
      Type threadType = types.getType(Types.JDK.THREAD);
      Type item = types.getOrAdd("test.Item", false, t -> t.addField("s", Types.Builtin.STRING));
      Type holder =
          types.getOrAdd(
              "test.Holder", false, t -> t.addField("items", item, TypedFieldBuilder::asArray));
      Type event =
          recording.registerEventType(
              TYPE,
              t -> {
                t.addField("seq", Types.Builtin.INT);
                t.addField("label", Types.Builtin.STRING);
                t.addField("holder", holder);
              });
      for (int i = 0; i < EVENTS; i++) {
        int seq = i;
        recording.writeEvent(
            event.asValue(
                v -> {
                  // Implicit fields first: the JMC writer declares but does not write them.
                  v.putField("stackTrace", stackTraceType.nullValue());
                  v.putField("eventThread", threadType.nullValue());
                  v.putField("startTime", System.nanoTime());
                  v.putField("seq", seq);
                  v.putField("label", "label-" + seq);
                  v.putField(
                      "holder",
                      h ->
                          h.putFields(
                              "items",
                              e -> e.putField("s", "first-" + seq),
                              e -> e.putField("s", seq == BAD ? MARKER : "second-" + seq)));
                }));
      }
    }
  }

  private static void write(Path jfr) throws Exception {
    JfrTestHelper.EventTypeBuilder type =
        JfrTestHelper.create(jfr).eventType(TYPE).intField("seq").stringField("label");
    for (int i = 0; i < EVENTS; i++) {
      type.event(i, i == BAD ? MARKER : "label-" + i);
    }
    type.build();
  }

  private static String describe(List<Map<String, Object>> seen) {
    StringBuilder sb = new StringBuilder("handler received " + seen.size() + " events:");
    synchronized (seen) {
      for (Map<String, Object> e : seen) {
        sb.append(" [seq=").append(e.get("seq")).append(" keys=").append(e.keySet()).append(']');
      }
    }
    return sb.toString();
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

  private static int indexOf(byte[] haystack, byte[] needle, int from) {
    outer:
    for (int i = from; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}

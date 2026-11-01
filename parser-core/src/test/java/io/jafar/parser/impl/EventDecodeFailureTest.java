package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.jafar.parser.JfrTestHelper;
import io.jafar.parser.api.Control;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
 * only the complete events before the bad one have been delivered.
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
    CONSTANT_POOL
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
      switch (this) {
        case DEFAULT:
          return new EventStream(null) {
            @Override
            protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {
              collect(t, v, sink);
            }
          };
        case LAZY:
          return new EventStreamLazy(null) {
            @Override
            protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {
              collect(t, v, sink);
            }
          };
        case BASELINE:
          return new EventStreamBaseline(null) {
            @Override
            protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {
              collect(t, v, sink);
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
    write(clean);
    byte[] bytes = Files.readAllBytes(clean);
    int at;
    if (corruption == Corruption.EVENT_PAYLOAD) {
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

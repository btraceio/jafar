package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

  /** {@code skipped == null}: default policy; otherwise skip and record each failure. */
  private static void parse(
      Path jfr,
      UntypedStrategy strategy,
      List<Map<String, Object>> seen,
      List<EventDecodingException> skipped)
      throws Exception {
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory(strategy))) {
      parser.parse(
          jfr,
          new EventStreamGenerated(null) {
            @Override
            protected void onEventValue(MetadataClass type, Map<String, Object> v, Control ctl) {
              if (TYPE.equals(type.getName())) {
                seen.add(new HashMap<>(v)); // a lazy map may be backed by reused storage
              }
            }

            @Override
            protected boolean onDecodeError(EventDecodingException failure) {
              return skipped != null && skipped.add(failure);
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

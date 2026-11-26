package io.jafar.shell.live;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.backend.EventSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LiveEventDecoder} conversion tests against purpose-built recordings (the shared fixtures
 * are deliberately avoided — they contain event types that were reported to trip a pre-existing JDK
 * consumer quirk with JDK 25, btraceio/jafar#187; that report did not reproduce on any of the 8
 * builds investigated for #187, but the fixtures stay out of these tests regardless). The {@code
 * decodeFields} half is the extraction of the jdk file backend's converter — its output shape is a
 * file-backend contract, so it is pinned here field-for-field. The {@code decode} half adds the
 * live time decorations.
 */
class LiveEventDecoderTest {

  /** Test event carrying a thread field and its own startTime/duration declarations. */
  static class ThreadedEvent extends jdk.jfr.Event {
    String label;
    java.lang.Thread thread;
  }

  /** Test event whose built-in stackTrace slot carries a recorded trace (settings force it). */
  @StackTrace
  static class TracedEvent extends jdk.jfr.Event {
    String label;
  }

  @TempDir Path tmp;

  private Path recordingWithThreads() throws Exception {
    Path file = tmp.resolve("threaded.jfr");
    try (Recording r = new Recording()) {
      r.setToDisk(true);
      r.setDestination(file);
      r.start();
      for (int i = 0; i < 3; i++) {
        ThreadedEvent e = new ThreadedEvent();
        e.label = "e" + i;
        e.thread = Thread.currentThread();
        e.commit();
      }
      Thread.sleep(50);
      r.stop();
    }
    return file;
  }

  private Path recordingWithStackTraces() throws Exception {
    Path file = tmp.resolve("traced.jfr");
    try (Recording r = new Recording()) {
      r.setToDisk(true);
      r.setDestination(file);
      r.enable(TracedEvent.class).withThreshold(Duration.ZERO).withStackTrace();
      r.start();
      for (int i = 0; i < 3; i++) {
        TracedEvent e = new TracedEvent();
        e.label = "t" + i;
        e.commit();
      }
      Thread.sleep(50);
      r.stop();
    }
    return file;
  }

  private static RecordedEvent firstEventOfType(Path file, String simpleName) throws Exception {
    try (RecordingFile rf = new RecordingFile(file)) {
      while (rf.hasMoreEvents()) {
        RecordedEvent e = rf.readEvent();
        if (e.getEventType().getName().endsWith(simpleName)) {
          return e;
        }
      }
    }
    throw new AssertionError("no " + simpleName + " in the fixture");
  }

  @Test
  void decodeFieldsMatchesRawFieldIteration() throws Exception {
    Path file = recordingWithThreads();
    RecordedEvent event = firstEventOfType(file, "ThreadedEvent");
    Map<String, Object> fields = LiveEventDecoder.decodeFields(event);
    assertEquals(event.getFields().size(), fields.size());
    for (var f : event.getFields()) {
      assertTrue(fields.containsKey(f.getName()), f.getName());
    }
    assertEquals(event.getValue("label"), fields.get("label"));
  }

  @Test
  void threadFlattenCarriesIdentity() throws Exception {
    Path file = recordingWithThreads();
    RecordedEvent event = firstEventOfType(file, "ThreadedEvent");
    Map<String, Object> fields = LiveEventDecoder.decodeFields(event);
    RecordedThread thread = (RecordedThread) event.getValue("thread");
    @SuppressWarnings("unchecked")
    Map<String, Object> threadMap = (Map<String, Object>) fields.get("thread");
    // RecordedThread.getFields() itself carries the thread identity — the flatten keeps it
    assertEquals(thread.getJavaName(), threadMap.get("javaName"));
    assertEquals(thread.getOSThreadId(), threadMap.get("osThreadId"));
    assertEquals(thread.getJavaThreadId(), threadMap.get("javaThreadId"));
  }

  @Test
  void decodeAddsTimeDecorations() throws Exception {
    Path file = recordingWithThreads();
    RecordedEvent event = firstEventOfType(file, "ThreadedEvent");
    Map<String, Object> decoded = LiveEventDecoder.decode(event);

    long startNanos =
        event.getStartTime().getEpochSecond() * 1_000_000_000L + event.getStartTime().getNano();
    assertEquals(startNanos, decoded.get("startTime"));
    assertEquals(event.getDuration().toNanos(), decoded.get("duration"));
  }

  @Test
  void decodeRewritesChunkRelativeBuiltinTimeFields() throws Exception {
    Path file = recordingWithThreads();
    RecordedEvent event = firstEventOfType(file, "ThreadedEvent");
    Map<String, Object> fields = LiveEventDecoder.decodeFields(event);
    long rawStart = (Long) fields.get("startTime");
    long absolute =
        event.getStartTime().getEpochSecond() * 1_000_000_000L + event.getStartTime().getNano();
    // The raw flatten carries the built-in chunk-relative ticks; the live decode rewrites them
    assertNotEquals(absolute, rawStart);
    Map<String, Object> decoded = LiveEventDecoder.decode(event);
    assertEquals(absolute, decoded.get("startTime"));
    assertEquals(event.getDuration().toNanos(), decoded.get("duration"));
  }

  @Test
  void decodeFeedsTheSameShapeTheEvaluatorConsumes() throws Exception {
    Path file = recordingWithThreads();
    RecordedEvent event = firstEventOfType(file, "ThreadedEvent");
    EventSource.Event ev =
        new EventSource.Event(event.getEventType().getName(), LiveEventDecoder.decode(event));
    assertEquals(event.getEventType().getName(), ev.typeName());
    assertTrue(ev.value().containsKey("startTime"));
  }

  @Test
  void stackTraceSlotFlattensToTruncatedPlusFramesShape() throws Exception {
    Path file = recordingWithStackTraces();
    RecordedEvent event = firstEventOfType(file, "TracedEvent");
    Map<String, Object> fields = LiveEventDecoder.decodeFields(event);
    Object struct = fields.get("stackTrace");
    assertTrue(struct instanceof Map, "stackTrace slot flattened to a map: " + struct);
    @SuppressWarnings("unchecked")
    Map<String, Object> structMap = (Map<String, Object>) struct;
    assertTrue(structMap.containsKey("truncated"), "flattened trace has 'truncated': " + structMap);
    Object frames = structMap.get("frames");
    assertTrue(
        frames instanceof Object[] array && array.length > 0,
        "flattened trace carries the frames value: " + structMap);
    // #196: the frames are the untyped model the jafar backend produces, an Object[] of
    // flattened frame maps, not the JDK's raw RecordedFrame objects
    for (Object frame : (Object[]) frames) {
      assertTrue(frame instanceof Map, "frame flattened to a map: " + frame);
      assertFalse(frame instanceof jdk.jfr.consumer.RecordedObject, String.valueOf(frame));
      assertTrue(((Map<?, ?>) frame).containsKey("method"), String.valueOf(frame));
    }
    // ...and two conversions of the same event now compare equal by content
    Object again =
        ((Map<?, ?>) LiveEventDecoder.decodeFields(event).get("stackTrace")).get("frames");
    assertTrue(java.util.Arrays.deepEquals((Object[]) frames, (Object[]) again));
    // Shape pin for the file-backend contract — NOT a regression test for btraceio/jafar#187:
    // the reported get-on-stackTrace failure could not be reproduced on any JDK build tested, and
    // per JDK source RecordedEvent.getStackTrace() runs through the very same getValue lookup as
    // the generic route (getTyped = hasField + getValue + type check on JDK 17-26), so no
    // dedicated struct route exists to pin. The projection-equality between two separate
    // conversions is guarded by liveDecodeCarriesTheSameStackTraceValue.
  }

  @Test
  void liveDecodeCarriesTheSameStackTraceValue() throws Exception {
    Path file = recordingWithStackTraces();
    RecordedEvent event = firstEventOfType(file, "TracedEvent");
    Map<String, Object> decoded = LiveEventDecoder.decode(event);
    Map<String, Object> plain = LiveEventDecoder.decodeFields(event);
    assertTrue(decoded.get("stackTrace") instanceof Map, "live decode flattens the struct");
    // The live path rewrites startTime/duration; every other field, stackTrace included, must
    // carry the same value the file-backend conversion produces. Projected equality because the
    // frames value is an Object[], and arrays compare by identity in Map.equals.
    assertEquals(projected(plain.get("stackTrace")), projected(decoded.get("stackTrace")));
    assertFalse(String.valueOf(decoded.get("stackTrace")).startsWith("<unconvertible:"));
  }

  /**
   * Canonical projection for value comparison: descends maps, Object[]/List arrays and {@code
   * RecordedObject} values (through {@link LiveEventDecoder#convertValue}) so two separate
   * conversions of the same recorded struct compare equal by content, not identity.
   */
  private static Object projected(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new java.util.LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        out.put(String.valueOf(e.getKey()), projected(e.getValue()));
      }
      return out;
    }
    if (value instanceof Object[] array) {
      List<Object> out = new ArrayList<>();
      for (Object element : array) {
        out.add(projected(element));
      }
      return out;
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object element : list) {
        out.add(projected(element));
      }
      return out;
    }
    if (value instanceof jdk.jfr.consumer.RecordedObject struct) {
      return projected(LiveEventDecoder.convertValue(struct));
    }
    return value;
  }

  @Test
  void convertValueFlattensObjectArraysAndKeepsPrimitiveArrays() {
    // #196: arrays follow the jafar backend's untyped model (Values.resolvedDeep)
    int[] ints = {1, 2, 3};
    assertSame(ints, LiveEventDecoder.convertValue(ints), "primitive arrays stay as they are");
    Object[] in = {"a", null, 7L, new Object[] {"nested"}, List.of("x")};
    Object out = LiveEventDecoder.convertValue(in);
    assertTrue(out instanceof Object[], String.valueOf(out));
    assertNotSame(in, out, "an object array is converted element by element, not passed through");
    assertArrayEquals(
        new Object[] {"a", null, 7L, new Object[] {"nested"}, List.of("x")}, (Object[]) out);
  }
}

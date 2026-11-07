package io.jafar.testsupport.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.testsupport.DumpDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Permanent proofs of the canonical dump format (T19): every rendering rule — flattening, array
 * indices, byte arrays, string-constant wrappers, escaping, null, float bit patterns — is pinned by
 * a unit test on a hand-built value, and the recording-level tests prove the whole pipeline over a
 * JMC-written recording: the dump is deterministic across repeated parses, the event count is
 * exact, and the header carries this side, the fixture sha256 and the event count.
 *
 * <p>The golden-only sections (the {@code C } and {@code M } lines) are proven at the recording
 * level too: a recording with constant-pool-backed values must yield constant lines and metadata
 * lines in its digest, or the goldens would silently cover events only.
 */
class JfrCanonicalDumpTest {

  @TempDir Path dir;

  // --- rendering rules, hand-built values -----------------------------------------------

  @Test
  void flattensNestedMapsIntoSortedDottedKeys() {
    Map<String, Object> inner = new LinkedHashMap<String, Object>();
    inner.put("b", 2L);
    inner.put("a", 1L);
    Map<String, Object> event = new LinkedHashMap<String, Object>();
    event.put("struct", inner);
    event.put("plain", "x");
    Map<String, String> flat = JfrCanonicalDump.flatten(event);
    assertEquals(Arrays.asList("plain", "struct.a", "struct.b"), new ArrayList<>(flat.keySet()));
    assertEquals("1", flat.get("struct.a"));
  }

  @Test
  void indexesArrayElementsAsDottedKeys() {
    Map<String, Object> frame0 = new LinkedHashMap<String, Object>();
    frame0.put("line", 7);
    Map<String, Object> frame1 = new LinkedHashMap<String, Object>();
    frame1.put("line", 9);
    Map<String, Object> event = new LinkedHashMap<String, Object>();
    event.put("frames", Arrays.asList(frame0, frame1));
    event.put("nested", new Object[] {new Object[] {1, 2}, 3});
    event.put("ints", new int[] {4, 5});
    event.put("longs", new long[] {6L});
    Map<String, String> flat = JfrCanonicalDump.flatten(event);
    assertEquals("7", flat.get("frames.0.line"));
    assertEquals("9", flat.get("frames.1.line"));
    assertEquals("1", flat.get("nested.0.0"));
    assertEquals("2", flat.get("nested.0.1"));
    assertEquals("3", flat.get("nested.1"));
    assertEquals("4", flat.get("ints.0"));
    assertEquals("5", flat.get("ints.1"));
    assertEquals("6", flat.get("longs.0"));
    // a primitive array never renders as its toString (an identity hash): every element is a key
    for (String value : flat.values()) {
      assertFalse(value.startsWith("[I") || value.startsWith("[J") || value.contains("@"));
    }
  }

  @Test
  void rendersByteArraysAsLengthHex() {
    assertEquals("b5:68656c6c6f", JfrCanonicalDump.bytes(new byte[] {'h', 'e', 'l', 'l', 'o'}));
    assertEquals("b0:", JfrCanonicalDump.bytes(new byte[0]));
  }

  @Test
  void rendersAStringConstantWrapperAsItsBytes() {
    // a one-entry {"string": byte[]} map is an unresolved string constant: the whole map renders
    // as the byte array at its key, not as a nested .string key
    Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
    wrapper.put("string", new byte[] {'h', 'i'});
    Map<String, Object> event = new LinkedHashMap<String, Object>();
    event.put("text", wrapper);
    Map<String, String> flat = JfrCanonicalDump.flatten(event);
    assertEquals("b2:6869", flat.get("text"));
    assertFalse(flat.containsKey("text.string"));
  }

  @Test
  void escapesNewlinesAndCarriageReturns() {
    assertEquals("line1\\nline2", JfrCanonicalDump.escape("line1\nline2"));
    assertEquals("a\\rb", JfrCanonicalDump.escape("a\rb"));
    Map<String, Object> event = new LinkedHashMap<String, Object>();
    event.put("value", "a\r\nb");
    assertEquals("a\\r\\nb", JfrCanonicalDump.flatten(event).get("value"));
  }

  @Test
  void rendersNullAsNull() {
    assertEquals("null", JfrCanonicalDump.scalar(null));
    Map<String, Object> event = new LinkedHashMap<String, Object>();
    event.put("absent", null);
    assertEquals("null", JfrCanonicalDump.flatten(event).get("absent"));
  }

  @Test
  void rendersFloatsAndDoublesAsIeee754BitPatterns() {
    assertEquals("f:3ff8000000000000", JfrCanonicalDump.scalar(1.5d));
    assertEquals("f:3ff8000000000000", JfrCanonicalDump.scalar(1.5f)); // widened, same bits
    assertEquals("f:0000000000000000", JfrCanonicalDump.scalar(0.0d));
    assertEquals("f:8000000000000000", JfrCanonicalDump.scalar(-0.0d));
    assertEquals("f:8000000000000000", JfrCanonicalDump.scalar(-0.0f));
    assertEquals("f:3fd0000000000000", JfrCanonicalDump.scalar(0.25d));
    // canonical NaN: doubleToLongBits collapses any NaN payload to the same quiet pattern
    assertEquals("f:7ff8000000000000", JfrCanonicalDump.scalar(Double.NaN));
    assertEquals("f:7ff8000000000000", JfrCanonicalDump.scalar(Float.NaN));
    assertEquals(
        "f:7ff8000000000000",
        JfrCanonicalDump.scalar(Double.longBitsToDouble(0x7ff81234_5678_9abcL)));
  }

  // --- a real JMC-written recording ------------------------------------------------------

  @Test
  void eventsAreIdenticalAcrossThreeParsesAndTheCountIsExact() throws IOException {
    Path recording = writeRecording();
    List<String> first = JfrCanonicalDump.events(recording);
    assertEquals(3, first.size(), "exactly the three events written, nothing phantom");
    assertEquals(first, JfrCanonicalDump.events(recording));
    assertEquals(first, JfrCanonicalDump.events(recording));
    assertEquals(first, sortedCopy(first), "the returned lines are sorted");

    String event0 = eventWithId(first, "0");
    String event1 = eventWithId(first, "1");
    String event2 = eventWithId(first, "2");
    for (String line : first) {
      assertTrue(line.startsWith("test.Marker "), "every line is one whole event: " + line);
      assertTrue(
          line.contains("trace.points.0.x=10"), "array elements are dotted-indexed: " + line);
      assertTrue(line.contains("label.blob=b3:aabbcc"), "byte arrays are b<len>:<hex>: " + line);
    }
    assertTrue(event0.contains("label.name=marker-0"));
    assertTrue(event1.contains("label.name=line1\\nline2\\r!"), "newlines stay escaped: " + event1);
    // 0.25 / 1.5f / -0.0 / NaN in every combination the recording writes
    assertTrue(event0.contains("label.ratio=f:3fd0000000000000"));
    assertTrue(event0.contains("label.weight=f:3ff8000000000000"));
    assertTrue(event1.contains("label.ratio=f:8000000000000000"));
    assertTrue(event1.contains("label.weight=f:7ff8000000000000"));
    assertTrue(event2.contains("label.ratio=f:7ff8000000000000"));
    assertTrue(event2.contains("label.weight=f:8000000000000000"));
  }

  /** The event whose {@code id} field equals {@code id} — key lookups, not line order. */
  private static String eventWithId(List<String> lines, String id) {
    for (String line : lines) {
      for (String field : line.split(" ")) {
        if (field.equals("id=" + id)) {
          return line;
        }
      }
    }
    throw new AssertionError("no event with id=" + id + " in " + lines);
  }

  @Test
  void writeEventDumpCarriesSideShaAndEventCountInTheHeader() throws IOException {
    Path recording = writeRecording();
    Path out = dir.resolve("marker.java.dump");
    JfrCanonicalDump.writeEventDump(recording, out);
    List<String> lines =
        Files.readAllLines(out, StandardCharsets.UTF_8); // escaped values never split a line
    String expectedHeader =
        JfrCanonicalDump.DUMP_FORMAT
            + " side="
            + JfrCanonicalDump.SIDE
            + " fixture-sha256="
            + DumpDigest.sha256Hex(Files.readAllBytes(recording))
            + " events=3";
    assertEquals(expectedHeader, lines.get(0));
    assertEquals(4, lines.size(), "header plus one line per event, no golden-only sections");
    assertEquals(JfrCanonicalDump.events(recording), lines.subList(1, lines.size()));
    for (String line : lines.subList(1, lines.size())) {
      assertFalse(line.startsWith("C "), "no constants section in the differential dump");
      assertFalse(line.startsWith("M "), "no metadata section in the differential dump");
    }
  }

  @Test
  void digestPinsConstantsAndMetadataBesideTheEvents() throws IOException {
    Path recording = writeRecording();
    DumpDigest eventsOnly = JfrCanonicalDump.digest(recording, false);
    DumpDigest withSections = JfrCanonicalDump.digest(recording, true);

    assertEquals(3, eventsOnly.lines(), "three event lines");
    assertTrue(eventsOnly.lines() < withSections.lines(), "the sections add lines");
    assertEquals(Long.valueOf(3), eventsOnly.counts().get("test.Marker"));
    assertEquals(eventsOnly.counts(), withSections.counts(), "counts stay event-type counts");
    assertEquals(DumpDigest.sha256Hex(Files.readAllBytes(recording)), withSections.fixtureSha256());
    // deterministic across parses, with the sections collected from the chunk callbacks
    assertEquals(withSections.digest(), JfrCanonicalDump.digest(recording, true).digest());

    // both sections actually populate: constants from the pools at onChunkEnd, metadata classes
    // and fields at onMetadata — an empty section would be a silent hole in the golden, not a
    // smaller dump
    int constantLines = 0;
    int metadataLines = 0;
    for (String line : JfrCanonicalDump.dump(recording, true)) {
      if (line.startsWith("C 1 ")) {
        constantLines++;
      } else if (line.startsWith("M 1 ")) {
        metadataLines++;
      }
    }
    assertTrue(constantLines > 0, "constant-pool entries are pinned: none in the dump");
    assertTrue(metadataLines > 0, "metadata classes and fields are pinned: none in the dump");
  }

  /**
   * Writes a three-event recording exercising every rendering rule: a constant-pool-backed struct
   * (the writer pools registerType values), a byte-array field, float and double fields in the
   * {-0.0, 0.25, 1.5, NaN} set, a struct array, and a string with embedded newline.
   */
  private Path writeRecording() throws IOException {
    Path file = dir.resolve("canonical.jfr");
    try (java.io.OutputStream out = Files.newOutputStream(file);
        Recording r = Recordings.newRecording(out)) {
      Type point =
          r.registerType(
              "test.types.Point",
              b -> b.addField("x", Types.Builtin.LONG).addField("y", Types.Builtin.LONG));
      // The array lives inside a struct, like the JDK's own stackTrace→frames shape: the JMC
      // writer cannot write an array field directly on an event type (it calls getValue() on it,
      // which throws for arrays — TypedFieldValueImpl.getValue).
      Type trace =
          r.registerType("test.types.Trace", b -> b.addField("points", point, fb -> fb.asArray()));
      Type payload =
          r.registerType(
              "test.types.Payload",
              b ->
                  b.addField("name", Types.Builtin.STRING)
                      .addField("blob", Types.Builtin.BYTE, fb -> fb.asArray())
                      .addField("ratio", Types.Builtin.DOUBLE)
                      .addField("weight", Types.Builtin.FLOAT));
      // "jdk.jfr.Event" supertype registration, not registerEventType: the JMC writer
      // mishandles the special call (TestJfrRecorder's comment says the same) — with it, every
      // struct field of the event resolves to null and the long field comes out as the writer's
      // internal pool index. An explicit startTime field replaces the auto-added ones, so the
      // recording carries no unset fields at all.
      Type marker =
          r.registerType(
              "test.Marker",
              "jdk.jfr.Event",
              b ->
                  b.addField("id", Types.Builtin.LONG)
                      .addField("label", payload)
                      .addField("trace", trace)
                      .addField("startTime", Types.Builtin.LONG));

      r.writeEvent(markerEvent(marker, payload, trace, point, 0, "marker-0", 0.25d, 1.5f));
      r.writeEvent(
          markerEvent(marker, payload, trace, point, 1, "line1\nline2\r!", -0.0d, Float.NaN));
      r.writeEvent(markerEvent(marker, payload, trace, point, 2, "marker-2", Double.NaN, -0.0f));
    }
    return file;
  }

  private static TypedValue markerEvent(
      Type marker,
      Type payload,
      Type trace,
      Type point,
      long id,
      String name,
      double ratio,
      float weight) {
    TypedValue label =
        payload.asValue(
            p ->
                p.putField("name", name)
                    .putField("blob", new byte[] {(byte) 0xaa, (byte) 0xbb, (byte) 0xcc})
                    .putField("ratio", ratio)
                    .putField("weight", weight));
    TypedValue p0 = point.asValue(v -> v.putField("x", 10L).putField("y", 20L));
    TypedValue p1 = point.asValue(v -> v.putField("x", 11L).putField("y", 21L));
    TypedValue points = trace.asValue(t -> t.putField("points", p0, p1));
    return marker.asValue(
        v ->
            v.putField("id", id)
                .putField("label", label)
                .putField("trace", points)
                .putField("startTime", 1_700_000_000_000_000_000L));
  }

  private static List<String> sortedCopy(List<String> lines) {
    List<String> copy = new ArrayList<String>(lines);
    java.util.Collections.sort(copy);
    return copy;
  }
}

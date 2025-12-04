package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.TestJfrRecorder;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.types.JFRExecutionSample;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;

public class TypedJafarParserTest {
  @Test
  void testEventParsing() throws Exception {

    ByteArrayOutputStream recordingStream = new ByteArrayOutputStream();
    long eventTypeId = -1;
    try (Recording recording = Recordings.newRecording(recordingStream)) {
      TestJfrRecorder rec = new TestJfrRecorder(recording);
      eventTypeId = rec.registerEventType(ParserEvent.class).getId();
      rec.writeEvent(new ParserEvent(10));
    }

    assertNotEquals(-1, eventTypeId);

    Path tmpFile = Files.createTempFile("recording", ".jfr");
    tmpFile.toFile().deleteOnExit();

    Files.write(tmpFile, recordingStream.toByteArray());

    AtomicInteger eventCount = new AtomicInteger(0);
    try (TypedJafarParser parser = TypedJafarParser.open(tmpFile.toString())) {
      parser.handle(
          ParserEvent1.class,
          (event, ctl) -> {
            assertNotNull(ctl.chunkInfo());
            assertNotEquals(Control.ChunkInfo.NONE, ctl.chunkInfo());
            eventCount.incrementAndGet();
            assertEquals(10, event.value());
          });

      parser.run();
    }

    assertEquals(1, eventCount.get());
  }

  @Test
  void testRealFile() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();

    try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
      AtomicLong eventCount = new AtomicLong(0);
      AtomicLong idHash = new AtomicLong(113);
      HandlerRegistration<JFRExecutionSample> h1 =
          p.handle(
              JFRExecutionSample.class,
              (event, ctl) -> {
                assertNotNull(event.sampledThread());
                assertNotNull(event.stackTrace());
                assertTrue(event.stackTrace().frames().length > 0);
                eventCount.incrementAndGet();

                long id = event.stackTraceId();
                idHash.updateAndGet(v -> v * 31 + id);
              });
      p.run();
      h1.destroy(p);

      System.out.println("StackTraceId Hash: " + idHash.get());
    }
  }

  /**
   * Test to validate that @JfrField annotation supports dual access to constant pool fields:
   * - Resolved value (the actual object via stackTrace())
   * - Raw constant pool index (long via stackTraceId())
   */
  @Test
  void testDualConstantPoolAccess() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();

    try (TypedJafarParser parser = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
      Map<Long, Integer> idToCount = new HashMap<>();

      parser.handle(
          JFRExecutionSample.class,
          (event, ctl) -> {
            // Test 1: Both accessors should work
            long rawId = event.stackTraceId(); // Raw CP index
            var stackTrace = event.stackTrace(); // Resolved value

            assertNotEquals(0, rawId, "Raw stackTrace ID should be non-zero");
            assertNotNull(stackTrace, "Resolved stackTrace should not be null");

            // Test 2: The same ID should consistently map to the same resolved object
            idToCount.merge(rawId, 1, Integer::sum);

            // Test 3: Verify stackTrace has frames
            assertTrue(
                stackTrace.frames().length > 0, "StackTrace should have at least one frame");
          });

      parser.run();

      // Test 4: Verify we saw events with valid IDs
      assertFalse(idToCount.isEmpty(), "Should have processed events with stackTrace IDs");

      // Test 5: Verify some IDs appear multiple times (same stack trace reused)
      long reusedIds = idToCount.values().stream().filter(count -> count > 1).count();
      assertTrue(
          reusedIds > 0,
          "Some stackTrace IDs should be reused across multiple events (constant pool optimization)");

      System.out.println("Processed " + idToCount.size() + " unique stackTrace IDs");
      System.out.println("Found " + reusedIds + " IDs that were reused across events");
    }
  }

  /**
   * Test that the same constant pool index always resolves to the same object, validating
   * consistency between raw index and resolved value accessors.
   */
  @Test
  void testConstantPoolIndexConsistency() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();

    try (TypedJafarParser parser = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
      Map<Long, String> idToFirstFrameMethod = new HashMap<>();

      parser.handle(
          JFRExecutionSample.class,
          (event, ctl) -> {
            long id = event.stackTraceId();
            var stackTrace = event.stackTrace();

            if (stackTrace != null && stackTrace.frames().length > 0) {
              String method = stackTrace.frames()[0].method().name();

              // If we've seen this ID before, verify it resolves to the same stack trace
              if (idToFirstFrameMethod.containsKey(id)) {
                assertEquals(
                    idToFirstFrameMethod.get(id),
                    method,
                    "Same CP index should always resolve to same stackTrace");
              } else {
                idToFirstFrameMethod.put(id, method);
              }
            }
          });

      parser.run();

      assertFalse(idToFirstFrameMethod.isEmpty(), "Should have mapped IDs to stackTrace data");
    }
  }
}

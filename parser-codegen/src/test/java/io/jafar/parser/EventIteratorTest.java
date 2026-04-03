package io.jafar.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import io.jafar.TestJfrRecorder;
import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.EventIterator;
import io.jafar.parser.api.JafarRecordedEvent;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;

public class EventIteratorTest {

  @Name("io.jafar.test.IteratorTestEvent")
  @StackTrace(true)
  static class TestEvent extends Event {
    int id;

    TestEvent(int id) {
      this.id = id;
    }
  }

  private static Path syntheticJfr;
  private static Path multiChunkJfr;

  @BeforeAll
  static void createTestJfrFiles() throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (Recording recording = Recordings.newRecording(bos)) {
      TestJfrRecorder rec = new TestJfrRecorder(recording);
      rec.registerEventType(TestEvent.class);
      for (int i = 0; i < 100; i++) {
        rec.writeEvent(new TestEvent(i));
      }
    }
    syntheticJfr = Files.createTempFile("jafar-iterator-test", ".jfr");
    Files.write(syntheticJfr, bos.toByteArray());

    // Two-chunk recording: concatenate two independent single-chunk recordings.
    // JFR format is a sequence of self-contained chunks, so this produces a valid two-chunk file.
    bos = new ByteArrayOutputStream();
    try (Recording r = Recordings.newRecording(bos)) {
      TestJfrRecorder rec = new TestJfrRecorder(r);
      rec.registerEventType(TestEvent.class);
      for (int i = 0; i < 20; i++) rec.writeEvent(new TestEvent(i));
    }
    try (Recording r = Recordings.newRecording(bos)) {
      TestJfrRecorder rec = new TestJfrRecorder(r);
      rec.registerEventType(TestEvent.class);
      for (int i = 20; i < 40; i++) rec.writeEvent(new TestEvent(i));
    }
    multiChunkJfr = Files.createTempFile("jafar-multi-chunk-test", ".jfr");
    Files.write(multiChunkJfr, bos.toByteArray());
  }

  @AfterAll
  static void deleteTestJfrFiles() throws Exception {
    if (syntheticJfr != null) Files.deleteIfExists(syntheticJfr);
    if (multiChunkJfr != null) Files.deleteIfExists(multiChunkJfr);
  }

  @Test
  void testIteratorConsumesAllEvents() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    // Count events using iterator
    int iteratorCount = 0;
    try (EventIterator it = EventIterator.open(syntheticJfr, ctx)) {
      while (it.hasNext()) {
        JafarRecordedEvent event = it.next();
        assertNotNull(event);
        assertNotNull(event.type());
        assertNotNull(event.value());
        iteratorCount++;
      }
    }

    // Count events using callback for comparison
    AtomicInteger callbackCount = new AtomicInteger();
    try (UntypedJafarParser p = ctx.newUntypedParser(syntheticJfr)) {
      p.handle((t, v, ctl) -> callbackCount.incrementAndGet());
      p.run();
    }

    // Should process same number of events
    assertTrue(iteratorCount > 0, "Expected at least one event");
    assertEquals(
        callbackCount.get(),
        iteratorCount,
        "Iterator and callback should process same number of events");
  }

  @Test
  void testIteratorEarlyTermination() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    int limit = 10;
    int count = 0;

    try (EventIterator it = EventIterator.open(syntheticJfr, ctx)) {
      while (it.hasNext() && count < limit) {
        JafarRecordedEvent event = it.next();
        assertNotNull(event);
        count++;
      }
    }

    assertEquals(limit, count, "Should stop at limit");
  }

  @Test
  void testIteratorAllowsMutableVariables() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    // Plain variables - no AtomicInteger needed!
    int counter = 0;
    List<String> eventTypes = new ArrayList<>();

    try (EventIterator it = EventIterator.open(syntheticJfr, ctx)) {
      while (it.hasNext()) {
        JafarRecordedEvent event = it.next();
        counter++; // Can mutate directly
        eventTypes.add(event.typeName()); // Can mutate collection

        if (counter > 5) {
          break; // Easy early exit
        }
      }
    }

    assertEquals(6, counter);
    assertEquals(6, eventTypes.size());
  }

  @Test
  void testIteratorEventDataIntegrity() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    try (EventIterator it = EventIterator.open(syntheticJfr, ctx)) {
      if (it.hasNext()) {
        JafarRecordedEvent event = it.next();

        // Verify record fields
        assertNotNull(event.type(), "Event type should not be null");
        assertNotNull(event.value(), "Event value map should not be null");
        assertTrue(event.streamPosition() >= 0, "Stream position should be non-negative");
        assertNotNull(event.chunkInfo(), "ChunkInfo should not be null");

        // Verify helper methods
        assertNotNull(event.typeName(), "Type name should not be null");
        assertTrue(event.typeId() > 0, "Type ID should be positive");

        // Verify immutability - value map should be unmodifiable
        assertThrows(
            UnsupportedOperationException.class,
            () -> {
              event.value().put("testKey", "testValue");
            });
      }
    }
  }

  @Test
  void testIteratorContractCompliance() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    try (EventIterator it = EventIterator.open(syntheticJfr, ctx)) {
      // hasNext() should be idempotent
      if (it.hasNext()) {
        assertTrue(it.hasNext(), "Multiple hasNext() calls should return same result");
        assertTrue(it.hasNext(), "Multiple hasNext() calls should return same result");

        // next() should advance
        JafarRecordedEvent first = it.next();
        assertNotNull(first);

        // If there's another event, it should be different
        if (it.hasNext()) {
          JafarRecordedEvent second = it.next();
          assertNotNull(second);
          assertNotSame(first, second, "Consecutive events should be different objects");
        }
      }

      // Consume all remaining events
      while (it.hasNext()) {
        it.next();
      }

      // After exhaustion, hasNext() should return false
      assertFalse(it.hasNext(), "hasNext() should return false when exhausted");

      // next() should throw when exhausted
      assertThrows(NoSuchElementException.class, it::next);
    }
  }

  @Test
  void testIteratorWithCustomBufferSize() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    // Small buffer — all 100 events must arrive regardless of buffer size
    int count = 0;
    try (EventIterator it = EventIterator.open(syntheticJfr, ctx, 10)) {
      while (it.hasNext()) {
        it.next();
        count++;
      }
    }

    assertEquals(100, count, "Should deliver all events with small buffer");

    // Large buffer
    count = 0;
    try (EventIterator it = EventIterator.open(syntheticJfr, ctx, 5000)) {
      while (it.hasNext()) {
        it.next();
        count++;
      }
    }

    assertEquals(100, count, "Should deliver all events with large buffer");
  }

  @Test
  void testIteratorInvalidBufferSize() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    assertThrows(
        IllegalArgumentException.class,
        () -> {
          EventIterator.open(syntheticJfr, ctx, 0);
        });

    assertThrows(
        IllegalArgumentException.class,
        () -> {
          EventIterator.open(syntheticJfr, ctx, -1);
        });
  }

  @Test
  void testIteratorCloseWithoutConsumption() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    // Open and close without consuming
    try (EventIterator it = EventIterator.open(syntheticJfr, ctx)) {
      // Don't consume any events
    }

    // Should not throw or leak resources
  }

  @Test
  void testIteratorMultipleClose() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    EventIterator it = EventIterator.open(syntheticJfr, ctx);

    // First close
    it.close();

    // Second close should be safe (idempotent)
    it.close();

    // Third close
    it.close();
  }

  @Test
  void testIteratorDefaultBufferSize() throws Exception {
    // Should use default buffer size (1000) and deliver all 100 events
    int count = 0;
    try (EventIterator it = EventIterator.open(syntheticJfr)) {
      while (it.hasNext()) {
        it.next();
        count++;
      }
    }

    assertEquals(100, count, "Should deliver all events with default buffer");
  }

  @Test
  void testIteratorChunkInfoAvailable() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    try (EventIterator it = EventIterator.open(syntheticJfr, ctx)) {
      if (it.hasNext()) {
        JafarRecordedEvent event = it.next();

        assertNotNull(event.chunkInfo(), "ChunkInfo should be available");
        assertNotNull(event.chunkInfo().startTime(), "ChunkInfo should have start time");
        assertNotNull(event.chunkInfo().duration(), "ChunkInfo should have duration");
        assertTrue(event.chunkInfo().size() >= 0, "ChunkInfo should have valid size");
      }
    }
  }

  @Test
  void testIteratorConstantPoolFieldsAreResolvable() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    int cpFieldsResolved = 0;
    try (EventIterator it = EventIterator.open(syntheticJfr, ctx)) {
      while (it.hasNext()) {
        JafarRecordedEvent event = it.next();
        for (Map.Entry<String, Object> entry : event.value().entrySet()) {
          Object value = entry.getValue();
          if (value instanceof ComplexType) {
            // Lazy CP reference — resolve it
            Map<String, Object> resolved = ((ComplexType) value).getValue();
            assertNotNull(
                resolved,
                "ComplexType field '"
                    + entry.getKey()
                    + "' in event "
                    + event.typeName()
                    + " should resolve to a non-null map");
            assertFalse(
                resolved.isEmpty(),
                "Resolved CP field '" + entry.getKey() + "' should have at least one key");
            cpFieldsResolved++;
          }
        }
      }
    }
    assertTrue(cpFieldsResolved > 0, "Expected at least one CP-backed field to be resolved");
  }

  @Test
  void testIteratorFieldValuesMatchCallback() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    // Collect field values via callback-based parser
    List<Map<String, Object>> callbackValues = new ArrayList<>();
    try (UntypedJafarParser p = ctx.newUntypedParser(syntheticJfr)) {
      p.handle((type, value, ctl) -> callbackValues.add(Values.resolvedDeep(value)));
      p.run();
    }

    // Collect the same events via EventIterator
    List<Map<String, Object>> iteratorValues = new ArrayList<>();
    try (EventIterator it = EventIterator.open(syntheticJfr, ctx)) {
      while (it.hasNext()) {
        JafarRecordedEvent event = it.next();
        iteratorValues.add(Values.resolvedDeep(event.value()));
      }
    }

    assertEquals(
        callbackValues.size(),
        iteratorValues.size(),
        "Iterator and callback should produce the same number of events");
    // Use recursive comparison so that Object[] values (e.g. stackTrace frames) are compared by
    // content, not by reference identity.
    for (int i = 0; i < callbackValues.size(); i++) {
      assertThat(iteratorValues.get(i))
          .as("Event %d field values should match between callback and iterator", i)
          .usingRecursiveComparison()
          .isEqualTo(callbackValues.get(i));
    }
  }

  @Test
  void testIteratorMultiChunkCpResolution() throws Exception {
    ParsingContext ctx = ParsingContext.create();

    int totalEvents = 0;
    int cpFieldsResolved = 0;
    try (EventIterator it = EventIterator.open(multiChunkJfr, ctx)) {
      while (it.hasNext()) {
        JafarRecordedEvent event = it.next();
        totalEvents++;
        for (Map.Entry<String, Object> entry : event.value().entrySet()) {
          Object value = entry.getValue();
          if (value instanceof ComplexType) {
            Map<String, Object> resolved = ((ComplexType) value).getValue();
            assertNotNull(
                resolved,
                "CP field '"
                    + entry.getKey()
                    + "' in event "
                    + event.typeName()
                    + " should resolve across chunk boundary");
            cpFieldsResolved++;
          }
        }
      }
    }
    assertEquals(40, totalEvents, "Should see events from both chunks");
    assertTrue(cpFieldsResolved > 0, "CP-backed fields should resolve in a multi-chunk recording");
  }
}

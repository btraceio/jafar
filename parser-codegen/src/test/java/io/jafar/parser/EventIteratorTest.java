package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.EventIterator;
import io.jafar.parser.api.JafarRecordedEvent;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class EventIteratorTest {

  @Test
  void testIteratorConsumesAllEvents() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    // Count events using iterator
    int iteratorCount = 0;
    try (EventIterator it = EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx)) {
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
    try (UntypedJafarParser p = ctx.newUntypedParser(Paths.get(new File(uri).getAbsolutePath()))) {
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
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    int limit = 10;
    int count = 0;

    try (EventIterator it = EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx)) {
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
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    // Plain variables - no AtomicInteger needed!
    int counter = 0;
    List<String> eventTypes = new ArrayList<>();

    try (EventIterator it = EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx)) {
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
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    try (EventIterator it = EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx)) {
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
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    try (EventIterator it = EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx)) {
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
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    // Small buffer
    int count = 0;
    try (EventIterator it =
        EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx, 10)) {
      while (it.hasNext()) {
        it.next();
        count++;
        if (count > 20) break;
      }
    }

    assertTrue(count > 0, "Should process events with small buffer");

    // Large buffer
    count = 0;
    try (EventIterator it =
        EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx, 5000)) {
      while (it.hasNext()) {
        it.next();
        count++;
        if (count > 20) break;
      }
    }

    assertTrue(count > 0, "Should process events with large buffer");
  }

  @Test
  void testIteratorInvalidBufferSize() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    assertThrows(
        IllegalArgumentException.class,
        () -> {
          EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx, 0);
        });

    assertThrows(
        IllegalArgumentException.class,
        () -> {
          EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx, -1);
        });
  }

  @Test
  void testIteratorCloseWithoutConsumption() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    // Open and close without consuming
    try (EventIterator it = EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx)) {
      // Don't consume any events
    }

    // Should not throw or leak resources
  }

  @Test
  void testIteratorMultipleClose() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    EventIterator it = EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx);

    // First close
    it.close();

    // Second close should be safe (idempotent)
    it.close();

    // Third close
    it.close();
  }

  @Test
  void testIteratorDefaultBufferSize() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();

    // Should use default buffer size (1000)
    int count = 0;
    try (EventIterator it = EventIterator.open(Paths.get(new File(uri).getAbsolutePath()))) {
      while (it.hasNext()) {
        it.next();
        count++;
        if (count > 100) break;
      }
    }

    assertTrue(count > 0, "Should process events with default buffer");
  }

  @Test
  void testIteratorChunkInfoAvailable() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();

    try (EventIterator it = EventIterator.open(Paths.get(new File(uri).getAbsolutePath()), ctx)) {
      if (it.hasNext()) {
        JafarRecordedEvent event = it.next();

        assertNotNull(event.chunkInfo(), "ChunkInfo should be available");
        assertNotNull(event.chunkInfo().startTime(), "ChunkInfo should have start time");
        assertNotNull(event.chunkInfo().duration(), "ChunkInfo should have duration");
        assertTrue(event.chunkInfo().size() >= 0, "ChunkInfo should have valid size");
      }
    }
  }
}

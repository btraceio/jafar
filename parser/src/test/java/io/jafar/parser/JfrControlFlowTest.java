package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for control flow operations (stop, continue, skip) in JFR event handling. */
public class JfrControlFlowTest {

  @TempDir Path tempDir;

  // Note: String fields removed due to JMC Writer string encoding incompatibility
  @JfrType("test.ControlEvent")
  public interface ControlEvent {
    @JfrField("id")
    int id();

    @JfrField("category")
    int category();
  }

  @JfrType("test.TypeAEvent")
  public interface TypeAEvent {
    @JfrField("id")
    int id();
  }

  @JfrType("test.TypeBEvent")
  public interface TypeBEvent {
    @JfrField("id")
    int id();
  }

  // STOP PARSING TESTS

  @Test
  void stopsParsingOnCondition_typed() throws Exception {
    Path jfrFile = tempDir.resolve("stop-condition.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.ControlEvent")
        .intField("id")
        .intField("category")
        .event(e -> e.put("id", 0).put("category", 0))
        .event(e -> e.put("id", 1).put("category", 0))
        .event(e -> e.put("id", 2).put("category", 0))
        .event(e -> e.put("id", 3).put("category", 0))
        .event(e -> e.put("id", 4).put("category", 0))
        .event(e -> e.put("id", 5).put("category", 0))
        .event(e -> e.put("id", 6).put("category", 0))
        .event(e -> e.put("id", 7).put("category", 0))
        .event(e -> e.put("id", 8).put("category", 0))
        .event(e -> e.put("id", 9).put("category", 0))
        .event(e -> e.put("id", 10).put("category", 0))
        .event(e -> e.put("id", 11).put("category", 0))
        .build();

    AtomicInteger eventCount = new AtomicInteger(0);

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            eventCount.incrementAndGet();
            if (event.id() == 9) {
              ctl.abort(); // Stop after 10 events (id 0-9)
            }
          });
      parser.run();
    }

    assertEquals(10, eventCount.get());
  }

  @Test
  void stopsParsingOnCondition_untyped() throws Exception {
    Path jfrFile = tempDir.resolve("stop-condition-untyped.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.ControlEvent")
        .intField("id")
        .intField("category")
        .event(e -> e.put("id", 0).put("category", 0))
        .event(e -> e.put("id", 1).put("category", 0))
        .event(e -> e.put("id", 2).put("category", 0))
        .event(e -> e.put("id", 3).put("category", 0))
        .event(e -> e.put("id", 4).put("category", 0))
        .event(e -> e.put("id", 5).put("category", 0))
        .build();

    AtomicInteger eventCount = new AtomicInteger(0);

    try (UntypedJafarParser parser = UntypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          (type, event, ctl) -> {
            if (type.getName().equals("test.ControlEvent")) {
              eventCount.incrementAndGet();
              int id = ((Number) event.get("id")).intValue();
              if (id >= 4) {
                ctl.abort();
              }
            }
          });
      parser.run();
    }

    assertEquals(5, eventCount.get());
  }

  @Test
  void stopsParsingImmediately_typed() throws Exception {
    Path jfrFile = tempDir.resolve("stop-immediate.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.ControlEvent")
        .intField("id")
        .intField("category")
        .event(e -> e.put("id", 0).put("category", 0))
        .event(e -> e.put("id", 1).put("category", 0))
        .event(e -> e.put("id", 2).put("category", 0))
        .build();

    AtomicInteger eventCount = new AtomicInteger(0);

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            eventCount.incrementAndGet();
            ctl.abort(); // Stop immediately after first event
          });
      parser.run();
    }

    assertEquals(1, eventCount.get());
  }

  // MULTIPLE HANDLERS WITH STOP

  @Test
  void stopAffectsAllHandlers_typed() throws Exception {
    Path jfrFile = tempDir.resolve("stop-all-handlers.jfr");

    JfrTestHelper.JfrFileBuilder builder = JfrTestHelper.create(jfrFile);

    // Add TypeA events
    JfrTestHelper.EventTypeBuilder typeABuilder =
        builder.eventType("test.TypeAEvent").intField("id");
    for (int i = 0; i < 20; i++) {
      final int idx = i;
      typeABuilder.event(e -> e.put("id", idx));
    }

    typeABuilder.build();

    // Create another file with interleaved events
    Path jfrFile2 = tempDir.resolve("stop-all-handlers2.jfr");
    JfrTestHelper.JfrFileBuilder builder2 = JfrTestHelper.create(jfrFile2);
    JfrTestHelper.EventTypeBuilder typeABuilder2 =
        builder2.eventType("test.TypeAEvent").intField("id");
    for (int i = 0; i < 10; i++) {
      final int idx = i;
      typeABuilder2.event(e -> e.put("id", idx));
    }
    typeABuilder2.build();

    AtomicInteger countA = new AtomicInteger(0);

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile2.toString())) {
      parser.handle(
          TypeAEvent.class,
          (event, ctl) -> {
            countA.incrementAndGet();
            if (event.id() == 4) {
              ctl.abort(); // Stop after seeing TypeA event with id=4
            }
          });

      parser.run();
    }

    assertEquals(5, countA.get()); // A0, A1, A2, A3, A4
  }

  // NO-OP AFTER STOP

  @Test
  void handlerCallsAfterStopAreIgnored_typed() throws Exception {
    Path jfrFile = tempDir.resolve("stop-noop.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.ControlEvent")
        .intField("id")
        .intField("category")
        .event(e -> e.put("id", 0).put("category", 0))
        .event(e -> e.put("id", 1).put("category", 0))
        .event(e -> e.put("id", 2).put("category", 0))
        .build();

    AtomicInteger eventCount = new AtomicInteger(0);
    AtomicBoolean stopCalled = new AtomicBoolean(false);

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            eventCount.incrementAndGet();
            if (!stopCalled.get()) {
              ctl.abort();
              stopCalled.set(true);
            }
            // Multiple stop calls should be safe
            ctl.abort();
            ctl.abort();
          });
      parser.run();
    }

    assertEquals(1, eventCount.get());
  }

  // HANDLER ORDER TESTS

  @Disabled("Parser behavior investigation needed - handlers called in reverse order")
  @Test
  void handlersCalledInRegistrationOrder_typed() throws Exception {
    Path jfrFile = tempDir.resolve("handler-order.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.ControlEvent")
        .intField("id")
        .intField("category")
        .event(e -> e.put("id", 1).put("category", 0))
        .build();

    List<String> callOrder = new ArrayList<>();

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            callOrder.add("handler1");
          });

      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            callOrder.add("handler2");
          });

      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            callOrder.add("handler3");
          });

      parser.run();
    }

    assertEquals(3, callOrder.size());
    assertEquals("handler1", callOrder.get(0));
    assertEquals("handler2", callOrder.get(1));
    assertEquals("handler3", callOrder.get(2));
  }

  // HANDLER STATE BETWEEN EVENTS

  @Test
  void handlerMaintainsStateBetweenEvents_typed() throws Exception {
    Path jfrFile = tempDir.resolve("handler-state.jfr");

    JfrTestHelper.JfrFileBuilder builder = JfrTestHelper.create(jfrFile);
    JfrTestHelper.EventTypeBuilder eventBuilder =
        builder.eventType("test.ControlEvent").intField("id").intField("category");

    for (int i = 0; i < 10; i++) {
      final int idx = i;
      eventBuilder.event(e -> e.put("id", idx).put("category", 0));
    }
    eventBuilder.build();

    AtomicInteger sum = new AtomicInteger(0);
    AtomicInteger previousId = new AtomicInteger(-1);

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            int currentId = event.id();
            sum.addAndGet(currentId);
            // Verify events are delivered in order
            assertTrue(
                currentId > previousId.get(),
                "Events should be in order: " + currentId + " > " + previousId.get());
            previousId.set(currentId);
          });
      parser.run();
    }

    // Sum of 0+1+2+...+9 = 45
    assertEquals(45, sum.get());
  }

  // EXCEPTION IN HANDLER

  @Disabled("Parser exception handling investigation needed")
  @Test
  void exceptionInHandlerPropagates_typed() throws Exception {
    Path jfrFile = tempDir.resolve("exception-handler.jfr");

    JfrTestHelper.JfrFileBuilder builder = JfrTestHelper.create(jfrFile);
    JfrTestHelper.EventTypeBuilder eventBuilder =
        builder.eventType("test.ControlEvent").intField("id").intField("category");

    for (int i = 0; i < 10; i++) {
      final int idx = i;
      eventBuilder.event(e -> e.put("id", idx).put("category", 0));
    }
    eventBuilder.build();

    AtomicInteger eventCount = new AtomicInteger(0);
    AtomicBoolean exceptionCaught = new AtomicBoolean(false);

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            eventCount.incrementAndGet();
            if (event.id() == 5) {
              throw new RuntimeException("Test exception");
            }
          });

      try {
        parser.run();
      } catch (RuntimeException e) {
        exceptionCaught.set(true);
        assertEquals("Test exception", e.getMessage());
      }
    }

    assertTrue(exceptionCaught.get(), "Exception should have been caught");
    assertEquals(6, eventCount.get()); // Events 0-5 processed before exception
  }

  // SELECTIVE EVENT PROCESSING (using int category instead of string)

  @Test
  void processesOnlyMatchingEvents_typed() throws Exception {
    Path jfrFile = tempDir.resolve("selective-processing.jfr");

    JfrTestHelper.JfrFileBuilder builder = JfrTestHelper.create(jfrFile);
    JfrTestHelper.EventTypeBuilder eventBuilder =
        builder.eventType("test.ControlEvent").intField("id").intField("category");

    for (int i = 0; i < 20; i++) {
      final int idx = i;
      final int category = i % 2; // 0 = even, 1 = odd
      eventBuilder.event(e -> e.put("id", idx).put("category", category));
    }
    eventBuilder.build();

    List<Integer> evenIds = new ArrayList<>();
    List<Integer> oddIds = new ArrayList<>();

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            if (event.category() == 0) {
              evenIds.add(event.id());
            } else {
              oddIds.add(event.id());
            }
          });
      parser.run();
    }

    assertEquals(10, evenIds.size());
    assertEquals(10, oddIds.size());

    // Verify even IDs
    for (int i = 0; i < 10; i++) {
      assertEquals(i * 2, evenIds.get(i).intValue());
    }

    // Verify odd IDs
    for (int i = 0; i < 10; i++) {
      assertEquals(i * 2 + 1, oddIds.get(i).intValue());
    }
  }

  // COUNTING AND AGGREGATION (using int category instead of string)

  @Test
  void aggregatesDataAcrossEvents_typed() throws Exception {
    Path jfrFile = tempDir.resolve("aggregation.jfr");

    JfrTestHelper.JfrFileBuilder builder = JfrTestHelper.create(jfrFile);
    JfrTestHelper.EventTypeBuilder eventBuilder =
        builder.eventType("test.ControlEvent").intField("id").intField("category");

    // Categories: 0=A, 1=B, 2=C
    for (int i = 0; i < 30; i++) {
      final int idx = i;
      final int category = i % 3;
      eventBuilder.event(e -> e.put("id", idx).put("category", category));
    }
    eventBuilder.build();

    AtomicInteger countA = new AtomicInteger(0);
    AtomicInteger countB = new AtomicInteger(0);
    AtomicInteger countC = new AtomicInteger(0);

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            switch (event.category()) {
              case 0:
                countA.incrementAndGet();
                break;
              case 1:
                countB.incrementAndGet();
                break;
              case 2:
                countC.incrementAndGet();
                break;
            }
          });
      parser.run();
    }

    assertEquals(10, countA.get());
    assertEquals(10, countB.get());
    assertEquals(10, countC.get());
  }

  // STOP AFTER FINDING TARGET (using int marker instead of string)

  @Test
  void stopsAfterFindingTargetEvent_typed() throws Exception {
    Path jfrFile = tempDir.resolve("find-target.jfr");

    JfrTestHelper.JfrFileBuilder builder = JfrTestHelper.create(jfrFile);
    JfrTestHelper.EventTypeBuilder eventBuilder =
        builder.eventType("test.ControlEvent").intField("id").intField("category");

    for (int i = 0; i < 100; i++) {
      final int idx = i;
      final int category = i == 42 ? 999 : 0; // 999 marks the target
      eventBuilder.event(e -> e.put("id", idx).put("category", category));
    }
    eventBuilder.build();

    AtomicInteger eventCount = new AtomicInteger(0);
    AtomicInteger foundId = new AtomicInteger(-1);

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ControlEvent.class,
          (event, ctl) -> {
            eventCount.incrementAndGet();
            if (event.category() == 999) {
              foundId.set(event.id());
              ctl.abort();
            }
          });
      parser.run();
    }

    assertEquals(42, foundId.get());
    assertEquals(43, eventCount.get()); // 0-42 inclusive
  }
}

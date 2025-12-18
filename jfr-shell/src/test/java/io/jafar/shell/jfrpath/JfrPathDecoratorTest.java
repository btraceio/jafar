package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JfrPathDecoratorTest {

  @Test
  void decorateByTimeBasicOverlap() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              // Decorator event: Monitor enter from time 1000 to 2000 on thread 1
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.JavaMonitorEnter",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          1000L,
                          "duration",
                          1000L,
                          "monitorClass",
                          "MyLock")));

              // Primary event 1: Execution sample at time 1500 on thread 1 (overlaps)
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          1500L,
                          "stackTrace",
                          "sample1")));

              // Primary event 2: Execution sample at time 3000 on thread 1 (no overlap)
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          3000L,
                          "stackTrace",
                          "sample2")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorEnter, fields=monitorClass)");

    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());

    // First event should have decorator
    Map<String, Object> event1 = out.get(0);
    assertEquals("sample1", event1.get("stackTrace"));
    assertEquals("MyLock", event1.get("$decorator.monitorClass"));

    // Second event should not have decorator (no overlap)
    Map<String, Object> event2 = out.get(1);
    assertEquals("sample2", event2.get("stackTrace"));
    assertNull(event2.get("$decorator.monitorClass"));
  }

  @Test
  void decorateByTimeThreadFiltering() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              // Decorator on thread 1
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.JavaMonitorEnter",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          1000L,
                          "duration",
                          1000L,
                          "monitorClass",
                          "Lock1")));

              // Primary event on thread 1 (should match)
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          1500L,
                          "name",
                          "thread1-sample")));

              // Primary event on thread 2 (should NOT match)
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 2L),
                          "startTime",
                          1500L,
                          "name",
                          "thread2-sample")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorEnter, fields=monitorClass)");

    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());

    // Thread 1 event should have decorator
    Map<String, Object> thread1Event = out.get(0);
    assertEquals("thread1-sample", thread1Event.get("name"));
    assertEquals("Lock1", thread1Event.get("$decorator.monitorClass"));

    // Thread 2 event should not have decorator
    Map<String, Object> thread2Event = out.get(1);
    assertEquals("thread2-sample", thread2Event.get("name"));
    assertNull(thread2Event.get("$decorator.monitorClass"));
  }

  @Test
  void decorateByTimeEdgeCases() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              // Decorator: 1000-2000
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.JavaMonitorEnter",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          1000L,
                          "duration",
                          1000L,
                          "monitorClass",
                          "Lock")));

              // Event at exactly start time (should overlap)
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          1000L,
                          "duration",
                          100L,
                          "name",
                          "at-start")));

              // Event ending exactly at decorator start (should NOT overlap)
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          900L,
                          "duration",
                          100L,
                          "name",
                          "before-start")));

              // Event starting exactly at decorator end (should NOT overlap)
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          2000L,
                          "duration",
                          100L,
                          "name",
                          "after-end")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorEnter, fields=monitorClass)");

    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(3, out.size());

    // Event at start should overlap
    assertEquals("at-start", out.get(0).get("name"));
    assertEquals("Lock", out.get(0).get("$decorator.monitorClass"));

    // Event before should not overlap
    assertEquals("before-start", out.get(1).get("name"));
    assertNull(out.get(1).get("$decorator.monitorClass"));

    // Event after should not overlap
    assertEquals("after-end", out.get(2).get("name"));
    assertNull(out.get(2).get("$decorator.monitorClass"));
  }

  @Test
  void decorateByKeySimpleMatch() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              // Decorator events with correlation IDs
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "RequestStart",
                      Map.of(
                          "thread",
                          Map.of("javaThreadId", 1L),
                          "requestId",
                          "req-123",
                          "endpoint",
                          "/api/users")));

              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "RequestStart",
                      Map.of(
                          "thread",
                          Map.of("javaThreadId", 2L),
                          "requestId",
                          "req-456",
                          "endpoint",
                          "/api/orders")));

              // Primary events
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "sampledThread", Map.of("javaThreadId", 1L), "method", "getUserData")));

              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "sampledThread", Map.of("javaThreadId", 2L), "method", "getOrderData")));

              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of("sampledThread", Map.of("javaThreadId", 3L), "method", "cleanup")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | decorateByKey(RequestStart, key=sampledThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=requestId,endpoint)");

    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(3, out.size());

    // Thread 1 event should have decorator
    Map<String, Object> event1 = out.get(0);
    assertEquals("getUserData", event1.get("method"));
    assertEquals("req-123", event1.get("$decorator.requestId"));
    assertEquals("/api/users", event1.get("$decorator.endpoint"));

    // Thread 2 event should have decorator
    Map<String, Object> event2 = out.get(1);
    assertEquals("getOrderData", event2.get("method"));
    assertEquals("req-456", event2.get("$decorator.requestId"));
    assertEquals("/api/orders", event2.get("$decorator.endpoint"));

    // Thread 3 event should not have decorator
    Map<String, Object> event3 = out.get(2);
    assertEquals("cleanup", event3.get("method"));
    assertNull(event3.get("$decorator.requestId"));
    assertNull(event3.get("$decorator.endpoint"));
  }

  @Test
  void decorateByKeyMissingKey() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              // Decorator with key
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "RequestStart",
                      Map.of(
                          "thread",
                          Map.of("javaThreadId", 1L),
                          "requestId",
                          "req-123",
                          "endpoint",
                          "/api/users")));

              // Primary event with key
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of("sampledThread", Map.of("javaThreadId", 1L), "method", "hasKey")));

              // Primary event without key (missing field)
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.ExecutionSample", Map.of("method", "noKey")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | decorateByKey(RequestStart, key=sampledThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=requestId)");

    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());

    // Event with key should have decorator
    assertEquals("hasKey", out.get(0).get("method"));
    assertEquals("req-123", out.get(0).get("$decorator.requestId"));

    // Event without key should not have decorator
    assertEquals("noKey", out.get(1).get("method"));
    assertNull(out.get(1).get("$decorator.requestId"));
  }

  @Test
  void decoratedEventMapBehavior() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "RequestStart",
                      Map.of(
                          "thread",
                          Map.of("javaThreadId", 1L),
                          "requestId",
                          "req-123",
                          "endpoint",
                          "/api/users",
                          "userId",
                          "user-456")));

              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "sampledThread",
                          Map.of("javaThreadId", 1L),
                          "method",
                          "test",
                          "primary",
                          "value")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | decorateByKey(RequestStart, key=sampledThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=requestId,endpoint)");

    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    Map<String, Object> decorated = out.get(0);

    // Primary fields should be accessible
    assertEquals("test", decorated.get("method"));
    assertEquals("value", decorated.get("primary"));

    // Only specified decorator fields should be accessible
    assertEquals("req-123", decorated.get("$decorator.requestId"));
    assertEquals("/api/users", decorated.get("$decorator.endpoint"));

    // Non-requested decorator field should not be accessible
    assertNull(decorated.get("$decorator.userId"));

    // containsKey should work correctly
    assertTrue(decorated.containsKey("method"));
    assertTrue(decorated.containsKey("$decorator.requestId"));
    assertFalse(decorated.containsKey("$decorator.userId"));

    // entrySet should contain both primary and decorator fields
    var entries = decorated.entrySet();
    assertTrue(entries.stream().anyMatch(e -> e.getKey().equals("method")));
    assertTrue(entries.stream().anyMatch(e -> e.getKey().equals("$decorator.requestId")));
    assertTrue(entries.stream().anyMatch(e -> e.getKey().equals("$decorator.endpoint")));
    assertFalse(entries.stream().anyMatch(e -> e.getKey().equals("$decorator.userId")));
  }

  @Test
  void decorateByTimeWithEventDuration() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              // Decorator: 1000-2000
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.JavaMonitorWait",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          1000L,
                          "duration",
                          1000L,
                          "monitorClass",
                          "Lock")));

              // Primary event: 1800-2100 (overlaps 1800-2000)
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead",
                      Map.of(
                          "eventThread",
                          Map.of("javaThreadId", 1L),
                          "startTime",
                          1800L,
                          "duration",
                          300L,
                          "path",
                          "/etc/hosts")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.FileRead | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass)");

    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());

    // Event should have decorator (overlap detected)
    assertEquals("/etc/hosts", out.get(0).get("path"));
    assertEquals("Lock", out.get(0).get("$decorator.monitorClass"));
  }

  @Test
  void parseDecorationSyntax() {
    // Test decorateByTime parsing
    var q1 =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorEnter, fields=monitorClass,duration)");
    assertNotNull(q1);
    assertEquals(1, q1.pipeline.size());
    assertTrue(q1.pipeline.get(0) instanceof JfrPath.DecorateByTimeOp);

    JfrPath.DecorateByTimeOp op1 = (JfrPath.DecorateByTimeOp) q1.pipeline.get(0);
    assertEquals("jdk.JavaMonitorEnter", op1.decoratorEventType);
    assertEquals(List.of("monitorClass", "duration"), op1.decoratorFields);

    // Test decorateByKey parsing
    var q2 =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | decorateByKey(RequestStart, key=sampledThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=requestId,endpoint)");
    assertNotNull(q2);
    assertEquals(1, q2.pipeline.size());
    assertTrue(q2.pipeline.get(0) instanceof JfrPath.DecorateByKeyOp);

    JfrPath.DecorateByKeyOp op2 = (JfrPath.DecorateByKeyOp) q2.pipeline.get(0);
    assertEquals("RequestStart", op2.decoratorEventType);
    assertEquals(List.of("requestId", "endpoint"), op2.decoratorFields);
    assertTrue(op2.primaryKey instanceof JfrPath.PathKeyExpr);
    assertTrue(op2.decoratorKey instanceof JfrPath.PathKeyExpr);
  }
}

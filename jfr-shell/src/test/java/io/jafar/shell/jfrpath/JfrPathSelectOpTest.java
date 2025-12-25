package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JfrPathSelectOpTest {

  @Test
  void selectsSingleField() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of("startTime", 1000L, "duration", 50L, "threadName", "main")));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of("startTime", 2000L, "duration", 100L, "threadName", "worker")));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | select(startTime)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());
    assertEquals(1, out.get(0).size());
    assertEquals(1000L, out.get(0).get("startTime"));
    assertEquals(1, out.get(1).size());
    assertEquals(2000L, out.get(1).get("startTime"));
  }

  @Test
  void selectsMultipleFields() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of("startTime", 1000L, "duration", 50L, "threadName", "main")));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | select(startTime, threadName)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(2, out.get(0).size());
    assertEquals(1000L, out.get(0).get("startTime"));
    assertEquals("main", out.get(0).get("threadName"));
    assertFalse(out.get(0).containsKey("duration"));
  }

  @Test
  void selectsNestedField() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "startTime",
                          1000L,
                          "eventThread",
                          Map.of("javaThreadId", 42L, "name", "main"))));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | select(eventThread/javaThreadId)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertTrue(out.get(0).containsKey("eventThread"));
    assertTrue(out.get(0).get("eventThread") instanceof Map);

    @SuppressWarnings("unchecked")
    Map<String, Object> eventThread = (Map<String, Object>) out.get(0).get("eventThread");
    assertEquals(42L, eventThread.get("javaThreadId"));
    assertFalse(eventThread.containsKey("name"));
  }

  @Test
  void selectsMultipleNestedFields() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of(
                          "startTime",
                          1000L,
                          "eventThread",
                          Map.of("javaThreadId", 42L, "name", "main", "osName", "Thread-0"))));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | select(eventThread/javaThreadId, eventThread/name)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertTrue(out.get(0).containsKey("eventThread"));

    @SuppressWarnings("unchecked")
    Map<String, Object> eventThread = (Map<String, Object>) out.get(0).get("eventThread");
    assertEquals(42L, eventThread.get("javaThreadId"));
    assertEquals("main", eventThread.get("name"));
    assertFalse(eventThread.containsKey("osName"));
  }

  // Note: Pipeline chaining with multiple operations is not yet fully supported
  // This test is disabled until pipeline chaining is implemented
  // @Test
  void selectAfterGroupByDisabled() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/a.txt", "bytes", 100L)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/a.txt", "bytes", 200L)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/b.txt", "bytes", 300L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead | groupBy(path) | select(key)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());
    // Should only have "key" field, not "value"
    assertEquals(1, out.get(0).size());
    assertTrue(out.get(0).containsKey("key"));
    assertFalse(out.get(0).containsKey("value"));
    assertEquals(1, out.get(1).size());
    assertTrue(out.get(1).containsKey("key"));
    assertFalse(out.get(1).containsKey("value"));
  }

  @Test
  void selectWithFilter() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead",
                      Map.of("path", "/tmp/a.txt", "bytes", 100L, "duration", 10L)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead",
                      Map.of("path", "/tmp/b.txt", "bytes", 200L, "duration", 20L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead[bytes>150] | select(path, bytes)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(2, out.get(0).size());
    assertEquals("/tmp/b.txt", out.get(0).get("path"));
    assertEquals(200L, out.get(0).get("bytes"));
    assertFalse(out.get(0).containsKey("duration"));
  }
}

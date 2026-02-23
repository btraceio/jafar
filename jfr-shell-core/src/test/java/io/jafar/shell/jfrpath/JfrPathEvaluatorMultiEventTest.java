package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Integration tests for multi-event type queries. */
class JfrPathEvaluatorMultiEventTest {

  @Test
  void evaluatesMultiEventTypeQuery() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes())
        .thenReturn(Set.of("jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/a.txt", "bytes", 100)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileWrite", Map.of("path", "/tmp/b.txt", "bytes", 200)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.SocketRead", Map.of("host", "example.com", "bytes", 300)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());
    assertEquals("/tmp/a.txt", out.get(0).get("path"));
    assertEquals("/tmp/b.txt", out.get(1).get("path"));
  }

  @Test
  void multiEventQueryRespectsFilters() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes()).thenReturn(Set.of("jdk.FileRead", "jdk.FileWrite"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/a.txt", "bytes", 100)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/b.txt", "bytes", 1500)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileWrite", Map.of("path", "/tmp/c.txt", "bytes", 200)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileWrite", Map.of("path", "/tmp/d.txt", "bytes", 2000)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite)[bytes>1000]");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());
    assertEquals(1500, out.get(0).get("bytes"));
    assertEquals(2000, out.get(1).get("bytes"));
  }

  @Test
  void multiEventQueryWorksWithCount() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes())
        .thenReturn(Set.of("jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 100)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileWrite", Map.of("bytes", 200)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 150)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.SocketRead", Map.of("bytes", 300)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite) | count()");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(3L, out.get(0).get("count"));
  }

  @Test
  void multiEventQueryWorksWithSum() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes()).thenReturn(Set.of("jdk.FileRead", "jdk.FileWrite"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 100)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileWrite", Map.of("bytes", 200)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 150)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite) | sum(bytes)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(450.0, out.get(0).get("sum"));
  }

  @Test
  void multiEventQueryWorksWithGroupBy() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes()).thenReturn(Set.of("jdk.FileRead", "jdk.FileWrite"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/a.txt", "bytes", 100)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileWrite", Map.of("path", "/tmp/a.txt", "bytes", 200)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/b.txt", "bytes", 150)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite) | groupBy(path)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());

    // Find the groups
    Map<String, Object> groupA =
        out.stream().filter(m -> "/tmp/a.txt".equals(m.get("key"))).findFirst().orElseThrow();
    Map<String, Object> groupB =
        out.stream().filter(m -> "/tmp/b.txt".equals(m.get("key"))).findFirst().orElseThrow();

    assertEquals(2L, groupA.get("count"));
    assertEquals(1L, groupB.get("count"));
  }

  @Test
  void throwsWhenEventTypeNotFound() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes()).thenReturn(Set.of("jdk.FileRead", "jdk.FileWrite"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              // Empty source
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.InvalidType)");

    var ex = assertThrows(IllegalArgumentException.class, () -> eval.evaluate(session, q));
    assertTrue(ex.getMessage().contains("jdk.InvalidType"));
    assertTrue(ex.getMessage().contains("not found"));
  }

  @Test
  void providesHelpfulSuggestionForTypo() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes()).thenReturn(Set.of("jdk.FileRead", "jdk.FileWrite"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              // Empty source
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/(jdk.FileRea|jdk.FileWrite)"); // Typo: "FileRea"

    var ex = assertThrows(IllegalArgumentException.class, () -> eval.evaluate(session, q));
    assertTrue(ex.getMessage().contains("Did you mean"));
    assertTrue(ex.getMessage().contains("jdk.FileRead"));
  }

  @Test
  void worksWithThreeOrMoreEventTypes() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes())
        .thenReturn(Set.of("jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 100)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileWrite", Map.of("bytes", 200)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.SocketRead", Map.of("bytes", 300)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.SocketWrite", Map.of("bytes", 400)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/(jdk.FileRead|jdk.FileWrite|jdk.SocketRead) | count()");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(3L, out.get(0).get("count"));
  }

  @Test
  void singleTypeInParenthesesStillWorks() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes()).thenReturn(Set.of("jdk.FileRead"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 100)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 200)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/(jdk.FileRead) | count()");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(2L, out.get(0).get("count"));
  }

  @Test
  void multiEventWithComplexFiltersAndPipeline() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes()).thenReturn(Set.of("jdk.FileRead", "jdk.FileWrite"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/a.txt", "bytes", 1500)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileWrite", Map.of("path", "/tmp/b.txt", "bytes", 2000)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/c.txt", "bytes", 500)));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileWrite", Map.of("path", "/tmp/d.txt", "bytes", 2500)));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/(jdk.FileRead|jdk.FileWrite)[bytes>1000] | groupBy(path, agg=sum, value=bytes)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    // Filter should have reduced to 3 events (bytes > 1000)
    // groupBy creates 3 groups (one per unique path)
    assertEquals(3, out.size());
    // Verify all groups have their sum values
    for (Map<String, Object> group : out) {
      assertTrue(group.containsKey("key"));
      assertTrue(group.containsKey("sum"));
    }
  }
}

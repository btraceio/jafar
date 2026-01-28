package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JfrPathEvaluatorTest {

  @Test
  void filtersByTypeAndRegexPredicate() throws Exception {
    // Fake session and event source
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        new JfrPathEvaluator.EventSource() {
          @Override
          public void streamEvents(
              Path recording, java.util.function.Consumer<JfrPathEvaluator.Event> consumer) {
            consumer.accept(
                new JfrPathEvaluator.Event(
                    "jdk.ExecutionSample", Map.of("thread", Map.of("name", "main"), "bytes", 42)));
            consumer.accept(
                new JfrPathEvaluator.Event(
                    "jdk.ExecutionSample",
                    Map.of("thread", Map.of("name", "worker-1"), "bytes", 7)));
            consumer.accept(
                new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/etc/hosts")));
          }
        };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.ExecutionSample[thread/name~\"main\"]");
    List<Map<String, Object>> out = eval.evaluate(session, q);
    assertEquals(1, out.size());
    assertEquals("main", ((Map<?, ?>) out.get(0).get("thread")).get("name"));
  }

  @Test
  void numericComparison() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 1500)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 500)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead[bytes>=1000]");
    List<Map<String, Object>> out = eval.evaluate(session, q);
    assertEquals(1, out.size());
    assertEquals(1500, out.get(0).get("bytes"));
  }

  @Test
  void aggregationCountAndStats() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 1500)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 500)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.ExecutionSample", Map.of()));
            };

    var eval = new JfrPathEvaluator(src);

    var qc = JfrPathParser.parse("events/jdk.FileRead | count()");
    List<Map<String, Object>> countRows = eval.evaluate(session, qc);
    assertEquals(1, countRows.size());
    assertEquals(2L, countRows.get(0).get("count"));

    var qs = JfrPathParser.parse("events/jdk.FileRead/bytes | stats()");
    List<Map<String, Object>> statsRows = eval.evaluate(session, qs);
    assertEquals(1, statsRows.size());
    Map<String, Object> r = statsRows.get(0);
    assertEquals(2L, r.get("count"));
    assertEquals(500.0, (Double) r.get("min"), 0.0001);
    assertEquals(1500.0, (Double) r.get("max"), 0.0001);
    assertEquals(1000.0, (Double) r.get("avg"), 0.0001);
    assertEquals(500.0, (Double) r.get("stddev"), 0.0001);

    var qq = JfrPathParser.parse("events/jdk.FileRead/bytes | quantiles(0.5,0.9)");
    List<Map<String, Object>> qrows = eval.evaluate(session, qq);
    assertEquals(1, qrows.size());
    Map<String, Object> qr = qrows.get(0);
    assertEquals(2, qr.get("count"));
    assertEquals(1000.0, (Double) qr.get("p50"), 0.0001);
    assertEquals(1500.0, (Double) qr.get("p90"), 0.0001);
  }

  // ==================== Filter Function Tests ====================

  @Test
  void startsWithFilter_MatchesPrefix() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/tmp/file.txt")));
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/var/log/app.log")));
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/tmp/other.dat")));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead[startsWith(path, \"/tmp/\")]");
    List<Map<String, Object>> out = eval.evaluate(session, q);
    assertEquals(2, out.size());
    assertTrue(out.stream().allMatch(e -> ((String) e.get("path")).startsWith("/tmp/")));
  }

  @Test
  void startsWithFilter_NoMatch() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/var/log/app.log")));
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/etc/hosts")));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead[startsWith(path, \"/tmp/\")]");
    List<Map<String, Object>> out = eval.evaluate(session, q);
    assertEquals(0, out.size());
  }

  @Test
  void endsWithFilter_MatchesSuffix() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/var/log/app.log")));
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/tmp/file.txt")));
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/etc/syslog.log")));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead[endsWith(path, \".log\")]");
    List<Map<String, Object>> out = eval.evaluate(session, q);
    assertEquals(2, out.size());
    assertTrue(out.stream().allMatch(e -> ((String) e.get("path")).endsWith(".log")));
  }

  @Test
  void endsWithFilter_NoMatch() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/tmp/file.txt")));
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/tmp/data.json")));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead[endsWith(path, \".log\")]");
    List<Map<String, Object>> out = eval.evaluate(session, q);
    assertEquals(0, out.size());
  }

  @Test
  void startsWithFilter_NullFieldValue() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 100)));
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/tmp/file.txt")));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead[startsWith(path, \"/tmp/\")]");
    List<Map<String, Object>> out = eval.evaluate(session, q);
    assertEquals(1, out.size());
    assertEquals("/tmp/file.txt", out.get(0).get("path"));
  }

  @Test
  void endsWithFilter_EmptySuffix() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/tmp/file.txt")));
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/var/log/app.log")));
            };

    var eval = new JfrPathEvaluator(src);
    // Empty suffix should match all strings
    var q = JfrPathParser.parse("events/jdk.FileRead[endsWith(path, \"\")]");
    List<Map<String, Object>> out = eval.evaluate(session, q);
    assertEquals(2, out.size());
  }
}

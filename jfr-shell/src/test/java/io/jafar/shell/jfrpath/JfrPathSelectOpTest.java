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
    // With flattened select, leaf segment becomes the column name
    assertTrue(out.get(0).containsKey("javaThreadId"));
    assertEquals(42L, out.get(0).get("javaThreadId"));
    assertFalse(out.get(0).containsKey("name"));
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
    // With flattened select, leaf segments become column names
    assertTrue(out.get(0).containsKey("javaThreadId"));
    assertTrue(out.get(0).containsKey("name"));
    assertEquals(42L, out.get(0).get("javaThreadId"));
    assertEquals("main", out.get(0).get("name"));
    assertFalse(out.get(0).containsKey("osName"));
  }

  @Test
  void selectsNestedFieldsWithDotNotation() throws Exception {
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
    // Test dot notation as alternative to slash notation
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | select(eventThread.javaThreadId)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    // With flattened select, leaf segment becomes the column name
    assertTrue(out.get(0).containsKey("javaThreadId"));
    assertEquals(42L, out.get(0).get("javaThreadId"));
    assertFalse(out.get(0).containsKey("name"));
  }

  @Test
  void selectsMixedSlashAndDotNotation() throws Exception {
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
    // Mix slash and dot notation in same query
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | select(eventThread.javaThreadId, eventThread/name)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    // With flattened select, leaf segments become column names
    assertTrue(out.get(0).containsKey("javaThreadId"));
    assertTrue(out.get(0).containsKey("name"));
    assertEquals(42L, out.get(0).get("javaThreadId"));
    assertEquals("main", out.get(0).get("name"));
    assertFalse(out.get(0).containsKey("osName"));
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

  @Test
  void selectWithStringConcatenation() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/file.txt", "bytes", 1024L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.FileRead | select(path + ' (' + bytes + ' bytes)' as description)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(1, out.get(0).size());
    assertTrue(out.get(0).containsKey("description"));
    assertEquals("/tmp/file.txt (1024 bytes)", out.get(0).get("description"));
  }

  @Test
  void selectWithArithmetic() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("bytes", 2048L, "duration", 1000L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.FileRead | select(bytes / 1024 as kilobytes, duration * 2 as doubleDuration)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(2, out.get(0).size());
    assertTrue(out.get(0).containsKey("kilobytes"));
    assertTrue(out.get(0).containsKey("doubleDuration"));
    assertEquals(2.0, (Double) out.get(0).get("kilobytes"), 0.001);
    assertEquals(2000.0, (Double) out.get(0).get("doubleDuration"), 0.001);
  }

  @Test
  void selectWithUpperFunction() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/tmp/hello.txt")));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead | select(upper(path) as upperPath)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(1, out.get(0).size());
    assertTrue(out.get(0).containsKey("upperPath"));
    assertEquals("/TMP/HELLO.TXT", out.get(0).get("upperPath"));
  }

  @Test
  void selectWithLowerFunction() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/TMP/HELLO.TXT")));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead | select(lower(path) as lowerPath)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(1, out.get(0).size());
    assertTrue(out.get(0).containsKey("lowerPath"));
    assertEquals("/tmp/hello.txt", out.get(0).get("lowerPath"));
  }

  @Test
  void selectWithSubstringFunction() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/longfilename.txt")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse("events/jdk.FileRead | select(substring(path, 0, 10) as shortPath)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(1, out.get(0).size());
    assertTrue(out.get(0).containsKey("shortPath"));
    assertEquals("/tmp/longf", out.get(0).get("shortPath"));
  }

  @Test
  void selectWithLengthFunction() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/tmp/hello.txt")));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead | select(length(path) as pathLength)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(1, out.get(0).size());
    assertTrue(out.get(0).containsKey("pathLength"));
    assertEquals(14, out.get(0).get("pathLength")); // "/tmp/hello.txt" has 14 characters
  }

  @Test
  void selectWithConditional() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 500L)));
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 1500L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse("events/jdk.FileRead | select(if(bytes, 'large', 'small') as size)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());
    assertEquals(1, out.get(0).size());
    assertTrue(out.get(0).containsKey("size"));
    assertEquals("large", out.get(0).get("size")); // 500L is truthy
    assertEquals("large", out.get(1).get("size")); // 1500L is truthy
  }

  @Test
  void selectMixedFieldsAndExpressions() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead",
                      Map.of("path", "/tmp/file.txt", "bytes", 2048L, "duration", 100L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead | select(path, bytes / 1024 as kb, duration)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(3, out.get(0).size());
    assertTrue(out.get(0).containsKey("path"));
    assertTrue(out.get(0).containsKey("kb"));
    assertTrue(out.get(0).containsKey("duration"));
    assertEquals("/tmp/file.txt", out.get(0).get("path"));
    assertEquals(2.0, (Double) out.get(0).get("kb"), 0.001);
    assertEquals(100L, out.get(0).get("duration"));
  }

  @Test
  void selectFieldWithAlias() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample",
                      Map.of("eventThread", Map.of("javaThreadId", 42L, "name", "main"))));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | select(eventThread/javaThreadId as threadId, eventThread/name as threadName)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(2, out.get(0).size());
    assertTrue(out.get(0).containsKey("threadId"));
    assertTrue(out.get(0).containsKey("threadName"));
    assertEquals(42L, out.get(0).get("threadId"));
    assertEquals("main", out.get(0).get("threadName"));
    assertFalse(out.get(0).containsKey("javaThreadId"));
    assertFalse(out.get(0).containsKey("name"));
  }

  @Test
  void selectWithCoalesceFunction() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "/tmp/file.txt")));
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("altPath", "/tmp/other.txt")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.FileRead | select(coalesce(path, altPath, 'unknown') as finalPath)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());
    assertEquals(1, out.get(0).size());
    assertTrue(out.get(0).containsKey("finalPath"));
    assertEquals("/tmp/file.txt", out.get(0).get("finalPath"));
    assertEquals(
        "/tmp/other.txt", out.get(1).get("finalPath")); // coalesce returns first non-null: altPath
  }

  @Test
  void selectWithComplexExpression() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 100L, "count", 5L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead | select((bytes * count) / 1024 as totalKb)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals(1, out.get(0).size());
    assertTrue(out.get(0).containsKey("totalKb"));
    assertEquals(0.48828125, (Double) out.get(0).get("totalKb"), 0.001);
  }

  @Test
  void selectWithSimpleStringTemplate() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/hello.txt", "bytes", 1024L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead | select(\"File: ${path}\" as description)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals("File: /tmp/hello.txt", out.get(0).get("description"));
  }

  @Test
  void selectWithMultipleTemplateExpressions() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/data.bin", "bytes", 2048L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse("events/jdk.FileRead | select(\"${path} (${bytes} bytes)\" as info)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals("/tmp/data.bin (2048 bytes)", out.get(0).get("info"));
  }

  @Test
  void selectWithTemplateAndArithmetic() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/test.log", "bytes", 4096L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.FileRead | select(\"${path}: ${bytes / 1024} KB\" as summary)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals("/tmp/test.log: 4.0 KB", out.get(0).get("summary"));
  }

  @Test
  void selectWithTemplateAndFunction() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/hello.txt", "bytes", 512L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.FileRead | select(\"File: ${upper(path)} - ${bytes} bytes\" as info)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals("File: /TMP/HELLO.TXT - 512 bytes", out.get(0).get("info"));
  }

  @Test
  void selectWithTemplateAndConditional() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead",
                      Map.of("path", "/tmp/big.bin", "bytes", 2000L, "category", "large")));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead",
                      Map.of("path", "/tmp/small.txt", "bytes", 100L, "category", "small")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.FileRead | select(\"${path}: ${upper(category)}\" as status)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(2, out.size());
    assertEquals("/tmp/big.bin: LARGE", out.get(0).get("status"));
    assertEquals("/tmp/small.txt: SMALL", out.get(1).get("status"));
  }

  @Test
  void selectWithNestedFieldInTemplate() throws Exception {
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
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | select(\"Thread ${eventThread/name} (ID: ${eventThread/javaThreadId})\" as threadInfo)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals("Thread main (ID: 42)", out.get(0).get("threadInfo"));
  }

  @Test
  void selectWithTemplateHandlesNullValues() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("bytes", 100L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.FileRead | select(\"Path: ${path}, Bytes: ${bytes}\" as info)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    // Null values should be rendered as empty string
    assertEquals("Path: , Bytes: 100", out.get(0).get("info"));
  }

  @Test
  void selectMixesRegularFieldsAndTemplates() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));

    var src =
        (JfrPathEvaluator.EventSource)
            (rec, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.FileRead", Map.of("path", "/tmp/file.txt", "bytes", 512L)));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse("events/jdk.FileRead | select(path, \"${bytes / 1024} KB\" as sizeKb)");
    List<Map<String, Object>> out = eval.evaluate(session, q);

    assertEquals(1, out.size());
    assertEquals("/tmp/file.txt", out.get(0).get("path"));
    assertEquals("0.5 KB", out.get(0).get("sizeKb"));
  }
}

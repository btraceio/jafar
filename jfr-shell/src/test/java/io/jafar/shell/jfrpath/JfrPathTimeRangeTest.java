package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JfrPathTimeRangeTest {

  @Test
  void timerangeReturnsExpectedFields() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    Path jfr = Path.of("..", "parser", "src", "test", "resources", "test-jfr.jfr");
    when(session.getRecordingPath()).thenReturn(jfr);
    when(session.getFilePath()).thenReturn(jfr);
    when(session.getAvailableEventTypes()).thenReturn(java.util.Set.of("jdk.ExecutionSample"));

    var eval = new JfrPathEvaluator();
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | timerange()");
    List<Map<String, Object>> rows = eval.evaluate(session, q);

    assertEquals(1, rows.size());
    Map<String, Object> result = rows.get(0);

    // Verify expected fields are present
    assertTrue(result.containsKey("count"));
    assertTrue(result.containsKey("field"));
    assertTrue(result.containsKey("minTicks"));
    assertTrue(result.containsKey("maxTicks"));
    assertTrue(result.containsKey("minTime"));
    assertTrue(result.containsKey("maxTime"));
    assertTrue(result.containsKey("durationNanos"));
    assertTrue(result.containsKey("durationMs"));

    // Field should be startTime (default)
    assertEquals("startTime", result.get("field"));

    // Count should be positive
    long count = (Long) result.get("count");
    assertTrue(count > 0, "Expected events to be found");

    // Min/max ticks should be valid
    long minTicks = (Long) result.get("minTicks");
    long maxTicks = (Long) result.get("maxTicks");
    assertTrue(minTicks <= maxTicks, "minTicks should be <= maxTicks");

    // Time strings should not be null
    assertNotNull(result.get("minTime"));
    assertNotNull(result.get("maxTime"));

    // Duration should be non-negative
    long durationNanos = (Long) result.get("durationNanos");
    assertTrue(durationNanos >= 0, "Duration should be non-negative");
  }

  @Test
  void timerangeWithCustomPath() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    Path jfr = Path.of("..", "parser", "src", "test", "resources", "test-jfr.jfr");
    when(session.getRecordingPath()).thenReturn(jfr);
    when(session.getFilePath()).thenReturn(jfr);
    when(session.getAvailableEventTypes()).thenReturn(java.util.Set.of("jdk.ExecutionSample"));

    var eval = new JfrPathEvaluator();
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | timerange(startTime)");
    List<Map<String, Object>> rows = eval.evaluate(session, q);

    assertEquals(1, rows.size());
    assertEquals("startTime", rows.get(0).get("field"));
  }

  @Test
  void timerangeApplyToRows() throws Exception {
    // Test the applyTimeRange method for cached rows
    var eval = new JfrPathEvaluator();

    List<Map<String, Object>> rows =
        List.of(
            Map.of("startTime", 1000L, "name", "a"),
            Map.of("startTime", 2000L, "name", "b"),
            Map.of("startTime", 1500L, "name", "c"));

    List<JfrPath.PipelineOp> pipeline =
        List.of(new JfrPath.TimeRangeOp(List.of("startTime"), List.of(), null));
    List<Map<String, Object>> result = eval.applyToRows(rows, pipeline);

    assertEquals(1, result.size());
    Map<String, Object> r = result.get(0);
    assertEquals(3L, r.get("count"));
    assertEquals(1000L, r.get("minTicks"));
    assertEquals(2000L, r.get("maxTicks"));
    assertEquals(1000L, r.get("durationTicks"));
    // Note: For cached rows, time conversion is not available
    assertNotNull(r.get("note"));
  }

  @Test
  void timerangeEmptyResult() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    Path jfr = Path.of("..", "parser", "src", "test", "resources", "test-jfr.jfr");
    when(session.getRecordingPath()).thenReturn(jfr);
    when(session.getFilePath()).thenReturn(jfr);
    when(session.getAvailableEventTypes()).thenReturn(java.util.Set.of("jdk.NonExistentEvent"));

    var eval = new JfrPathEvaluator();
    // Use a filter that matches no events
    var q = JfrPathParser.parse("events/jdk.NonExistentEvent | timerange()");
    List<Map<String, Object>> rows = eval.evaluate(session, q);

    assertEquals(1, rows.size());
    Map<String, Object> result = rows.get(0);
    assertEquals(0L, result.get("count"));
    assertNull(result.get("minTicks"));
    assertNull(result.get("maxTicks"));
    assertNull(result.get("durationNanos"));
    assertNull(result.get("durationMs"));
    assertNull(result.get("duration"));
  }
}

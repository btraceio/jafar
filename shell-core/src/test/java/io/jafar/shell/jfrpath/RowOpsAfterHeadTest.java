package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * #305: after head(n) (or tail, filter, distinct as the first operator) a projection was dropped,
 * so {@code /b | head(3)} showed whole events and {@code stats()} after it read their first column,
 * and {@code sketch()} there was ignored.
 */
class RowOpsAfterHeadTest {

  /** Five events in order: a = 1..5 first in each, b = 10..50, t = {name: "t" + (i % 2), id: i}. */
  private static List<Map<String, Object>> run(String query) throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));
    JfrPathEvaluator.EventSource src =
        (rec, consumer) -> {
          for (long i = 1; i <= 5; i++) {
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("a", i);
            event.put("b", i * 10);
            Map<String, Object> t = new java.util.LinkedHashMap<>();
            t.put("name", "t" + (i % 2));
            t.put("id", i);
            event.put("t", t);
            // A string constant, as the untyped parser gives one
            event.put("state", Map.of("name", i % 2 == 0 ? "STATE_PARKED" : "STATE_RUNNABLE"));
            consumer.accept(new JfrPathEvaluator.Event("test.Event", event));
          }
        };
    return new JfrPathEvaluator(src).evaluate(session, JfrPathParser.parse(query));
  }

  @Test
  void aScalarProjectionGivesValueRows() throws Exception {
    // As a plain projection and top() name them
    assertEquals(
        List.of(Map.of("value", 10L), Map.of("value", 20L)), run("events/test.Event/b | head(2)"));
    assertEquals(List.of(Map.of("value", 50L)), run("events/test.Event/b | tail(1)"));
    assertEquals(
        List.of(Map.of("value", 40L), Map.of("value", 50L)),
        run("events/test.Event/b | filter(value > 30)"));
    // A nested path projects the leaf
    assertEquals(
        List.of(Map.of("value", "t1"), Map.of("value", "t0")),
        run("events/test.Event/t/name | head(2)"));
  }

  @Test
  void aStringConstantIsAValueNotAField() throws Exception {
    // Was a "name" column: filter(value = ...) matched nothing
    assertEquals(
        List.of(Map.of("value", "STATE_RUNNABLE"), Map.of("value", "STATE_PARKED")),
        run("events/test.Event/state | head(2)"));
    assertEquals(3, run("events/test.Event/state | filter(value = \"STATE_RUNNABLE\")").size());
    assertEquals(2, run("events/test.Event/state | distinct(value)").size());
  }

  @Test
  void anObjectProjectionGivesItsFields() throws Exception {
    assertEquals(
        List.of(Map.of("name", "t1", "id", 1L), Map.of("name", "t0", "id", 2L)),
        run("events/test.Event/t | head(2)"));
    assertEquals(2, run("events/test.Event/t | distinct(name)").size());
  }

  @Test
  void statsAndSketchAfterHeadReadTheProjection() throws Exception {
    // Sketch alone first: ignored before, the 3 rows came back
    List<Map<String, Object>> sketch = run("events/test.Event/b | head(3) | sketch()");
    assertEquals(1, sketch.size(), sketch.toString());
    Map<String, Object> row = sketch.get(0);
    assertEquals(3L, ((Number) row.get("count")).longValue(), row.toString());
    assertEquals(10.0, ((Number) row.get("min")).doubleValue(), row.toString());
    assertEquals(20.0, ((Number) row.get("avg")).doubleValue(), row.toString());
    assertEquals(20.0, ((Number) row.get("p50")).doubleValue(), row.toString());
    assertEquals(30.0, ((Number) row.get("p90")).doubleValue(), row.toString());
    assertEquals(30.0, ((Number) row.get("p99")).doubleValue(), row.toString());
    // Read the first column (a) before: min 1, not 10
    Map<String, Object> stats = run("events/test.Event/b | head(3) | stats()").get(0);
    assertEquals(10.0, ((Number) stats.get("min")).doubleValue(), stats.toString());
    assertEquals(30.0, ((Number) stats.get("max")).doubleValue(), stats.toString());
    // Without a projection, on a named field
    Map<String, Object> named = run("events/test.Event | head(2) | sketch(b)").get(0);
    assertEquals(2L, ((Number) named.get("count")).longValue(), named.toString());
    assertEquals(20.0, ((Number) named.get("max")).doubleValue(), named.toString());
  }

  @Test
  void anEmptySketchHasNullQuantiles() throws Exception {
    // NaN, which JSON cannot hold, as quantiles() after an operator gave
    Map<String, Object> row = run("events/test.Event/b | filter(value < 0) | sketch()").get(0);
    assertEquals(0L, ((Number) row.get("count")).longValue(), row.toString());
    assertTrue(row.containsKey("p50"), row.toString());
    assertNull(row.get("p50"), row.toString());
    assertNull(row.get("p99"), row.toString());
  }

  @Test
  void aDecoratorAfterAnotherOperatorFailsInsteadOfBeingSkipped() {
    for (String decorator :
        List.of(
            "decorateByKey(test.Event, key=a, decoratorKey=a, fields=b)",
            "decorateByTime(test.Event, fields=b)")) {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class,
              () -> run("events/test.Event | head(2) | " + decorator),
              decorator);
      assertTrue(e.getMessage().contains("must be the first operator"), e.getMessage());
    }
  }
}

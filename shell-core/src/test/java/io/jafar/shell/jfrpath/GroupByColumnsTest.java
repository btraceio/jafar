package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * How a groupBy result names its columns, and what happens when the key names nothing.
 *
 * <p>Both came from a real investigation against a recording: {@code groupBy(gcType, ...)} on
 * {@code jdk.GarbageCollection} — which has no {@code gcType} — returned zero rows and no
 * complaint, and the natural follow-up {@code | sortBy(value, asc=false)} was rejected because
 * groupBy names its aggregate column after the function.
 */
class GroupByColumnsTest {

  private static JFRSession session() {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    return session;
  }

  /** Two collections of each name, with pause totals that order differently from the names. */
  private static JfrPathEvaluator.EventSource gcEvents() {
    return (recording, consumer) -> {
      consumer.accept(
          new JfrPathEvaluator.Event(
              "jdk.GarbageCollection", Map.of("name", "G1New", "sumOfPauses", 10)));
      consumer.accept(
          new JfrPathEvaluator.Event(
              "jdk.GarbageCollection", Map.of("name", "G1New", "sumOfPauses", 5)));
      consumer.accept(
          new JfrPathEvaluator.Event(
              "jdk.GarbageCollection", Map.of("name", "G1Old", "sumOfPauses", 100)));
    };
  }

  @Test
  void groupByAnUnknownKeyNamesTheKeyAndTheEventsItSaw() throws Exception {
    var eval = new JfrPathEvaluator(gcEvents());
    var q = JfrPathParser.parse("events/jdk.GarbageCollection | groupBy(gcType, agg=count)");

    var thrown = assertThrows(IllegalArgumentException.class, () -> eval.evaluate(session(), q));

    // Without this the call returns an empty list, which reads as "no such events".
    assertTrue(thrown.getMessage().contains("gcType"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("3 events"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("jdk.GarbageCollection"), thrown.getMessage());
  }

  @Test
  void groupByOverNoEventsAtAllIsStillAnEmptyResult() throws Exception {
    var eval = new JfrPathEvaluator(gcEvents());
    // A type the source never emits: nothing was offered, so there is nothing to complain about.
    var q = JfrPathParser.parse("events/jdk.ThreadStart | groupBy(gcType, agg=count)");

    assertEquals(List.of(), eval.evaluate(session(), q));
  }

  @Test
  void groupByWhoseKeyIsNullOnEveryEventStillReports() throws Exception {
    // 'name' exists on the type but is absent from these events — indistinguishable from a typo
    // without metadata, and equally worth saying out loud.
    JfrPathEvaluator.EventSource noName =
        (recording, consumer) ->
            consumer.accept(
                new JfrPathEvaluator.Event("jdk.GarbageCollection", Map.of("sumOfPauses", 1)));
    var eval = new JfrPathEvaluator(noName);
    var q = JfrPathParser.parse("events/jdk.GarbageCollection | groupBy(name, agg=count)");

    assertThrows(IllegalArgumentException.class, () -> eval.evaluate(session(), q));
  }

  @Test
  void sortByValueMeansTheAggregateColumn() throws Exception {
    var eval = new JfrPathEvaluator(gcEvents());
    var q =
        JfrPathParser.parse(
            "events/jdk.GarbageCollection"
                + " | groupBy(name, agg=sum, value=sumOfPauses)"
                + " | sortBy(value, asc=false)");

    List<Map<String, Object>> rows = eval.evaluate(session(), q);

    assertEquals(2, rows.size());
    assertEquals("G1Old", rows.get(0).get("key"));
    assertEquals(100.0, ((Number) rows.get(0).get("sum")).doubleValue(), 0.0001);
    assertEquals("G1New", rows.get(1).get("key"));
  }

  @Test
  void sortByValueAscendingToo() throws Exception {
    var eval = new JfrPathEvaluator(gcEvents());
    var q =
        JfrPathParser.parse(
            "events/jdk.GarbageCollection"
                + " | groupBy(name, agg=sum, value=sumOfPauses)"
                + " | sortBy(value, asc=true)");

    List<Map<String, Object>> rows = eval.evaluate(session(), q);

    assertEquals("G1New", rows.get(0).get("key"));
  }

  @Test
  void topByValueMeansTheAggregateColumn() throws Exception {
    var eval = new JfrPathEvaluator(gcEvents());
    var q =
        JfrPathParser.parse(
            "events/jdk.GarbageCollection"
                + " | groupBy(name, agg=sum, value=sumOfPauses)"
                + " | top(1, by=value)");

    List<Map<String, Object>> rows = eval.evaluate(session(), q);

    // Before the alias, 'value' resolved to null on every row and top kept the input order.
    assertEquals(1, rows.size());
    assertEquals("G1Old", rows.get(0).get("key"));
  }

  @Test
  void sortByNamesTheAggregateColumnDirectlyAsWell() throws Exception {
    var eval = new JfrPathEvaluator(gcEvents());
    var q =
        JfrPathParser.parse(
            "events/jdk.GarbageCollection"
                + " | groupBy(name, agg=sum, value=sumOfPauses)"
                + " | sortBy(sum, asc=false)");

    assertEquals("G1Old", eval.evaluate(session(), q).get(0).get("key"));
  }

  @Test
  void aRealValueColumnIsNotShadowedByTheAlias() throws Exception {
    JfrPathEvaluator.EventSource src =
        (recording, consumer) -> {
          consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("value", 2, "key", 9)));
          consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("value", 1, "key", 8)));
        };
    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead | sortBy(value, asc=true)");

    List<Map<String, Object>> rows = eval.evaluate(session(), q);

    assertEquals(1, ((Number) rows.get(0).get("value")).intValue());
  }

  @Test
  void sortByAnUnknownColumnStillListsWhatIsThere() throws Exception {
    var eval = new JfrPathEvaluator(gcEvents());
    var q =
        JfrPathParser.parse(
            "events/jdk.GarbageCollection | groupBy(name, agg=count) | sortBy(total)");

    var thrown = assertThrows(IllegalArgumentException.class, () -> eval.evaluate(session(), q));

    assertTrue(thrown.getMessage().contains("'total' not found"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("count"), thrown.getMessage());
  }
}

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

  // --- streaming groupBy: distinct-group cap ---

  /** One event per key value, all of type jdk.FileRead. */
  private static JfrPathEvaluator.EventSource keyEvents(String... keys) {
    return (recording, consumer) -> {
      for (String k : keys) {
        consumer.accept(new JfrPathEvaluator.Event("jdk.FileRead", Map.of("k", k)));
      }
    };
  }

  @Test
  void groupByCapsStreamingDistinctGroups() throws Exception {
    System.setProperty("jafar.groupby.maxGroups", "2");
    try {
      var eval = new JfrPathEvaluator(keyEvents("k1", "k2", "k3"));
      var q = JfrPathParser.parse("events/jdk.FileRead | groupBy(k, agg=count)");

      // The throw happens inside the streamEvents consumer; it must surface from evaluate
      var thrown = assertThrows(IllegalArgumentException.class, () -> eval.evaluate(session(), q));
      assertTrue(thrown.getMessage().contains("cap of 2"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains("k3"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains("jafar.groupby.maxGroups"), thrown.getMessage());
    } finally {
      System.clearProperty("jafar.groupby.maxGroups");
    }
  }

  @Test
  void groupByAtTheCapBoundarySucceedsOneMoreKeyThrows() throws Exception {
    System.setProperty("jafar.groupby.maxGroups", "2");
    try {
      var q = JfrPathParser.parse("events/jdk.FileRead | groupBy(k, agg=count)");

      // Exactly cap distinct keys: fine
      var two = new JfrPathEvaluator(keyEvents("k1", "k2"));
      assertEquals(2, two.evaluate(session(), q).size());

      // One more: throws
      var three = new JfrPathEvaluator(keyEvents("k1", "k2", "k3"));
      assertThrows(IllegalArgumentException.class, () -> three.evaluate(session(), q));
    } finally {
      System.clearProperty("jafar.groupby.maxGroups");
    }
  }

  @Test
  void groupByKeepsFirstSeenInsertionOrderBelowTheCap() throws Exception {
    var eval = new JfrPathEvaluator(keyEvents("b", "a", "b"));
    var q = JfrPathParser.parse("events/jdk.FileRead | groupBy(k, agg=count)");

    var rows = eval.evaluate(session(), q);

    assertEquals(List.of("b", "a"), rows.stream().map(r -> r.get("key")).toList());
  }

  @Test
  void cachedGroupByIsExemptFromTheStreamingCap() throws Exception {
    System.setProperty("jafar.groupby.maxGroups", "2");
    try {
      var eval = new JfrPathEvaluator();
      var rows =
          List.<Map<String, Object>>of(Map.of("k", "k1"), Map.of("k", "k2"), Map.of("k", "k3"));
      var op =
          (JfrPath.GroupByOp)
              JfrPathParser.parse("events/jdk.FileRead | groupBy(k, agg=count)").pipeline.get(0);

      var out = eval.applyToRows(rows, List.of(op));

      assertEquals(3, out.size()); // cap is streaming-only; the cached input is already bounded
    } finally {
      System.clearProperty("jafar.groupby.maxGroups");
    }
  }

  // --- groupBy max sentinel and computed/null keys on the cached path ---

  @Test
  void groupByMaxOverAllNegativeValues() throws Exception {
    // Pre-fix the max accumulator started at Double.MIN_VALUE (4.9E-324, the smallest positive
    // double), so an all-negative group reported that instead of its largest element
    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "f", "bytes", -5L)));
              consumer.accept(
                  new JfrPathEvaluator.Event("jdk.FileRead", Map.of("path", "f", "bytes", -1L)));
            };
    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.FileRead | groupBy(path, agg=max, value=bytes)");

    var out = eval.evaluate(session(), q);

    assertEquals(1, out.size());
    assertEquals(-1.0, ((Number) out.get(0).get("max")).doubleValue(), 0.000001);
  }

  @Test
  void cachedGroupByNullComputedKeyContributesNoGroup() throws Exception {
    var eval = new JfrPathEvaluator();
    var rows =
        List.<Map<String, Object>>of(
            new java.util.HashMap<>(Map.<String, Object>of("x", "a", "y", 1)),
            new java.util.HashMap<>(Map.<String, Object>of("y", 2)),
            new java.util.HashMap<>(Map.<String, Object>of("x", "b", "y", 3)));
    var op =
        (JfrPath.GroupByOp)
            JfrPathParser.parse("events/jdk.FileRead | groupBy(coalesce(x), agg=count)")
                .pipeline
                .get(0);

    var out = eval.applyToRows(rows, List.of(op));

    // x is missing on the middle row: coalesce(x) is null there, so that row joins no group;
    // the non-null computed keys still group, and no null-key group appears
    var keys = out.stream().map(r -> r.get("key")).toList();
    assertEquals(2, out.size());
    assertTrue(keys.contains("a"));
    assertTrue(keys.contains("b"));
    assertFalse(keys.contains(null));
  }

  @Test
  void cachedGroupByFansOutOverArraysCrossedByTheKeyPath() throws Exception {
    // Cached-path parity with the streaming groupBy: a plain path fans out over arrays it
    // CROSSES (one group per element of the nested array it navigates into)
    var eval = new JfrPathEvaluator();
    var rows =
        List.<Map<String, Object>>of(
            Map.of("parents", List.of(Map.of("name", "a"), Map.of("name", "b"))),
            Map.of("parents", List.of(Map.of("name", "b"))));
    var op =
        (JfrPath.GroupByOp)
            JfrPathParser.parse("events/jdk.FileRead | groupBy(parents/name, agg=count)")
                .pipeline
                .get(0);

    var out = eval.applyToRows(rows, List.of(op));

    assertEquals(2, out.size());
    assertEquals(List.of("a", "b"), out.stream().map(r -> r.get("key")).toList());
    assertEquals(1L, out.get(0).get("count"));
    assertEquals(2L, out.get(1).get("count"));
  }

  @Test
  void cachedGroupByIndexedKeyToleratesCollectionValuedFields() throws Exception {
    // The JDK backend's array shape is a java.util.Collection: an index token into it must
    // resolve the element instead of throwing
    var eval = new JfrPathEvaluator();
    var rows =
        List.<Map<String, Object>>of(Map.of("ids", List.of(42L, 43L)), Map.of("ids", List.of(42L)));
    var op =
        (JfrPath.GroupByOp)
            JfrPathParser.parse("events/jdk.FileRead | groupBy(ids[1], agg=count)").pipeline.get(0);

    var out = eval.applyToRows(rows, List.of(op));

    // Row 2 has no second element: it contributes no group
    assertEquals(1, out.size());
    assertEquals(43L, out.get(0).get("key"));
    assertEquals(1L, out.get(0).get("count"));
  }

  @Test
  void cachedGroupByValuePathTakesOneIndexedValuePerRow() throws Exception {
    var eval = new JfrPathEvaluator();
    var rows =
        List.of(
            Map.of("k", "a", "ids", List.of(10L, 20L)), Map.of("k", "a", "ids", List.of(30L, 40L)));
    var op =
        (JfrPath.GroupByOp)
            JfrPathParser.parse("events/jdk.FileRead | groupBy(k, agg=sum, value=ids[0])")
                .pipeline
                .get(0);

    var out = eval.applyToRows(rows, List.of(op));

    // One value per row (ids[0]), not a fan-out over the collection
    assertEquals(1, out.size());
    assertEquals(40.0, ((Number) out.get(0).get("sum")).doubleValue(), 0.000001);
  }
}

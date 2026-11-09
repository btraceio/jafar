package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Per-event filtering semantics used by live {@code tail} (issue #92): {@link
 * JfrPathEvaluator#matchesEvent} (type membership + bracket predicates) and {@link
 * JfrPathEvaluator#applyEventOps} (the per-event pipeline subset; every row-set operator must be
 * rejected with {@link UnsupportedPipelineOperationException} naming the operator).
 */
class JfrPathPerEventTest {

  private final JfrPathEvaluator evaluator = new JfrPathEvaluator();

  private static Map<String, Object> event(String type, Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(JfrPathEvaluator.EVENT_TYPE_KEY, type);
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  // ---- matchesEvent ----

  @Test
  void typeMembershipSingleAndMulti() {
    var single = JfrPathParser.parse("events/jdk.ExecutionSample");
    var multi = JfrPathParser.parse("events/(jdk.ExecutionSample|jdk.FileRead)");

    assertTrue(evaluator.matchesEvent(single, event("jdk.ExecutionSample", "costlyStacks", true)));
    assertFalse(evaluator.matchesEvent(single, event("jdk.FileRead")));
    assertTrue(evaluator.matchesEvent(multi, event("jdk.ExecutionSample")));
    assertTrue(evaluator.matchesEvent(multi, event("jdk.FileRead")));
    assertFalse(evaluator.matchesEvent(multi, event("jdk.FileWrite")));
  }

  @Test
  void bracketPredicatesEvaluatePerEvent() {
    var q = JfrPathParser.parse("events/jdk.ExecutionSample[costlyStacks == true]");
    assertTrue(evaluator.matchesEvent(q, event("jdk.ExecutionSample", "costlyStacks", true)));
    assertFalse(evaluator.matchesEvent(q, event("jdk.ExecutionSample", "costlyStacks", false)));
  }

  @Test
  void noFilterMatchesAllTypes() {
    var q = JfrPathParser.parse("events");
    assertTrue(evaluator.matchesEvent(q, event("jdk.ExecutionSample")));
    assertTrue(evaluator.matchesEvent(q, event("whatever.Type")));
  }

  @Test
  void nonEventsRootRejected() {
    var q = JfrPathParser.parse("metadata");
    assertThrows(
        UnsupportedOperationException.class,
        () -> evaluator.matchesEvent(q, event("jdk.ExecutionSample")));
  }

  // ---- applyEventOps: accepted per-event subset ----

  @Test
  void filterOpKeepsOrDrops() {
    var ops =
        JfrPathParser.parse("events/jdk.ExecutionSample | filter(costlyStacks == true)").pipeline;
    assertTrue(
        evaluator.applyEventOps(ops, event("jdk.ExecutionSample", "costlyStacks", true)) != null);
    assertNull(evaluator.applyEventOps(ops, event("jdk.ExecutionSample", "costlyStacks", false)));
  }

  @Test
  void selectOpProjects() {
    var ops = JfrPathParser.parse("events/jdk.FileRead | select(path)").pipeline;
    Map<String, Object> out =
        evaluator.applyEventOps(ops, event("jdk.FileRead", "path", "/tmp/a.txt", "bytesRead", 10));
    assertEquals(Map.of("path", "/tmp/a.txt"), out);
  }

  @Test
  void lenOpAddsLen() {
    var ops = JfrPathParser.parse("events/jdk.FileRead | len(path)").pipeline;
    Map<String, Object> out = evaluator.applyEventOps(ops, event("jdk.FileRead", "path", "abcde"));
    assertEquals(5, ((Number) out.get("len")).intValue());
  }

  @Test
  void stringAndNumberTransforms() {
    var up = JfrPathParser.parse("events/jdk.FileRead | uppercase(path)").pipeline;
    assertEquals(
        "/TMP/X", evaluator.applyEventOps(up, event("jdk.FileRead", "path", "/tmp/x")).get("path"));

    var abs = JfrPathParser.parse("events/jdk.FileRead | abs(bytesRead)").pipeline;
    assertEquals(
        10.0,
        ((Number)
                evaluator
                    .applyEventOps(abs, event("jdk.FileRead", "bytesRead", -10))
                    .get("bytesRead"))
            .doubleValue());
  }

  @Test
  void containsOpFilters() {
    var ops = JfrPathParser.parse("events/jdk.FileRead | contains(path, \".jfr\")").pipeline;
    assertTrue(evaluator.applyEventOps(ops, event("jdk.FileRead", "path", "a.jfr")) != null);
    assertNull(evaluator.applyEventOps(ops, event("jdk.FileRead", "path", "a.txt")));
  }

  @Test
  void replaceOpRewrites() {
    var ops = JfrPathParser.parse("events/jdk.FileRead | replace(path, \"jfr\", \"JFR\")").pipeline;
    assertEquals(
        "a.JFR", evaluator.applyEventOps(ops, event("jdk.FileRead", "path", "a.jfr")).get("path"));
  }

  // ---- applyEventOps: row-set operators must be rejected ----

  @Test
  void rowSetOperatorsAreRejectedByName() {
    String[] queries = {
      "events/jdk.FileRead | count()",
      "events/jdk.FileRead | sum(bytesRead)",
      "events/jdk.FileRead | stats(bytesRead)",
      "events/jdk.FileRead | groupBy(path)",
      "events/jdk.FileRead | sortby(path)",
      "events/jdk.FileRead | top(10)",
      "events/jdk.FileRead | quantiles(0.5, path=bytesRead)",
      "events/jdk.FileRead | head(5)",
      "events/jdk.FileRead | tail(5)",
      "events/jdk.FileRead | distinct(path)",
      "events/jdk.FileRead | toMap(path, bytesRead)",
      "events/jdk.FileRead | timeRange(startTime)",
      "events/jdk.FileRead | stackprofile()",
      "events/jdk.FileRead | flamegraph()",
    };
    for (String q : queries) {
      var ops = JfrPathParser.parse(q).pipeline;
      var e =
          assertThrows(
              UnsupportedPipelineOperationException.class,
              () -> evaluator.applyEventOps(ops, event("jdk.FileRead", "path", "p")),
              q);
      assertTrue(e.getMessage().contains("("), e.getMessage());
    }
  }

  @Test
  void exceptionNamesTheOperation() {
    var ops = JfrPathParser.parse("events/jdk.FileRead | groupBy(path)").pipeline;
    var e =
        assertThrows(
            UnsupportedPipelineOperationException.class,
            () -> evaluator.applyEventOps(ops, event("jdk.FileRead")));
    assertEquals("groupby", e.getOperation());
  }

  @Test
  void mixedPipelineFiltersBeforeTransform() {
    var ops =
        JfrPathParser.parse("events/jdk.FileRead | filter(bytesRead > 5) | uppercase(path)")
            .pipeline;
    Map<String, Object> keep =
        evaluator.applyEventOps(ops, event("jdk.FileRead", "path", "/tmp/x", "bytesRead", 10));
    assertEquals("/TMP/X", keep.get("path"));
    assertNull(
        evaluator.applyEventOps(ops, event("jdk.FileRead", "path", "/tmp/x", "bytesRead", 1)));
  }

  @Test
  void decorateAndCrossrefRejected() {
    var crossref = JfrPathParser.parse("constants/jdk.types.StackTrace | crossref()").pipeline;
    assertThrows(
        UnsupportedPipelineOperationException.class,
        () -> evaluator.applyEventOps(crossref, event("jdk.ExecutionSample")));
  }
}

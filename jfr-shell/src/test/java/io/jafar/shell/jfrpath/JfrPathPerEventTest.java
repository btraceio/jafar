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

  /** A per-event field map: no reserved side-channel key — the type name is passed explicitly. */
  private static Map<String, Object> event(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static final String ES = "jdk.ExecutionSample";
  private static final String FR = "jdk.FileRead";
  private static final String FW = "jdk.FileWrite";

  // ---- matchesEvent ----

  @Test
  void typeMembershipSingleAndMulti() {
    var single = JfrPathParser.parse("events/jdk.ExecutionSample");
    var multi = JfrPathParser.parse("events/(jdk.ExecutionSample|jdk.FileRead)");

    assertTrue(evaluator.matchesEvent(single, ES, event("costlyStacks", true)));
    assertFalse(evaluator.matchesEvent(single, FR, event()));
    assertTrue(evaluator.matchesEvent(multi, ES, event()));
    assertTrue(evaluator.matchesEvent(multi, FR, event()));
    assertFalse(evaluator.matchesEvent(multi, FW, event()));
  }

  @Test
  void bracketPredicatesEvaluatePerEvent() {
    var q = JfrPathParser.parse("events/jdk.ExecutionSample[costlyStacks == true]");
    assertTrue(evaluator.matchesEvent(q, ES, event("costlyStacks", true)));
    assertFalse(evaluator.matchesEvent(q, ES, event("costlyStacks", false)));
  }

  @Test
  void noFilterMatchesAllTypes() {
    var q = JfrPathParser.parse("events");
    assertTrue(evaluator.matchesEvent(q, ES, event()));
    assertTrue(evaluator.matchesEvent(q, "whatever.Type", event()));
  }

  @Test
  void nonEventsRootRejected() {
    var q = JfrPathParser.parse("metadata");
    assertThrows(UnsupportedOperationException.class, () -> evaluator.matchesEvent(q, ES, event()));
  }

  // ---- applyEventOps: accepted per-event subset ----

  @Test
  void filterOpKeepsOrDrops() {
    var ops =
        JfrPathParser.parse("events/jdk.ExecutionSample | filter(costlyStacks == true)").pipeline;
    assertTrue(evaluator.applyEventOps(ops, event("costlyStacks", true)) != null);
    assertNull(evaluator.applyEventOps(ops, event("costlyStacks", false)));
  }

  @Test
  void selectOpProjects() {
    var ops = JfrPathParser.parse("events/jdk.FileRead | select(path)").pipeline;
    Map<String, Object> out =
        evaluator.applyEventOps(ops, event("path", "/tmp/a.txt", "bytesRead", 10));
    assertEquals(Map.of("path", "/tmp/a.txt"), out);
  }

  @Test
  void lenOpAddsLen() {
    var ops = JfrPathParser.parse("events/jdk.FileRead | len(path)").pipeline;
    Map<String, Object> out = evaluator.applyEventOps(ops, event("path", "abcde"));
    assertEquals(5, ((Number) out.get("len")).intValue());
  }

  @Test
  void stringAndNumberTransforms() {
    var up = JfrPathParser.parse("events/jdk.FileRead | uppercase(path)").pipeline;
    assertEquals("/TMP/X", evaluator.applyEventOps(up, event("path", "/tmp/x")).get("path"));

    var abs = JfrPathParser.parse("events/jdk.FileRead | abs(bytesRead)").pipeline;
    assertEquals(
        10.0,
        ((Number) evaluator.applyEventOps(abs, event("bytesRead", -10)).get("bytesRead"))
            .doubleValue());
  }

  @Test
  void containsOpFilters() {
    var ops = JfrPathParser.parse("events/jdk.FileRead | contains(path, \".jfr\")").pipeline;
    assertTrue(evaluator.applyEventOps(ops, event("path", "a.jfr")) != null);
    assertNull(evaluator.applyEventOps(ops, event("path", "a.txt")));
  }

  @Test
  void replaceOpRewrites() {
    var ops = JfrPathParser.parse("events/jdk.FileRead | replace(path, \"jfr\", \"JFR\")").pipeline;
    assertEquals("a.JFR", evaluator.applyEventOps(ops, event("path", "a.jfr")).get("path"));
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
              () -> evaluator.applyEventOps(ops, event("path", "p")),
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
            () -> evaluator.applyEventOps(ops, event()));
    assertEquals("groupby", e.getOperation());
  }

  @Test
  void mixedPipelineFiltersBeforeTransform() {
    var ops =
        JfrPathParser.parse("events/jdk.FileRead | filter(bytesRead > 5) | uppercase(path)")
            .pipeline;
    Map<String, Object> keep =
        evaluator.applyEventOps(ops, event("path", "/tmp/x", "bytesRead", 10));
    assertEquals("/TMP/X", keep.get("path"));
    assertNull(evaluator.applyEventOps(ops, event("path", "/tmp/x", "bytesRead", 1)));
  }

  // ---- matchesType (shared membership rule) ----

  @Test
  void matchesTypeEmptyListMatchesAll() {
    var q = JfrPathParser.parse("events");
    assertTrue(JfrPathEvaluator.matchesType(q, ES));
    assertTrue(JfrPathEvaluator.matchesType(q, null));
  }

  @Test
  void matchesTypeSingleAndMulti() {
    var single = JfrPathParser.parse("events/jdk.ExecutionSample");
    var multi = JfrPathParser.parse("events/(jdk.ExecutionSample|jdk.FileRead)");
    assertTrue(JfrPathEvaluator.matchesType(single, ES));
    assertFalse(JfrPathEvaluator.matchesType(single, FR));
    assertTrue(JfrPathEvaluator.matchesType(multi, ES));
    assertTrue(JfrPathEvaluator.matchesType(multi, FR));
    assertFalse(JfrPathEvaluator.matchesType(multi, FW));
    assertFalse(JfrPathEvaluator.matchesType(multi, null));
  }

  // ---- per-event op opt-in (PipelineOp.isPerEventApplicable) ----

  @Test
  void perEventOpsDeclareApplicability() {
    String[] applicable = {
      "events/jdk.FileRead | filter(path != null)",
      "events/jdk.FileRead | contains(path, \"a\")",
      "events/jdk.FileRead | select(path)",
      "events/jdk.FileRead | len(path)",
      "events/jdk.FileRead | uppercase(path)",
      "events/jdk.FileRead | lowercase(path)",
      "events/jdk.FileRead | trim(path)",
      "events/jdk.FileRead | abs(bytesRead)",
      "events/jdk.FileRead | round(bytesRead)",
      "events/jdk.FileRead | floor(bytesRead)",
      "events/jdk.FileRead | ceil(bytesRead)",
      "events/jdk.FileRead | formatduration(duration)",
      "events/jdk.FileRead | replace(path, \"a\", \"b\")",
      "events/jdk.FileRead | asdatetime(startTime)",
    };
    for (String q : applicable) {
      var ops = JfrPathParser.parse(q).pipeline;
      for (var op : ops) {
        assertTrue(op.isPerEventApplicable(), q);
      }
    }
    // one non-applicable op returns false AND applyEventOps rejects it by name
    var count = JfrPathParser.parse("events/jdk.FileRead | count()").pipeline;
    assertFalse(count.get(0).isPerEventApplicable());
    var e =
        assertThrows(
            UnsupportedPipelineOperationException.class,
            () -> evaluator.applyEventOps(count, event("path", "p")));
    assertEquals("count", e.getOperation());
  }

  @Test
  void applyEventOpsNeverInjectsAReservedTypeKey() {
    var ops = JfrPathParser.parse("events/jdk.FileRead | len(path)").pipeline;
    Map<String, Object> out = evaluator.applyEventOps(ops, event("path", "abcde"));
    assertFalse(out.containsKey("__eventType"));
  }

  @Test
  void decorateAndCrossrefRejected() {
    var crossref = JfrPathParser.parse("constants/jdk.types.StackTrace | crossref()").pipeline;
    assertThrows(
        UnsupportedPipelineOperationException.class,
        () -> evaluator.applyEventOps(crossref, event()));
  }
}

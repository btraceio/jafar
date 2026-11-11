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
 * Array element traversal in select templates and groupBy keys, and in the path arguments the two
 * share with the other pipeline operators.
 *
 * <p>Projection paths ({@code events/T/a[0].b}) always supported indexing and tail slices; the
 * pipeline operators' path arguments went through a parser that stopped at {@code [}. Worse, an
 * index inside a select() string template ({@code "${a[0]}"}) was silently dropped.
 */
class ArrayTraversalTest {

  private static JFRSession session() {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    return session;
  }

  /** Events with a String[] field and a List-valued field (the JDK backend's array shape). */
  private static JfrPathEvaluator.EventSource arrayEvents() {
    return (recording, consumer) -> {
      consumer.accept(
          new JfrPathEvaluator.Event(
              "jdk.ExecutionSample",
              Map.of("frames", new String[] {"a", "b", "c"}, "tags", List.of("x", "y"), "n", 1)));
      consumer.accept(
          new JfrPathEvaluator.Event(
              "jdk.ExecutionSample",
              Map.of("frames", new String[] {"d", "e"}, "tags", List.of("z"), "n", 2)));
    };
  }

  private static List<Map<String, Object>> run(JfrPathEvaluator.EventSource src, String query)
      throws Exception {
    return new JfrPathEvaluator(src).evaluate(session(), JfrPathParser.parse(query));
  }

  // --- select(): indexed paths, one value per row, no fan-out ---

  @Test
  void selectReachesAnArrayElement() throws Exception {
    var out = run(arrayEvents(), "events/jdk.ExecutionSample | select(frames[0] as first)");
    assertEquals(2, out.size());
    assertEquals("a", out.get(0).get("first"));
    assertEquals("d", out.get(1).get("first"));
  }

  @Test
  void selectKeepsWholeArraysUntouched() throws Exception {
    // No fan-out in select: an unindexed array field stays one value per row
    var out = run(arrayEvents(), "events/jdk.ExecutionSample | select(frames)");
    assertEquals(2, out.size());
    assertArrayEquals(new String[] {"a", "b", "c"}, (String[]) out.get(0).get("frames"));
  }

  @Test
  void selectIndexOutOfRangeIsNull() throws Exception {
    var out = run(arrayEvents(), "events/jdk.ExecutionSample | select(frames[5] as fifth)");
    assertNull(out.get(0).get("fifth"));
    assertNull(out.get(1).get("fifth"));
  }

  @Test
  void selectNestedIndexedPathWorks() throws Exception {
    var out = run(arrayEvents(), "events/jdk.ExecutionSample | select(tags[1] as secondTag)");
    assertEquals("y", out.get(0).get("secondTag"));
    assertNull(out.get(1).get("secondTag"));
  }

  @Test
  void selectTailSliceYieldsAList() throws Exception {
    var out = run(arrayEvents(), "events/jdk.ExecutionSample | select(frames[0:2] as head)");
    assertEquals(List.of("a", "b"), out.get(0).get("head"));
    assertEquals(List.of("d", "e"), out.get(1).get("head"));
  }

  @Test
  void selectOpenEndedSliceRunsToEnd() throws Exception {
    var out = run(arrayEvents(), "events/jdk.ExecutionSample | select(frames[1:] as tail)");
    assertEquals(List.of("b", "c"), out.get(0).get("tail"));
    assertEquals(List.of("e"), out.get(1).get("tail"));
  }

  // --- select(): templates must not silently drop the index ---

  @Test
  void templateInterpolatesAnIndexedElement() throws Exception {
    var out =
        run(arrayEvents(), "events/jdk.ExecutionSample | select(\"first=${frames[0]}\" as label)");
    assertEquals(2, out.size());
    assertEquals("first=a", out.get(0).get("label"));
    assertEquals("first=d", out.get(1).get("label"));
  }

  @Test
  void templateInterpolatesIndexedAndPlainFields() throws Exception {
    var out =
        run(
            arrayEvents(),
            "events/jdk.ExecutionSample | select(\"${frames[0]}/${tags[0]}/${n}\" as label)");
    assertEquals("a/x/1", out.get(0).get("label"));
    assertEquals("d/z/2", out.get(1).get("label"));
  }

  // --- groupBy(): keys traverse arrays; plain paths keep their fan-out ---

  @Test
  void groupByIndexedKeyGroupsByElement() throws Exception {
    var out = run(arrayEvents(), "events/jdk.ExecutionSample | groupBy(frames[0], agg=count)");
    assertEquals(2, out.size());
    assertTrue(out.stream().anyMatch(r -> "a".equals(r.get("key"))));
    assertTrue(out.stream().anyMatch(r -> "d".equals(r.get("key"))));
  }

  @Test
  void groupByCrossingAnUnindexedArrayFansOut() throws Exception {
    // Tail arrays are one value; fan-out happens when the path continues past the array —
    // the pre-existing plain-path behaviour, one group per element.
    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) ->
                consumer.accept(
                    new JfrPathEvaluator.Event(
                        "jdk.ExecutionSample",
                        Map.of(
                            "st",
                            Map.of("frames", List.of(Map.of("name", "a"), Map.of("name", "b"))))));
    var out = run(src, "events/jdk.ExecutionSample | groupBy(st/frames/name, agg=count)");
    assertEquals(2, out.size());
    assertTrue(out.stream().anyMatch(r -> "a".equals(r.get("key"))));
    assertTrue(out.stream().anyMatch(r -> "b".equals(r.get("key"))));
  }

  @Test
  void groupByTemplateKeyCombinesFields() throws Exception {
    var out =
        run(
            arrayEvents(),
            "events/jdk.ExecutionSample | groupBy(\"${frames[0]}#${n}\", agg=count)");
    assertEquals(2, out.size());
    assertTrue(out.stream().anyMatch(r -> "a#1".equals(r.get("key"))));
    assertTrue(out.stream().anyMatch(r -> "d#2".equals(r.get("key"))));
  }

  @Test
  void groupByComputedKeyArithmetic() throws Exception {
    var out = run(arrayEvents(), "events/jdk.ExecutionSample | groupBy(n * 10, agg=count)");
    assertEquals(2, out.size());
    assertTrue(
        out.stream().anyMatch(r -> Integer.valueOf(10).equals(((Number) r.get("key")).intValue())));
    assertTrue(
        out.stream().anyMatch(r -> Integer.valueOf(20).equals(((Number) r.get("key")).intValue())));
  }

  @Test
  void groupByExpressionKeyWithAggregate() throws Exception {
    var out =
        run(arrayEvents(), "events/jdk.ExecutionSample | groupBy(frames[0], agg=sum, value=n)");
    assertEquals(2, out.size());
    var byKey = new java.util.HashMap<>();
    out.forEach(r -> byKey.put(r.get("key"), r.get("sum")));
    assertEquals(1.0, ((Number) byKey.get("a")).doubleValue());
    assertEquals(2.0, ((Number) byKey.get("d")).doubleValue());
  }

  @Test
  void groupByIndexedKeySkipsEventsWithoutTheField() throws Exception {
    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample", Map.of("frames", new String[] {"a"})));
              consumer.accept(new JfrPathEvaluator.Event("jdk.ExecutionSample", Map.of("n", 1)));
            };
    var out = run(src, "events/jdk.ExecutionSample | groupBy(frames[0], agg=count)");
    // The event without frames contributes no group; no null-key row appears.
    assertEquals(1, out.size());
    assertEquals("a", out.get(0).get("key"));
  }

  @Test
  void groupBySliceKeyFansOutOverTheRange() throws Exception {
    var out = run(arrayEvents(), "events/jdk.ExecutionSample | groupBy(frames[0:2], agg=count)");
    // a, b from event 1 and d, e from event 2
    assertEquals(4, out.size());
  }

  @Test
  void nullExpressionKeysSkipEventsAndStayNameable() throws Exception {
    // coalesce over absent fields evaluates to null for every event: no groups form — and no
    // "null" group appears — so reportUnmatchedKey fires, naming the expression.
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                run(
                    arrayEvents(),
                    "events/jdk.ExecutionSample | groupBy(coalesce(missing, missing), agg=count)"));
    assertTrue(thrown.getMessage().contains("coalesce(missing, missing)"), thrown.getMessage());
  }

  // --- the shared path-argument parser: projection paths and other operators gain [n] too ---

  @Test
  void projectionPathsAcceptIndexesAndSlices() throws Exception {
    var eval = new JfrPathEvaluator(arrayEvents());
    var first =
        eval.evaluateValues(session(), JfrPathParser.parse("events/jdk.ExecutionSample/frames[0]"));
    assertEquals(List.of("a", "d"), first);

    var sliced =
        eval.evaluateValues(
            session(), JfrPathParser.parse("events/jdk.ExecutionSample/frames[0:2]"));
    // projection slices fan out flat into the value list (long-standing behaviour)
    assertEquals(List.of("a", "b", "d", "e"), sliced);
  }

  @Test
  void filtersStillParseWhenBracketContentIsAPredicate() throws Exception {
    var out = run(arrayEvents(), "events/jdk.ExecutionSample[n>1] | select(frames[0] as first)");
    assertEquals(1, out.size());
    assertEquals("d", out.get(0).get("first"));
  }

  @Test
  void sumAndTopTakeIndexedPaths() throws Exception {
    var summed = run(arrayEvents(), "events/jdk.ExecutionSample/n[0] | count()");
    assertEquals(2, ((Number) summed.get(0).get("count")).intValue());

    var top = run(arrayEvents(), "events/jdk.ExecutionSample | top(1, by=frames[0])");
    assertEquals(1, top.size());
  }

  // --- non-tail slices are rejected, not silently ignored ---

  @Test
  void nonTailSliceIsRejected() {
    // frames[0:2]/name used to silently degrade to frames/name (unindexed fan-out); now it fails
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> PathValues.buildPathTokens(List.of("frames[0:2]", "name")));
    assertTrue(ex.getMessage().contains("last path segment"));
    assertTrue(ex.getMessage().contains("frames[0:2]"));

    // Same rule inside the tail segment when the slice is not its last bracket group
    assertThrows(
        IllegalArgumentException.class,
        () -> PathValues.buildPathTokens(List.of("frames[0:2][0]")));
  }

  @Test
  void groupByIndexOutOfRangeSkipsTheEvent() throws Exception {
    // Out-of-range index on one event drops that event's group (no fan-out, no throw); the
    // in-range event still groups. Only an event stream where the key matches nothing at all
    // trips reportUnmatchedKey — a different, deliberate feature.
    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample", Map.of("frames", new String[] {"a", "b"})));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample", Map.of("frames", new String[] {"a"})));
            };
    var out = run(src, "events/jdk.ExecutionSample | groupBy(frames[1], agg=count)");
    // The out-of-range event contributes no group; the in-range one still groups
    assertEquals(1, out.size());
    assertEquals("b", out.get(0).get("key"));
  }

  // --- parser rejects malformed traversal up front ---

  @Test
  void malformedIndexIsAParseError() {
    assertThrows(
        IllegalArgumentException.class,
        () -> JfrPathParser.parse("events/jdk.ExecutionSample | select(frames[x] as f)"));
    assertThrows(
        IllegalArgumentException.class,
        () -> JfrPathParser.parse("events/jdk.ExecutionSample | select(frames[0 as f)"));
  }
}

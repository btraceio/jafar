package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.jafar.parser.TimeRange;
import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The evaluator hands a query's event types to its source, and the default source's answer with the
 * parser's type filter equals its answer without it.
 */
class TypePushdownTest {
  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @AfterEach
  void restoreProperty() {
    System.clearProperty(JfrPathEvaluator.PUSHDOWN_PROPERTY);
  }

  /** Records which overload the evaluator called and with which types. */
  private static final class SpySource implements JfrPathEvaluator.EventSource {
    final List<Set<String>> selections = Collections.synchronizedList(new ArrayList<>());
    final List<TimeRange> ranges = Collections.synchronizedList(new ArrayList<>());
    int unfilteredCalls;

    @Override
    public void streamEvents(Path recording, Consumer<JfrPathEvaluator.Event> consumer) {
      unfilteredCalls++;
      deliver(consumer);
    }

    @Override
    public void streamEvents(
        Path recording, Collection<String> types, Consumer<JfrPathEvaluator.Event> consumer) {
      Set<String> wanted = new TreeSet<>(types);
      selections.add(wanted);
      deliver(
          ev -> {
            if (wanted.contains(ev.typeName())) consumer.accept(ev);
          });
    }

    @Override
    public void streamEvents(
        Path recording,
        Collection<String> types,
        TimeRange startTime,
        Consumer<JfrPathEvaluator.Event> consumer) {
      ranges.add(startTime);
      streamEvents(recording, types, consumer);
    }

    private static void deliver(Consumer<JfrPathEvaluator.Event> consumer) {
      for (int i = 0; i < 10; i++) {
        consumer.accept(
            new JfrPathEvaluator.Event(
                "t.A", Map.of("id", (long) i, "startTime", 1000L * i, "thread", "main")));
        consumer.accept(
            new JfrPathEvaluator.Event(
                "t.B",
                Map.of(
                    "id", (long) i, "startTime", 1000L * i, "duration", 500L, "thread", "main")));
      }
    }
  }

  /**
   * Every operator that reads events names its types to the source. An operator that calls the
   * unfiltered overload decodes the whole recording for one type, which is what this change
   * removed; this is the net for the next operator that is added.
   */
  @Test
  void everyEventOperatorNamesItsTypesToTheSource() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes()).thenReturn(Set.of("t.A", "t.B"));

    Map<String, Set<String>> queries = new TreeMap<>();
    queries.put("events/t.A", Set.of("t.A"));
    queries.put("events/t.A[id>3]", Set.of("t.A"));
    queries.put("events/(t.A|t.B)", Set.of("t.A", "t.B"));
    queries.put("events/t.A | count()", Set.of("t.A"));
    queries.put("events/(t.A|t.B) | count()", Set.of("t.A", "t.B"));
    queries.put("events/t.A | sum(id)", Set.of("t.A"));
    queries.put("events/t.A | stats(id)", Set.of("t.A"));
    queries.put("events/t.A | groupBy(thread, agg=count)", Set.of("t.A"));
    queries.put("events/t.A | select(id)", Set.of("t.A"));
    queries.put("events/t.A | decorateByTime(t.B, fields=id)", Set.of("t.A", "t.B"));
    queries.put(
        "events/t.A | decorateByKey(t.B, key=id, decoratorKey=id, fields=thread)",
        Set.of("t.A", "t.B"));

    for (Map.Entry<String, Set<String>> q : queries.entrySet()) {
      SpySource spy = new SpySource();
      new JfrPathEvaluator(spy).evaluate(session, JfrPathParser.parse(q.getKey()));
      assertEquals(0, spy.unfilteredCalls, q.getKey() + " read every type");
      assertFalse(spy.selections.isEmpty(), q.getKey() + " read no events");
      Set<String> named = new TreeSet<>();
      spy.selections.forEach(named::addAll);
      assertEquals(new TreeSet<>(q.getValue()), named, q.getKey());
    }

    SpySource spy = new SpySource();
    new JfrPathEvaluator(spy).evaluateValues(session, JfrPathParser.parse("events/t.A/id"));
    assertEquals(0, spy.unfilteredCalls, "projection read every type");
    assertEquals(List.of(Set.of("t.A")), spy.selections);
  }

  /** A query's start-time filter reaches the source as a range, with the filter left in place. */
  @Test
  void aStartTimeFilterReachesTheSourceAsARange() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    when(session.getAvailableTypes()).thenReturn(Set.of("t.A", "t.B"));

    SpySource spy = new SpySource();
    List<Map<String, Object>> rows =
        new JfrPathEvaluator(spy)
            .evaluate(session, JfrPathParser.parse("events/t.A[startTime>=3000] | count()"));
    assertEquals(List.of(TimeRange.since(3000)), spy.ranges);
    // the spy delivers all ten events whatever the range: the filter still decides
    assertEquals(7L, ((Number) rows.get(0).get("count")).longValue());

    spy = new SpySource();
    new JfrPathEvaluator(spy).evaluate(session, JfrPathParser.parse("events/t.A | count()"));
    assertEquals(List.of(TimeRange.all()), spy.ranges, "no time filter, no bound");

    // a decorator's own events are not bound by the primary query's filter
    spy = new SpySource();
    new JfrPathEvaluator(spy)
        .evaluate(
            session,
            JfrPathParser.parse("events/t.A[startTime>=3000] | decorateByTime(t.B, fields=id)"));
    assertEquals(2, spy.ranges.size());
    assertTrue(spy.ranges.contains(TimeRange.since(3000)), spy.ranges.toString());
    assertTrue(spy.ranges.contains(TimeRange.all()), spy.ranges.toString());
  }

  /**
   * On a real recording: time-filtered counts with chunk skipping equal those without it, for
   * bounds at and around every chunk edge.
   */
  @Test
  void timeFilteredResultsEqualTheUnprunedReference() throws Exception {
    try (JFRSession session = new JFRSession(TCK_RECORDING, ParsingContext.create())) {
      JfrPathEvaluator evaluator = new JfrPathEvaluator();
      setPushdown(false);
      List<Long> edges = new ArrayList<>();
      for (Map<String, Object> chunk : evaluator.evaluate(session, JfrPathParser.parse("chunks"))) {
        long start = ((Number) chunk.get("startNanos")).longValue();
        long duration = ((Number) chunk.get("duration")).longValue();
        edges.add(start);
        edges.add(start + duration);
      }
      assertTrue(edges.size() >= 4, "expected at least two chunks: " + edges);

      Map<String, Long> counts = new TreeMap<>(evaluator.countAllEventTypes(session));
      // datadog.HeapLiveObject is the type that refuted skipping chunks by the range's end
      assertTrue(counts.containsKey("datadog.HeapLiveObject"), counts.keySet().toString());
      int compared = 0;
      for (String type : counts.keySet()) {
        for (long edge : edges) {
          for (String filter : new String[] {"startTime>=" + edge, "startTime<=" + edge}) {
            compared += assertSameCount(evaluator, session, type, filter);
          }
        }
      }
      // the other filter shapes, and bounds off the chunk edges, on three types
      List<String> types = new ArrayList<>(counts.keySet());
      for (String type :
          List.of("datadog.HeapLiveObject", types.get(0), types.get(types.size() - 1))) {
        for (long edge : edges) {
          for (long delta : new long[] {-2_000_000_000L, 2_000_000_000L}) {
            long t = edge + delta;
            String instant = java.time.Instant.ofEpochSecond(0, t).toString();
            for (String filter :
                new String[] {
                  "after(startTime, \"" + instant + "\")",
                  "before(startTime, \"" + instant + "\")",
                  "startTime>=" + (t - 500_000_000L) + " and startTime<=" + (t + 500_000_000L)
                }) {
              compared += assertSameCount(evaluator, session, type, filter);
            }
          }
        }
      }
      assertTrue(compared > 100, "queries compared: " + compared);
    }
  }

  private static int assertSameCount(
      JfrPathEvaluator evaluator, JFRSession session, String type, String filter) throws Exception {
    String query = "events/" + type + "[" + filter + "] | count()";
    assertEquals(
        countOf(evaluator, session, query, false), countOf(evaluator, session, query, true), query);
    return 1;
  }

  /** On a real recording: the same rows with the parser's type filter as without it. */
  @Test
  void resultsEqualTheUnfilteredReference() throws Exception {
    try (JFRSession session = new JFRSession(TCK_RECORDING, ParsingContext.create())) {
      JfrPathEvaluator evaluator = new JfrPathEvaluator();
      Map<String, Long> counts = new TreeMap<>(evaluator.countAllEventTypes(session));
      assertTrue(counts.size() > 5, "expected a recording with many event types: " + counts);

      List<String> types = new ArrayList<>(counts.keySet());
      for (String type : types) {
        String count = "events/" + type + " | count()";
        long expected = counts.get(type);
        assertEquals(expected, countOf(evaluator, session, count, false), count + " (reference)");
        assertEquals(expected, countOf(evaluator, session, count, true), count);

        String projection = "events/" + type + "/startTime";
        assertEquals(
            sorted(values(evaluator, session, projection, false)),
            sorted(values(evaluator, session, projection, true)),
            projection);

        // the route that opens its own parser: a limit nothing reaches returns every row
        setPushdown(true);
        int limited =
            evaluator
                .evaluateWithLimit(
                    session, JfrPathParser.parse("events/" + type), Integer.MAX_VALUE)
                .size();
        assertEquals(expected, limited, "events/" + type + " --limit");
      }

      String two = "events/(" + types.get(0) + "|" + types.get(types.size() - 1) + ") | count()";
      long expectedTwo = counts.get(types.get(0)) + counts.get(types.get(types.size() - 1));
      assertEquals(expectedTwo, countOf(evaluator, session, two, false), two + " (reference)");
      assertEquals(expectedTwo, countOf(evaluator, session, two, true), two);
    }
  }

  private static void setPushdown(boolean enabled) {
    System.setProperty(JfrPathEvaluator.PUSHDOWN_PROPERTY, String.valueOf(enabled));
    assertEquals(enabled, JfrPathEvaluator.pushdownEnabled());
  }

  private static long countOf(
      JfrPathEvaluator evaluator, JFRSession session, String query, boolean pushdown)
      throws Exception {
    setPushdown(pushdown);
    Object count = evaluator.evaluate(session, JfrPathParser.parse(query)).get(0).get("count");
    return ((Number) count).longValue();
  }

  private static List<Object> values(
      JfrPathEvaluator evaluator, JFRSession session, String query, boolean pushdown)
      throws Exception {
    setPushdown(pushdown);
    return evaluator.evaluateValues(session, JfrPathParser.parse(query));
  }

  private static List<String> sorted(List<Object> values) {
    List<String> out = new ArrayList<>();
    for (Object v : values) {
      out.add(String.valueOf(v));
    }
    Collections.sort(out);
    return out;
  }
}

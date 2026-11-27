package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * #303: a pipeline {@code filter(...)} whose condition is a function matched no row, though the
 * same condition in a {@code [...]} predicate matched. The pipeline filter had no case for function
 * conditions, and compared a value function's raw first argument instead of its value.
 */
class PipelineFilterFunctionTest {

  /** Five events: durations 10-50, names a, bb, ccc, dddd, eeeee, the last two in "After GC". */
  private static List<Map<String, Object>> run(String query) throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));
    JfrPathEvaluator.EventSource src =
        (rec, consumer) -> {
          String[] names = {"a", "bb", "ccc", "dddd", "eeeee"};
          for (int i = 0; i < names.length; i++) {
            consumer.accept(
                new JfrPathEvaluator.Event(
                    "test.Event",
                    Map.of(
                        "duration",
                        (long) (i + 1) * 10,
                        "name",
                        names[i],
                        "when",
                        Map.of("when", i >= 3 ? "After GC" : "Before GC"))));
          }
        };
    return new JfrPathEvaluator(src).evaluate(session, JfrPathParser.parse(query));
  }

  private static long count(String query) throws Exception {
    return ((Number) run(query).get(0).get("count")).longValue();
  }

  @Test
  void eachFunctionMatchesInFilterAsInABracketPredicate() throws Exception {
    Map<String, Long> expected =
        Map.ofEntries(
            Map.entry("between(duration, 20, 40)", 3L),
            Map.entry("contains(when/when, \"After\")", 2L),
            Map.entry("startsWith(name, \"cc\")", 1L),
            Map.entry("endsWith(name, \"d\")", 1L),
            Map.entry("matches(name, \"^[a-c]+$\")", 3L),
            Map.entry("exists(name)", 5L),
            Map.entry("empty(name)", 0L),
            Map.entry("len(name) > 3", 2L),
            Map.entry("len(name) = 3", 1L),
            Map.entry("len(name) == 3", 1L),
            Map.entry("len(name) != 3", 4L),
            Map.entry("len(name) < 3", 2L),
            Map.entry("len(name) <= 3", 3L),
            Map.entry("len(name) >= 4", 2L),
            Map.entry("len(name) ~ \"^[45]$\"", 2L),
            Map.entry("between(duration, 20, 40) and contains(name, \"c\")", 1L),
            Map.entry("not contains(name, \"a\")", 4L));
    // Every mismatch, not the first
    List<String> mismatches = new java.util.ArrayList<>();
    for (Map.Entry<String, Long> e : expected.entrySet()) {
      String cond = e.getKey();
      long bracket = count("events/test.Event[" + cond + "] | count()");
      long filter = count("events/test.Event | filter(" + cond + ") | count()");
      if (bracket != e.getValue() || filter != e.getValue()) {
        mismatches.add(
            cond + ": expected " + e.getValue() + ", [] " + bracket + ", filter " + filter);
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  void logicalOperatorsShortCircuitAsInABracketPredicate() throws Exception {
    // The right side throws on a number ("Cannot parse datetime"); it is never reached
    for (String cond :
        List.of(
            "exists(name) or before(duration, \"garbage\")",
            "not exists(name) and before(duration, \"garbage\")")) {
      long expected = cond.startsWith("not") ? 0L : 5L;
      assertEquals(expected, count("events/test.Event[" + cond + "] | count()"), cond);
      assertEquals(expected, count("events/test.Event | filter(" + cond + ") | count()"), cond);
    }
  }

  @Test
  void numbersOfDifferentClassesAreEqualByExactValue() throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));
    // An Integer, a Float and a numeric string above 2^53, against Long and Double literals
    JfrPathEvaluator.EventSource src =
        (rec, consumer) ->
            consumer.accept(
                new JfrPathEvaluator.Event(
                    "test.Event",
                    Map.of(
                        "small", 7,
                        "half", 0.5f,
                        "tenth", 0.1f,
                        "big", "9007199254740993",
                        "dec", "0.1",
                        "arabic", "\u0665",
                        "nul", "5\u0000")));
    JfrPathEvaluator eval = new JfrPathEvaluator(src);
    Map<String, Long> expected =
        Map.ofEntries(
            Map.entry("small = 7", 1L),
            Map.entry("small != 7", 0L),
            Map.entry("half = 0.5", 1L),
            // A float by its decimal form, not its binary expansion
            Map.entry("tenth = 0.1", 1L),
            Map.entry("big = 9007199254740993", 1L),
            Map.entry("big = 9007199254740992", 0L),
            Map.entry("big != 9007199254740992", 1L),
            // A decimal string equals a decimal literal, as before
            Map.entry("dec = 0.1", 1L),
            // Not numbers: an Arabic-Indic five, a five with a NUL
            Map.entry("arabic = 5", 0L),
            Map.entry("nul = 5", 0L));
    for (Map.Entry<String, Long> e : expected.entrySet()) {
      Object n =
          eval.evaluate(
                  session, JfrPathParser.parse("events/test.Event[" + e.getKey() + "] | count()"))
              .get(0)
              .get("count");
      assertEquals(e.getValue(), ((Number) n).longValue(), e.getKey());
    }
  }

  @Test
  void anUnknownFunctionFailsInFilterAsInABracketPredicate() {
    // filter(foo(x)) matched nothing without a word
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> run("events/test.Event | filter(foo(name)) | count()"));
    assertTrue(e.getMessage().contains("foo"), e.getMessage());
  }

  @Test
  void aBooleanFunctionComparedFailsToParse() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> JfrPathParser.parse("events/test.Event[contains(name, \"a\") = true]"));
    assertTrue(e.getMessage().contains("contains() is not a value function"), e.getMessage());
  }

  @Test
  void aFunctionFilterAfterAGroupByReadsTheGroupRows() throws Exception {
    List<Map<String, Object>> rows =
        run("events/test.Event | groupBy(when/when) | filter(contains(key, \"After\"))");
    assertEquals(1, rows.size(), rows.toString());
    assertEquals(2L, ((Number) rows.get(0).get("count")).longValue(), rows.toString());
  }
}

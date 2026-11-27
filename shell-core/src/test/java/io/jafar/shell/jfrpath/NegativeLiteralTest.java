package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * #304: a negative number did not parse as a literal ({@code [v > -1]}: "Expected literal"), and as
 * a function argument it was read as a field named {@code -1} ({@code [between(v, -1, 5)]}: 'For
 * input string: "null"').
 */
class NegativeLiteralTest {

  /** Four events: v = -5, 0, 3, 10; d = -2.5, -0.25, 0.5, 1.0. */
  private static long count(String query) throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));
    long[] v = {-5, 0, 3, 10};
    double[] d = {-2.5, -0.25, 0.5, 1.0};
    JfrPathEvaluator.EventSource src =
        (rec, consumer) -> {
          for (int i = 0; i < v.length; i++) {
            consumer.accept(new JfrPathEvaluator.Event("test.Event", Map.of("v", v[i], "d", d[i])));
          }
        };
    return ((Number)
            new JfrPathEvaluator(src)
                .evaluate(session, JfrPathParser.parse(query))
                .get(0)
                .get("count"))
        .longValue();
  }

  @Test
  void negativeLiteralsCompareInBracketsAndFilter() throws Exception {
    Map<String, Long> expected =
        Map.ofEntries(
            Map.entry("v > -1", 3L),
            Map.entry("v >= -5", 4L),
            Map.entry("v = -5", 1L),
            Map.entry("v != -5", 3L),
            Map.entry("v < -1", 1L),
            Map.entry("d > -0.5", 3L),
            Map.entry("d < -.5", 1L),
            Map.entry("between(v, -10, 0)", 2L),
            Map.entry("between(d, -1, 1)", 3L),
            Map.entry("v > -1 and d < 0.75", 2L));
    List<String> mismatches = new ArrayList<>();
    for (Map.Entry<String, Long> e : expected.entrySet()) {
      long bracket = count("events/test.Event[" + e.getKey() + "] | count()");
      long filter = count("events/test.Event | filter(" + e.getKey() + ") | count()");
      if (bracket != e.getValue() || filter != e.getValue()) {
        mismatches.add(
            e.getKey() + ": expected " + e.getValue() + ", [] " + bracket + ", filter " + filter);
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  void aLoneMinusIsNoLiteral() {
    assertThrows(
        IllegalArgumentException.class, () -> JfrPathParser.parse("events/test.Event[v > -]"));
  }

  @Test
  void countsMayNotBeNegative() {
    // A literal may be negative now; a count may not, or head(-1) fails in subList at run time
    for (String op :
        List.of(
            "head(-1)",
            "tail(-2)",
            "top(-3, by=v)",
            "quantiles(-0.5)",
            "stackprofile(buckets=-1)",
            "stackprofile(minPct=-1)")) {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class,
              () -> JfrPathParser.parse("events/test.Event | " + op),
              op);
      assertTrue(
          e.getMessage().contains("0 or more") || e.getMessage().contains("0..1"),
          op + ": " + e.getMessage());
    }
  }

  @Test
  void countsAndQuantilesAtTheirBounds() {
    // Rejected: wrapping to a negative int, a fraction, a quantile above 1
    for (String op :
        List.of(
            "head(3000000000)", "tail(2147483648)", "head(-0.5)", "head(1.5)", "quantiles(1.5)")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> JfrPathParser.parse("events/test.Event | " + op),
          op);
    }
    // Accepted: the bounds themselves
    for (String op :
        List.of(
            "head(0)",
            "tail(2147483647)",
            "top(0, by=v)",
            "quantiles(0, 1)",
            "stackprofile(minPct=0)")) {
      JfrPathParser.parse("events/test.Event | " + op);
    }
  }
}

package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * #312: the expression grammar of {@code select(... as)} read {@code -.5} as a field path {@code
 * ["-", "5"]} (null, so {@code -.5 + v} was 0.0), and {@code .5} was no expression at all.
 */
class ExpressionNumberLiteralTest {

  /** {@code x} of each of two events, v = 2 and v = 10. */
  private static List<Object> xs(String expression) throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));
    JfrPathEvaluator.EventSource src =
        (rec, consumer) -> {
          for (long v : new long[] {2, 10}) {
            consumer.accept(new JfrPathEvaluator.Event("test.Event", Map.of("v", v)));
          }
        };
    List<Object> xs = new ArrayList<>();
    for (Map<String, Object> row :
        new JfrPathEvaluator(src)
            .evaluate(
                session,
                JfrPathParser.parse("events/test.Event | select(" + expression + " as x)"))) {
      xs.add(((Number) row.get("x")).doubleValue());
    }
    return xs;
  }

  @Test
  void aFilterLiteralTooLargeForALongIsAParseError() {
    // 9223372036854775807 * 1024 wrapped around silently
    IllegalArgumentException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> JfrPathParser.parse("events/test.Event[v > 9223372036854775807K]"));
    org.junit.jupiter.api.Assertions.assertTrue(
        e.getMessage().contains("out of range"), e.getMessage());
  }

  @Test
  void aNumberWithoutAnIntegerPartIsANumber() throws Exception {
    Map<String, List<Object>> expected = new LinkedHashMap<>();
    expected.put("-.5 + v", List.of(1.5, 9.5));
    expected.put(".5 + v", List.of(2.5, 10.5));
    expected.put("v - .5", List.of(1.5, 9.5));
    expected.put("v * -.5", List.of(-1.0, -5.0));
    expected.put("-0.5 + v", List.of(1.5, 9.5)); // already worked
    expected.put("-1 + v", List.of(1.0, 9.0)); // already worked
    expected.put(".5", List.of(0.5, 0.5));
    expected.put("-.5", List.of(-0.5, -0.5));
    Map<String, List<Object>> actual = new LinkedHashMap<>();
    for (String expression : expected.keySet()) {
      actual.put(expression, xs(expression));
    }
    assertEquals(expected, actual);
  }
}

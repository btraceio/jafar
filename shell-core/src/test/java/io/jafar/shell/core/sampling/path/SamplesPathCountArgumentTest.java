package io.jafar.shell.core.sampling.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #312: {@code head}, {@code tail} and {@code top} cast their count with {@code (int)}: a negative
 * count was accepted, and {@code head(3000000000)} wrapped to a negative one.
 */
class SamplesPathCountArgumentTest {

  @Test
  void aNegativeFractionalOrOverIntCountIsAParseError() {
    List<String> accepted = new ArrayList<>();
    for (String template :
        List.of("samples | head(%s)", "samples | tail(%s)", "samples | top(%s)")) {
      for (String value : List.of("-1", "2.5", "3000000000", "99999999999999999999")) {
        String query = String.format(template, value);
        try {
          SamplesPathParser.parse(query);
          accepted.add(query);
        } catch (SamplesPathParseException expected) {
          assertTrue(
              expected.getMessage().contains("whole number of 0 or more"),
              query + ": " + expected.getMessage());
        }
      }
    }
    assertEquals(List.of(), accepted);
  }

  @Test
  void validCountsParseAsBefore() {
    assertEquals(new SamplesPath.HeadOp(0), last("samples | head(0)"));
    assertEquals(new SamplesPath.TailOp(5), last("samples | tail(5)"));
    assertEquals(10, ((SamplesPath.TopOp) last("samples | top(10)")).n());
  }

  @Test
  void aWholeValueWithADecimalPointIsACount() {
    // The rule JfrPath and HdumpPath apply; before, "Expected ')'" at the '.'
    assertEquals(new SamplesPath.HeadOp(2), last("samples | head(2.0)"));
  }

  private static SamplesPath.PipelineOp last(String query) {
    List<SamplesPath.PipelineOp> pipeline = SamplesPathParser.parse(query).pipeline();
    return pipeline.get(pipeline.size() - 1);
  }
}

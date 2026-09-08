package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.jfrpath.JfrPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class JfrQueryEvaluatorTest {

  /** Checked into the repository, so this needs no download. */
  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr");

  private final JfrQueryEvaluator evaluator = new JfrQueryEvaluator();

  @Test
  void parseProducesAJfrPathQuery() {
    assertInstanceOf(JfrPath.Query.class, evaluator.parse("events/jdk.ExecutionSample | count()"));
  }

  @Test
  void parseReportsAnUnparseableQueryAsQueryParseException() {
    QueryEvaluator.QueryParseException e =
        assertThrows(
            QueryEvaluator.QueryParseException.class,
            () -> evaluator.parse("SELECT * FROM jdk.ExecutionSample"));
    assertTrue(e.getMessage().contains("SELECT"), e.getMessage());
  }

  @Test
  void evaluateAcceptsTheRawQueryString() throws Exception {
    // The QueryEvaluator contract says "parsed query object or raw query string", and the Hdump,
    // pprof and OTLP evaluators both accept both. This one used to reject the string, so any
    // caller holding only the text had to know which implementation it had.
    Assumptions.assumeTrue(Files.isReadable(TCK_RECORDING), "TCK recording not present");

    try (JFRSession session = new JFRSession(TCK_RECORDING, ParsingContext.create())) {
      Object fromString = evaluator.evaluate(session, "events/jdk.ExecutionSample | count()");
      Object fromParsed =
          evaluator.evaluate(session, evaluator.parse("events/jdk.ExecutionSample | count()"));

      assertInstanceOf(List.class, fromString);
      assertEquals(rowsOf(fromParsed), rowsOf(fromString));
    }
  }

  @Test
  void evaluateRejectsSomethingThatIsNeither() throws Exception {
    Assumptions.assumeTrue(Files.isReadable(TCK_RECORDING), "TCK recording not present");

    try (JFRSession session = new JFRSession(TCK_RECORDING, ParsingContext.create())) {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(session, 42));
      assertTrue(e.getMessage().contains("JfrPath.Query or String"), e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rowsOf(Object result) {
    return (List<Map<String, Object>>) result;
  }
}

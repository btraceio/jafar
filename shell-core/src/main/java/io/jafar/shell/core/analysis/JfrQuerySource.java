package io.jafar.shell.core.analysis;

import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * How the analyses read a recording.
 *
 * <p>Exists so the analyses keep taking their query engine from the caller rather than constructing
 * one. That injection was already load-bearing and nearly lost in the move: {@code
 * ConsumeEdgeCasesTest} builds the MCP server with an evaluator that yields nothing, and an
 * analysis that quietly built its own real evaluator ignored the double and went to the recording
 * instead — which is precisely the sort of difference a refactor is supposed not to make.
 */
public interface JfrQuerySource {

  List<Map<String, Object>> evaluate(JFRSession session, JfrPath.Query query) throws Exception;

  void consume(JFRSession session, JfrPath.Query query, Consumer<Map<String, Object>> consumer)
      throws Exception;

  Map<String, Long> countAllEventTypes(JFRSession session) throws Exception;

  /** The real engine, for callers with no reason to substitute anything. */
  static JfrQuerySource defaultSource() {
    JfrPathEvaluator evaluator = new JfrPathEvaluator();
    return new JfrQuerySource() {
      @Override
      public List<Map<String, Object>> evaluate(JFRSession session, JfrPath.Query query)
          throws Exception {
        return evaluator.evaluate(session, query);
      }

      @Override
      public void consume(
          JFRSession session, JfrPath.Query query, Consumer<Map<String, Object>> consumer)
          throws Exception {
        evaluator.consume(session, query, consumer);
      }

      @Override
      public Map<String, Long> countAllEventTypes(JFRSession session) throws Exception {
        return evaluator.countAllEventTypes(session);
      }
    };
  }
}

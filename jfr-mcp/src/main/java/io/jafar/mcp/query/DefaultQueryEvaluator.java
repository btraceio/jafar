package io.jafar.mcp.query;

import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Default implementation of QueryEvaluator using JfrPathEvaluator.
 *
 * <p>This adapter wraps the real JfrPathEvaluator for production use.
 */
public final class DefaultQueryEvaluator implements QueryEvaluator {

  private final JfrPathEvaluator evaluator;

  public DefaultQueryEvaluator() {
    this.evaluator = new JfrPathEvaluator();
  }

  @Override
  public List<Map<String, Object>> evaluate(JFRSession session, JfrPath.Query query)
      throws Exception {
    return evaluator.evaluate(session, query);
  }

  @Override
  public List<Map<String, Object>> evaluate(
      JFRSession session, JfrPath.Query query, JfrPathEvaluator.ProgressListener progress)
      throws Exception {
    return evaluator.evaluate(session, query, progress);
  }

  @Override
  public Map<String, Long> countAllEventTypes(JFRSession session) throws Exception {
    return evaluator.countAllEventTypes(session);
  }

  @Override
  public void consume(
      JFRSession session, JfrPath.Query query, Consumer<Map<String, Object>> consumer)
      throws Exception {
    evaluator.consume(session, query, consumer);
  }
}

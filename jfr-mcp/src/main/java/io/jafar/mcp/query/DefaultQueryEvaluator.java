package io.jafar.mcp.query;

import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import java.util.List;
import java.util.Map;

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
}

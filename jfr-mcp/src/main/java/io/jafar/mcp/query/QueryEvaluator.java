package io.jafar.mcp.query;

import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPath;
import java.util.List;
import java.util.Map;

/**
 * Interface for evaluating JfrPath queries against JFR sessions.
 *
 * <p>This abstraction allows testing of MCP handlers without requiring real JFR file processing.
 */
public interface QueryEvaluator {

  /**
   * Evaluates a parsed query against a JFR session.
   *
   * @param session the JFR session to query
   * @param query the parsed query to evaluate
   * @return list of event maps matching the query
   * @throws Exception if evaluation fails
   */
  List<Map<String, Object>> evaluate(JFRSession session, JfrPath.Query query) throws Exception;
}

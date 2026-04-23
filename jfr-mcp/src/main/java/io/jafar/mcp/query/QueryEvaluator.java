package io.jafar.mcp.query;

import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

  /**
   * Evaluates a parsed query with progress reporting.
   *
   * @param session the JFR session to query
   * @param query the parsed query to evaluate
   * @param progress optional progress listener, may be null
   * @return list of event maps matching the query
   * @throws Exception if evaluation fails
   */
  default List<Map<String, Object>> evaluate(
      JFRSession session, JfrPath.Query query, JfrPathEvaluator.ProgressListener progress)
      throws Exception {
    return evaluate(session, query);
  }

  /**
   * Count all events by type in a single pass over the recording.
   *
   * <p>Prefer this over per-type {@code events/T | count()} queries: it is O(file_size) rather than
   * O(N × file_size).
   */
  default Map<String, Long> countAllEventTypes(JFRSession session) throws Exception {
    return Collections.emptyMap();
  }

  /**
   * Streams matching events to {@code consumer} without materialising them into a list. Use this
   * instead of {@link #evaluate} when the full event list would be too large to hold in memory
   * (e.g. execution samples).
   *
   * <p><strong>WARNING — default implementation is O(N) memory and test-only.</strong> The default
   * falls back to {@link #evaluate}, which materialises the full event list. This defeats OOM
   * prevention and must not be used in production paths. Override this method in all production
   * implementations (see {@link io.jafar.mcp.query.DefaultQueryEvaluator}).
   */
  default void consume(
      JFRSession session, JfrPath.Query query, Consumer<Map<String, Object>> consumer)
      throws Exception {
    for (Map<String, Object> event : evaluate(session, query)) {
      consumer.accept(event);
    }
  }
}

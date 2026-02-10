package io.jafar.shell.core;

import java.util.List;

/**
 * Interface for domain-specific query evaluators. Each module (JFR, heap dump) provides its own
 * implementation that parses and evaluates queries against its data format.
 */
public interface QueryEvaluator {

  /**
   * Parses a query string into a query object.
   *
   * @param queryString the query to parse
   * @return parsed query object (implementation-specific)
   * @throws QueryParseException if the query cannot be parsed
   */
  Object parse(String queryString) throws QueryParseException;

  /**
   * Evaluates a parsed query against a session.
   *
   * @param session the session to query
   * @param query the parsed query (from parse())
   * @return query results (typically List of Map for tabular data)
   * @throws Exception if evaluation fails
   */
  Object evaluate(Session session, Object query) throws Exception;

  /**
   * Convenience method to parse and evaluate in one step.
   *
   * @param session the session to query
   * @param queryString the query to execute
   * @return query results
   * @throws Exception if parsing or evaluation fails
   */
  default Object evaluate(Session session, String queryString) throws Exception {
    return evaluate(session, parse(queryString));
  }

  /**
   * Gets the available root types for this query language. For JFR: events, metadata, chunks, cp.
   * For heap dumps: objects, classes, gcroots, dominators.
   *
   * @return list of root type names
   */
  List<String> getRootTypes();

  /**
   * Gets available operators for the pipeline (e.g., top, groupBy, select, stats).
   *
   * @return list of operator names
   */
  List<String> getOperators();

  /**
   * Gets help text for a specific operator.
   *
   * @param operator the operator name
   * @return help text, or null if unknown
   */
  default String getOperatorHelp(String operator) {
    return null;
  }

  /**
   * Creates a lazy value that evaluates the query on demand.
   *
   * @param session the session to query
   * @param query the parsed query
   * @param queryString the original query string (for display)
   * @return a lazy value that evaluates when accessed
   */
  VariableStore.LazyValue createLazyValue(Session session, Object query, String queryString);

  /** Exception thrown when query parsing fails. */
  class QueryParseException extends Exception {
    private final int position;
    private final String queryString;

    public QueryParseException(String message, String queryString, int position) {
      super(message);
      this.queryString = queryString;
      this.position = position;
    }

    public QueryParseException(String message, String queryString, int position, Throwable cause) {
      super(message, cause);
      this.queryString = queryString;
      this.position = position;
    }

    /** Returns the position in the query where the error occurred (-1 if unknown). */
    public int getPosition() {
      return position;
    }

    /** Returns the original query string. */
    public String getQueryString() {
      return queryString;
    }

    /** Returns a formatted error message with position indicator. */
    public String getFormattedMessage() {
      if (position < 0 || queryString == null) {
        return getMessage();
      }
      StringBuilder sb = new StringBuilder();
      sb.append(getMessage()).append("\n");
      sb.append(queryString).append("\n");
      sb.append(" ".repeat(Math.max(0, position))).append("^");
      return sb.toString();
    }
  }
}

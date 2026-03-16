package io.jafar.shell.core;

import java.util.List;

/** Evaluates queries in a module-specific query language against a session. */
public interface QueryEvaluator {

  /**
   * Parses a query string into a module-specific query object.
   *
   * @param queryString the raw query string
   * @return a parsed query object
   * @throws QueryParseException if the query is invalid
   */
  Object parse(String queryString) throws QueryParseException;

  /**
   * Evaluates a parsed query (or raw query string) against a session.
   *
   * @param session the session to query
   * @param query the parsed query object or raw query string
   * @return the result (typically {@code List<Map<String,Object>>})
   * @throws Exception if evaluation fails
   */
  Object evaluate(Session session, Object query) throws Exception;

  /** Returns the root types supported by this query language (e.g. "events", "objects"). */
  List<String> getRootTypes();

  /** Returns the pipeline operators supported by this query language. */
  List<String> getOperators();

  /**
   * Returns help text for a specific operator.
   *
   * @param operator the operator name
   * @return help text, or null if unknown
   */
  String getOperatorHelp(String operator);

  /**
   * Creates a lazy variable value that evaluates the query on demand.
   *
   * @param session the session
   * @param query the parsed query object
   * @param queryString the original query string
   * @return a lazy value
   */
  VariableStore.LazyValue createLazyValue(Session session, Object query, String queryString);

  /** Thrown when a query string cannot be parsed. */
  class QueryParseException extends RuntimeException {
    private final String query;
    private final int position;

    public QueryParseException(String message, String query, int position) {
      super(message);
      this.query = query;
      this.position = position;
    }

    public QueryParseException(String message, String query, int position, Throwable cause) {
      super(message, cause);
      this.query = query;
      this.position = position;
    }

    public String getQuery() {
      return query;
    }

    public int getPosition() {
      return position;
    }
  }
}

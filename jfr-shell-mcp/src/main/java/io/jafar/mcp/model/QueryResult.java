package io.jafar.mcp.model;

import java.util.List;
import java.util.Map;

/**
 * Result of executing a JfrPath query.
 *
 * @param query the executed JfrPath query
 * @param results list of result maps (field name -> value)
 * @param resultCount number of results returned
 * @param success whether query executed successfully
 * @param error error message if execution failed
 * @param explanation optional explanation of query (from natural language translation)
 */
public record QueryResult(
    String query,
    List<Map<String, Object>> results,
    int resultCount,
    boolean success,
    String error,
    String explanation) {

  /**
   * Creates a successful query result.
   *
   * @param query executed query
   * @param results result data
   * @return query result
   */
  public static QueryResult success(String query, List<Map<String, Object>> results) {
    return new QueryResult(query, results, results.size(), true, null, null);
  }

  /**
   * Creates a successful query result with explanation.
   *
   * @param query executed query
   * @param results result data
   * @param explanation query explanation
   * @return query result
   */
  public static QueryResult success(
      String query, List<Map<String, Object>> results, String explanation) {
    return new QueryResult(query, results, results.size(), true, null, explanation);
  }

  /**
   * Creates a failed query result.
   *
   * @param query attempted query
   * @param error error message
   * @return query result
   */
  public static QueryResult failure(String query, String error) {
    return new QueryResult(query, List.of(), 0, false, error, null);
  }
}

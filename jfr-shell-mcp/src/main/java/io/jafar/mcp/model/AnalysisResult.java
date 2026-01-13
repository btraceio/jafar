package io.jafar.mcp.model;

import java.util.List;
import java.util.Map;

/**
 * Result of a pre-built analysis operation.
 *
 * @param analysisType type of analysis performed (e.g., "threads", "gc", "cpu", "allocations")
 * @param query the JfrPath query used for analysis
 * @param results list of result maps
 * @param resultCount number of results
 * @param success whether analysis succeeded
 * @param error error message if failed
 */
public record AnalysisResult(
    String analysisType,
    String query,
    List<Map<String, Object>> results,
    int resultCount,
    boolean success,
    String error) {

  /**
   * Creates a successful analysis result.
   *
   * @param analysisType analysis type
   * @param query query used
   * @param results result data
   * @return analysis result
   */
  public static AnalysisResult success(
      String analysisType, String query, List<Map<String, Object>> results) {
    return new AnalysisResult(analysisType, query, results, results.size(), true, null);
  }

  /**
   * Creates a failed analysis result.
   *
   * @param analysisType analysis type
   * @param query query attempted
   * @param error error message
   * @return analysis result
   */
  public static AnalysisResult failure(String analysisType, String query, String error) {
    return new AnalysisResult(analysisType, query, List.of(), 0, false, error);
  }
}

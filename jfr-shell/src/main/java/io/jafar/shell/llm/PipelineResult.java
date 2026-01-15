package io.jafar.shell.llm;

import java.util.Optional;

/**
 * Result from pipeline execution. Represents the outcome of the multi-step pipeline that processes
 * natural language queries.
 */
public record PipelineResult(
    ResultType type,
    String query,
    String explanation,
    double confidence,
    Optional<String> warning,
    Optional<ClarificationRequest> clarification,
    Optional<PipelineTrace> trace) {

  /** Type of pipeline result. */
  public enum ResultType {
    /** Successfully generated a query. */
    SUCCESS,
    /** Query is ambiguous and needs user clarification. */
    NEEDS_CLARIFICATION,
    /** Pipeline execution error. */
    ERROR
  }

  /**
   * Creates a successful pipeline result.
   *
   * @param query generated JfrPath query
   * @param explanation query explanation
   * @param confidence confidence score (0.0-1.0)
   * @param warning optional warning message
   * @param trace optional pipeline trace
   * @return success result
   */
  public static PipelineResult success(
      String query, String explanation, double confidence, String warning, PipelineTrace trace) {

    return new PipelineResult(
        ResultType.SUCCESS,
        query,
        explanation,
        confidence,
        Optional.ofNullable(warning),
        Optional.empty(),
        Optional.ofNullable(trace));
  }

  /**
   * Creates a clarification request result.
   *
   * @param clarification clarification request
   * @param trace optional pipeline trace
   * @return clarification result
   */
  public static PipelineResult needsClarification(
      ClarificationRequest clarification, PipelineTrace trace) {

    return new PipelineResult(
        ResultType.NEEDS_CLARIFICATION,
        null,
        null,
        0.0,
        Optional.empty(),
        Optional.of(clarification),
        Optional.ofNullable(trace));
  }

  /**
   * Check if result has a generated query.
   *
   * @return true if successful with query
   */
  public boolean hasQuery() {
    return type == ResultType.SUCCESS && query != null;
  }

  /**
   * Check if result needs clarification.
   *
   * @return true if needs clarification
   */
  public boolean needsClarification() {
    return type == ResultType.NEEDS_CLARIFICATION;
  }
}

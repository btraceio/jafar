package io.jafar.shell.llm.tuning;

import io.jafar.shell.llm.LLMException;

/**
 * Represents the result of executing a single test case. Tracks whether the generated query was
 * syntactically valid, semantically equivalent to the expected query, and any errors that occurred.
 */
public record TestResult(
    TestCase testCase,
    String generatedQuery,
    boolean syntaxValid,
    boolean semanticMatch,
    double confidence,
    LLMException error) {

  /**
   * Returns true if the test case passed (valid syntax and semantic match).
   *
   * @return true if successful
   */
  public boolean isSuccess() {
    return syntaxValid && semanticMatch;
  }

  /**
   * Categorizes the type of error that occurred.
   *
   * @return error category string
   */
  public String getErrorCategory() {
    if (error != null) {
      return "LLM_ERROR";
    }
    if (!syntaxValid) {
      return classifySyntaxError(generatedQuery);
    }
    if (!semanticMatch) {
      return "SEMANTIC_MISMATCH";
    }
    return "SUCCESS";
  }

  /**
   * Classifies common syntax errors in JfrPath queries.
   *
   * @param query the generated query
   * @return error classification
   */
  private String classifySyntaxError(String query) {
    if (query == null) {
      return "NULL_QUERY";
    }
    if (query.contains("[") && !query.contains("\\[")) {
      return "WRONG_ARRAY_SYNTAX";
    }
    if (query.contains("select(")) {
      return "INVALID_SELECT";
    }
    if (query.contains("stats(count)")) {
      return "INVALID_STATS_COUNT";
    }
    if (query.contains("jdk.MethodSample")) {
      return "WRONG_EVENT_TYPE";
    }
    return "OTHER_SYNTAX_ERROR";
  }
}

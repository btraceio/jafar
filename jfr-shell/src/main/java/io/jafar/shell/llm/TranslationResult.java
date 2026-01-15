package io.jafar.shell.llm;

import java.util.Optional;

/**
 * Result of translating a natural language query to JfrPath.
 *
 * <p>Can represent:
 *
 * <ul>
 *   <li>Successful translation with a JfrPath query
 *   <li>Conversational response (non-query questions)
 *   <li>Clarification request (ambiguous query needing user input)
 * </ul>
 *
 * @param jfrPathQuery the generated JfrPath query (null for conversational or clarification)
 * @param explanation 1-2 sentence explanation of the query
 * @param confidence confidence score from 0.0 (uncertain) to 1.0 (certain)
 * @param warning optional warning about potential ambiguity or low confidence
 * @param conversationalResponse response for non-query questions (null for data queries)
 * @param clarificationRequest optional request for user clarification
 */
public record TranslationResult(
    String jfrPathQuery,
    String explanation,
    double confidence,
    Optional<String> warning,
    String conversationalResponse,
    Optional<ClarificationRequest> clarificationRequest) {

  /**
   * Returns true if this is a conversational response (no query generated).
   *
   * @return true if conversational
   */
  public boolean isConversational() {
    return conversationalResponse != null;
  }

  /**
   * Returns true if this result requires user clarification.
   *
   * @return true if clarification needed
   */
  public boolean needsClarification() {
    return clarificationRequest.isPresent();
  }

  /**
   * Returns true if this is a successful query translation.
   *
   * @return true if jfrPathQuery is non-null
   */
  public boolean hasQuery() {
    return jfrPathQuery != null && !jfrPathQuery.isBlank();
  }

  /**
   * Creates a successful translation result.
   *
   * @param query the JfrPath query
   * @param explanation explanation of the query
   * @param confidence confidence score
   * @return translation result with query
   */
  public static TranslationResult success(String query, String explanation, double confidence) {
    return new TranslationResult(
        query, explanation, confidence, Optional.empty(), null, Optional.empty());
  }

  /**
   * Creates a successful translation result with a warning.
   *
   * @param query the JfrPath query
   * @param explanation explanation of the query
   * @param confidence confidence score
   * @param warning warning message
   * @return translation result with query and warning
   */
  public static TranslationResult successWithWarning(
      String query, String explanation, double confidence, String warning) {
    return new TranslationResult(
        query, explanation, confidence, Optional.of(warning), null, Optional.empty());
  }

  /**
   * Creates a conversational response result (no query).
   *
   * @param response the conversational response text
   * @return translation result with conversational response
   */
  public static TranslationResult conversational(String response) {
    return new TranslationResult(null, null, 1.0, Optional.empty(), response, Optional.empty());
  }

  /**
   * Creates a clarification request result.
   *
   * @param request the clarification request
   * @return translation result with clarification request
   */
  public static TranslationResult needsClarification(ClarificationRequest request) {
    return new TranslationResult(null, null, 0.0, Optional.empty(), null, Optional.of(request));
  }
}

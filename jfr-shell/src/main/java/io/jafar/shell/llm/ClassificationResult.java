package io.jafar.shell.llm;

import java.util.Optional;

/**
 * Result of query classification containing the identified category and confidence.
 *
 * @param category the classified query category
 * @param confidence confidence score from 0.0 (uncertain) to 1.0 (certain)
 * @param reasoning optional explanation of why this category was chosen
 * @param usedLLM true if LLM was used for classification, false if rule-based
 */
public record ClassificationResult(
    QueryCategory category, double confidence, Optional<String> reasoning, boolean usedLLM) {

  /**
   * Creates a rule-based classification result.
   *
   * @param category the classified category
   * @param confidence confidence score
   * @return classification result marked as rule-based
   */
  public static ClassificationResult fromRules(QueryCategory category, double confidence) {
    return new ClassificationResult(category, confidence, Optional.empty(), false);
  }

  /**
   * Creates an LLM-based classification result.
   *
   * @param category the classified category
   * @param confidence confidence score
   * @param reasoning explanation from LLM
   * @return classification result marked as LLM-based
   */
  public static ClassificationResult fromLLM(
      QueryCategory category, double confidence, String reasoning) {
    return new ClassificationResult(category, confidence, Optional.of(reasoning), true);
  }

  /**
   * Checks if this classification is considered high confidence.
   *
   * @return true if confidence >= 0.85
   */
  public boolean isHighConfidence() {
    return confidence >= 0.85;
  }

  /**
   * Checks if this classification is considered medium confidence.
   *
   * @return true if 0.6 <= confidence < 0.85
   */
  public boolean isMediumConfidence() {
    return confidence >= 0.6 && confidence < 0.85;
  }

  /**
   * Checks if this classification is considered low confidence.
   *
   * @return true if confidence < 0.6
   */
  public boolean isLowConfidence() {
    return confidence < 0.6;
  }

  /**
   * Checks if clarification should be requested from the user.
   *
   * @return true if confidence < 0.5 (very ambiguous)
   */
  public boolean needsClarification() {
    return confidence < 0.5;
  }
}

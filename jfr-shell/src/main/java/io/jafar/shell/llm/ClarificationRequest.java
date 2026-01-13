package io.jafar.shell.llm;

import java.util.Collections;
import java.util.List;

/**
 * Request for user clarification when a query is ambiguous or has multiple valid interpretations.
 *
 * @param originalQuery the original user query that needs clarification
 * @param clarificationQuestion the question to ask the user for disambiguation
 * @param suggestedChoices list of suggested answer choices (2-4 options)
 * @param ambiguityScore score from 0.0 (clear) to 1.0 (maximally ambiguous)
 */
public record ClarificationRequest(
    String originalQuery,
    String clarificationQuestion,
    List<String> suggestedChoices,
    double ambiguityScore) {

  public ClarificationRequest {
    if (originalQuery == null || originalQuery.isBlank()) {
      throw new IllegalArgumentException("Original query cannot be null or blank");
    }
    if (clarificationQuestion == null || clarificationQuestion.isBlank()) {
      throw new IllegalArgumentException("Clarification question cannot be null or blank");
    }
    if (suggestedChoices == null || suggestedChoices.size() < 2) {
      throw new IllegalArgumentException(
          "Suggested choices must have at least 2 options, got: "
              + (suggestedChoices == null ? 0 : suggestedChoices.size()));
    }
    if (ambiguityScore < 0.0 || ambiguityScore > 1.0) {
      throw new IllegalArgumentException(
          "Ambiguity score must be between 0.0 and 1.0, got: " + ambiguityScore);
    }

    // Make suggestedChoices immutable
    suggestedChoices = Collections.unmodifiableList(List.copyOf(suggestedChoices));
  }

  /**
   * Formats the clarification request as a user-friendly message.
   *
   * @return formatted message with question and numbered choices
   */
  public String formatForUser() {
    StringBuilder sb = new StringBuilder();
    sb.append("Your query \"")
        .append(originalQuery)
        .append("\" is ambiguous.\n\n")
        .append(clarificationQuestion)
        .append("\n\n");

    for (int i = 0; i < suggestedChoices.size(); i++) {
      sb.append("  ").append(i + 1).append(") ").append(suggestedChoices.get(i)).append("\n");
    }

    return sb.toString();
  }

  /**
   * Checks if this is a high-ambiguity request.
   *
   * @return true if ambiguityScore >= 0.7
   */
  public boolean isHighAmbiguity() {
    return ambiguityScore >= 0.7;
  }
}

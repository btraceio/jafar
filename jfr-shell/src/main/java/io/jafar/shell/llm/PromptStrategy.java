package io.jafar.shell.llm;

import java.io.IOException;

/**
 * Strategy for building category-specific prompts at different levels of detail.
 *
 * <p>Three-level prompt composition:
 *
 * <ul>
 *   <li><b>MINIMAL</b>: ~2-3KB - Category examples + core rules only
 *   <li><b>ENHANCED</b>: ~6-8KB - MINIMAL + related categories + negative examples
 *   <li><b>FULL</b>: ~15KB - Complete monolithic prompt (backward compatible)
 * </ul>
 */
public class PromptStrategy {

  /** Prompt detail levels for progressive escalation */
  public enum PromptLevel {
    /** Minimal prompt with category-specific content only (~2-3KB) */
    MINIMAL,
    /** Enhanced prompt with related categories and negative examples (~6-8KB) */
    ENHANCED,
    /** Full monolithic prompt with all content (~15KB) */
    FULL;

    /**
     * Returns the next escalation level.
     *
     * @return next level, or null if already at FULL
     */
    public PromptLevel next() {
      return switch (this) {
        case MINIMAL -> ENHANCED;
        case ENHANCED -> FULL;
        case FULL -> null;
      };
    }

    /**
     * Checks if this is the final level (no more escalation possible).
     *
     * @return true if FULL level
     */
    public boolean isFinal() {
      return this == FULL;
    }
  }

  private final ContextBuilder contextBuilder;

  public PromptStrategy(ContextBuilder contextBuilder) {
    this.contextBuilder = contextBuilder;
  }

  /**
   * Builds a prompt at the specified level for the given category.
   *
   * @param category the query category
   * @param level the prompt detail level
   * @return system prompt string
   * @throws RuntimeException if resource loading fails
   */
  public String buildPrompt(QueryCategory category, PromptLevel level) {
    try {
      return switch (level) {
        case MINIMAL -> contextBuilder.buildMinimalPrompt(category);
        case ENHANCED -> contextBuilder.buildEnhancedPrompt(
            category, category.getRelatedCategories());
        case FULL -> contextBuilder.buildSystemPrompt(); // Delegate to existing full prompt
      };
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to build " + level + " prompt for category " + category + ": " + e.getMessage(),
          e);
    }
  }

  /**
   * Selects the starting prompt level based on classification confidence.
   *
   * @param classification the classification result
   * @return appropriate starting level
   */
  public PromptLevel selectStartLevel(ClassificationResult classification) {
    PromptLevel selected;
    String reason;

    // COMPLEX_MULTIOP always starts with full (check first)
    if (classification.category() == QueryCategory.COMPLEX_MULTIOP) {
      selected = PromptLevel.FULL;
      reason = "COMPLEX_MULTIOP category requires full context";
    }
    // Low confidence → start with full
    else if (classification.isLowConfidence()) {
      selected = PromptLevel.FULL;
      reason = "Low confidence (" + classification.confidence() + ") requires full context";
    }
    // High confidence + simple category → start with minimal
    else if (classification.isHighConfidence() && classification.category().isSimple()) {
      selected = PromptLevel.MINIMAL;
      reason = "High confidence (" + classification.confidence() + ") + simple category = minimal";
    }
    // High confidence + complex category → start with enhanced
    else if (classification.isHighConfidence()) {
      selected = PromptLevel.ENHANCED;
      reason =
          "High confidence (" + classification.confidence() + ") + complex category = enhanced";
    }
    // Medium confidence → start with enhanced
    else if (classification.isMediumConfidence()) {
      selected = PromptLevel.ENHANCED;
      reason = "Medium confidence (" + classification.confidence() + ") = enhanced";
    }
    // Default to enhanced for safety
    else {
      selected = PromptLevel.ENHANCED;
      reason = "Default to enhanced for safety";
    }

    if (Boolean.getBoolean("jfr.shell.debug")) {
      System.err.println("=== PROMPT LEVEL SELECTION ===");
      System.err.println("Category: " + classification.category());
      System.err.println("Confidence: " + classification.confidence());
      System.err.println("Selected level: " + selected);
      System.err.println("Reason: " + reason);
      System.err.println("==============================");
    }

    return selected;
  }

  /**
   * Determines if escalation to the next level is needed.
   *
   * @param result the translation result
   * @param attempt the current attempt number (1-based)
   * @param currentLevel the current prompt level
   * @param expectedCategory the category from classification
   * @return true if escalation is needed
   */
  public boolean shouldEscalate(
      TranslationResult result,
      int attempt,
      PromptLevel currentLevel,
      QueryCategory expectedCategory) {
    boolean debug = Boolean.getBoolean("jfr.shell.debug");

    // Don't escalate if already at full level
    if (currentLevel.isFinal()) {
      if (debug) {
        System.err.println("No escalation: already at FULL level");
      }
      return false;
    }

    // Don't escalate after max attempts (3)
    if (attempt >= 3) {
      if (debug) {
        System.err.println("No escalation: max attempts reached");
      }
      return false;
    }

    // Don't escalate for clarification requests
    if (result.needsClarification()) {
      if (debug) {
        System.err.println("No escalation: clarification requested");
      }
      return false;
    }

    // Escalate if we got conversational response but expected a query
    // (Only allow conversational if category was CONVERSATIONAL)
    if (result.isConversational() && expectedCategory != QueryCategory.CONVERSATIONAL) {
      if (debug) {
        System.err.println(
            "ESCALATING: Got conversational response but expected " + expectedCategory + " query");
      }
      return true;
    }

    // Don't escalate for legitimate conversational responses
    if (result.isConversational() && expectedCategory == QueryCategory.CONVERSATIONAL) {
      if (debug) {
        System.err.println("No escalation: legitimate conversational response");
      }
      return false;
    }

    // Escalate if low confidence
    if (result.confidence() < 0.6) {
      if (debug) {
        System.err.println("ESCALATING: Low confidence (" + result.confidence() + ")");
      }
      return true;
    }

    // Escalate if no query generated (and not conversational)
    if (!result.hasQuery()) {
      if (debug) {
        System.err.println("ESCALATING: No query generated");
      }
      return true;
    }

    // Escalate if query has warning about ambiguity
    if (result.warning().isPresent()
        && result.warning().get().toLowerCase().contains("ambiguous")) {
      if (debug) {
        System.err.println("ESCALATING: Query has ambiguity warning");
      }
      return true;
    }

    // No escalation needed
    if (debug) {
      System.err.println("No escalation: result meets quality thresholds");
    }
    return false;
  }

  /**
   * Gets the estimated token count for a prompt level.
   *
   * @param level the prompt level
   * @return approximate token count
   */
  public int estimateTokenCount(PromptLevel level) {
    return switch (level) {
      case MINIMAL -> 750; // ~2.5KB / 4 chars per token ≈ 625 tokens, rounded up
      case ENHANCED -> 2000; // ~7KB / 4 ≈ 1750 tokens, rounded up
      case FULL -> 4000; // ~15KB / 4 ≈ 3750 tokens, rounded up
    };
  }

  /**
   * Calculates the token reduction percentage compared to full prompt.
   *
   * @param level the prompt level
   * @return reduction percentage (0-100)
   */
  public double calculateReduction(PromptLevel level) {
    int fullTokens = estimateTokenCount(PromptLevel.FULL);
    int levelTokens = estimateTokenCount(level);
    return 100.0 * (fullTokens - levelTokens) / fullTokens;
  }
}

package io.jafar.shell.llm.privacy;

import io.jafar.shell.JFRSession;
import io.jafar.shell.llm.LLMConfig;
import java.util.regex.Pattern;

/**
 * Minimizes data sent to LLMs by filtering sensitive information and limiting what context is
 * shared. Ensures only necessary metadata is included, never actual event values or sensitive
 * fields.
 */
public class DataMinimizer {

  private final LLMConfig.PrivacySettings settings;

  /**
   * Creates a data minimizer with the given privacy settings.
   *
   * @param settings privacy settings
   */
  public DataMinimizer(LLMConfig.PrivacySettings settings) {
    this.settings = settings;
  }

  /**
   * Minimizes session context to include only safe metadata.
   *
   * @param session JFR session
   * @return minimized context string
   */
  public String minimizeSessionContext(JFRSession session) {
    StringBuilder context = new StringBuilder();

    // Event type names are safe - no actual data
    context.append("Event types: ").append(session.getAvailableEventTypes().size()).append("\n");

    // Type names only, no values
    if (!settings.allowStackTraces()) {
      context.append("(Stack traces filtered for privacy)").append("\n");
    }

    if (!settings.allowThreadNames()) {
      context.append("(Thread names filtered for privacy)").append("\n");
    }

    if (!settings.allowEventValues()) {
      context.append("(Event values filtered for privacy)").append("\n");
    }

    return context.toString();
  }

  /**
   * Checks if a string contains sensitive data based on configured patterns.
   *
   * @param text text to check
   * @return true if sensitive
   */
  public boolean isSensitive(String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }

    String lower = text.toLowerCase();
    for (String pattern : settings.sensitivePatterns()) {
      try {
        if (Pattern.matches(pattern.toLowerCase(), lower)) {
          return true;
        }
      } catch (Exception e) {
        // Invalid regex, try simple contains
        if (lower.contains(pattern.toLowerCase())) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Scrubs sensitive data from text by replacing matches with [REDACTED].
   *
   * @param text text to scrub
   * @return scrubbed text
   */
  public String scrubSensitiveData(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }

    String result = text;
    for (String pattern : settings.sensitivePatterns()) {
      try {
        // Try regex replacement
        result = result.replaceAll("(?i)" + pattern, "[REDACTED]");
      } catch (Exception e) {
        // Invalid regex, try simple replacement
        result = result.replaceAll("(?i)" + Pattern.quote(pattern), "[REDACTED]");
      }
    }

    return result;
  }

  /**
   * Checks if stack traces are allowed to be shared.
   *
   * @return true if allowed
   */
  public boolean allowStackTraces() {
    return settings.allowStackTraces();
  }

  /**
   * Checks if thread names are allowed to be shared.
   *
   * @return true if allowed
   */
  public boolean allowThreadNames() {
    return settings.allowThreadNames();
  }

  /**
   * Checks if event values are allowed to be shared.
   *
   * @return true if allowed
   */
  public boolean allowEventValues() {
    return settings.allowEventValues();
  }
}

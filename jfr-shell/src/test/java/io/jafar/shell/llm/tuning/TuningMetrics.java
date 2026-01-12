package io.jafar.shell.llm.tuning;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregate metrics for a prompt variant's test results. Includes success rate, syntax validity,
 * semantic matches, error categories, and execution duration.
 */
public record TuningMetrics(
    String variantId,
    int totalTests,
    int successCount,
    double successRate,
    int syntaxValidCount,
    int semanticMatchCount,
    Map<String, Long> errorCategories,
    long durationMs) {

  /**
   * Formats a summary of the metrics as a human-readable string.
   *
   * @return formatted summary
   */
  public String formatSummary() {
    return String.format(
        "Variant: %s\n"
            + "Success Rate: %.1f%% (%d/%d)\n"
            + "Syntax Valid: %d\n"
            + "Semantic Match: %d\n"
            + "Duration: %dms\n"
            + "Top Errors:\n%s",
        variantId,
        successRate * 100,
        successCount,
        totalTests,
        syntaxValidCount,
        semanticMatchCount,
        durationMs,
        formatTopErrors());
  }

  /**
   * Formats the top 5 error categories.
   *
   * @return formatted error list
   */
  private String formatTopErrors() {
    if (errorCategories.isEmpty()) {
      return "  (No errors)";
    }
    return errorCategories.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(5)
        .map(e -> String.format("  - %s: %d", e.getKey(), e.getValue()))
        .collect(Collectors.joining("\n"));
  }
}

package io.jafar.shell.llm.tuning.reports;

import io.jafar.shell.llm.tuning.TestResult;
import io.jafar.shell.llm.tuning.TuningMetrics;
import io.jafar.shell.llm.tuning.TuningResults;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Generates markdown reports comparing prompt variant performance. */
public class TuningReport {

  /**
   * Generates a markdown report comparing all variants.
   *
   * @param allResults list of tuning results for each variant
   * @param outputPath path to write the report
   * @throws IOException if writing fails
   */
  public static void generateMarkdown(List<TuningResults> allResults, Path outputPath)
      throws IOException {
    StringBuilder md = new StringBuilder();

    // Header
    md.append("# LLM Prompt Tuning Report\n\n");
    md.append("Generated: ").append(Instant.now()).append("\n\n");

    // Summary table
    md.append("## Variant Comparison\n\n");
    md.append("| Variant | Success Rate | Syntax Valid | Semantic Match | Duration |\n");
    md.append("|---------|--------------|--------------|----------------|----------|\n");

    for (TuningResults result : allResults) {
      TuningMetrics m = result.calculateMetrics();
      md.append(
          String.format(
              "| %s | %.1f%% | %d/%d | %d/%d | %dms |\n",
              m.variantId(),
              m.successRate() * 100,
              m.syntaxValidCount(),
              m.totalTests(),
              m.semanticMatchCount(),
              m.totalTests(),
              m.durationMs()));
    }

    // Error analysis
    md.append("\n## Error Analysis\n\n");

    for (TuningResults result : allResults) {
      TuningMetrics m = result.calculateMetrics();
      md.append("### ").append(result.getVariantId()).append("\n\n");

      // Show metrics summary
      md.append("**Success Rate:** ")
          .append(String.format("%.1f%%", m.successRate() * 100))
          .append("\n\n");

      if (!m.errorCategories().isEmpty()) {
        md.append("**Error Categories:**\n\n");
        m.errorCategories().entrySet().stream()
            .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
            .forEach(
                e ->
                    md.append("- ")
                        .append(e.getKey())
                        .append(": ")
                        .append(e.getValue())
                        .append("\n"));
        md.append("\n");
      }

      // Show detailed failures
      List<TestResult> failures = result.getFailures();
      if (!failures.isEmpty()) {
        md.append("**Failed Test Cases:**\n\n");

        for (TestResult failure : failures) {
          md.append("#### ").append(failure.testCase().id()).append("\n\n");
          md.append("- **Query:** `").append(failure.testCase().naturalLanguage()).append("`\n");
          md.append("- **Expected:** `").append(failure.testCase().expectedQuery()).append("`\n");
          if (failure.generatedQuery() != null) {
            md.append("- **Generated:** `").append(failure.generatedQuery()).append("`\n");
          } else {
            md.append("- **Generated:** (null)\n");
          }
          md.append("- **Error:** ").append(failure.getErrorCategory()).append("\n");
          if (failure.error() != null) {
            md.append("- **Exception:** ").append(failure.error().getMessage()).append("\n");
          }
          md.append("\n");
        }
      } else {
        md.append("**All test cases passed!**\n\n");
      }
    }

    // Recommendations
    md.append("\n## Recommendations\n\n");

    // Find best variant
    TuningResults bestResult =
        allResults.stream()
            .max(
                (r1, r2) ->
                    Double.compare(
                        r1.calculateMetrics().successRate(), r2.calculateMetrics().successRate()))
            .orElse(null);

    if (bestResult != null) {
      TuningMetrics bestMetrics = bestResult.calculateMetrics();
      md.append("**Best Variant:** ")
          .append(bestMetrics.variantId())
          .append(" with ")
          .append(String.format("%.1f%%", bestMetrics.successRate() * 100))
          .append(" success rate\n\n");

      if (bestMetrics.successRate() < 0.9) {
        md.append("**Areas for Improvement:**\n\n");

        // Analyze common error patterns across all variants
        for (TuningResults result : allResults) {
          TuningMetrics m = result.calculateMetrics();
          if (!m.errorCategories().isEmpty()) {
            String topError =
                m.errorCategories().entrySet().stream()
                    .max((e1, e2) -> Long.compare(e1.getValue(), e2.getValue()))
                    .map(e -> e.getKey())
                    .orElse("UNKNOWN");

            if (topError.equals("WRONG_ARRAY_SYNTAX")) {
              md.append("- Consider emphasizing array syntax rules: Use `/0` not `[0]`\n");
              break;
            } else if (topError.equals("INVALID_SELECT")) {
              md.append("- Consider adding more examples showing `select()` is not supported\n");
              break;
            } else if (topError.equals("WRONG_EVENT_TYPE")) {
              md.append(
                  "- Consider adding validation step: check event type exists in AVAILABLE list\n");
              break;
            }
          }
        }
      } else {
        md.append("Success rate is above 90%, prompts are performing well!\n");
      }
    }

    // Write to file
    Files.createDirectories(outputPath.getParent());
    Files.writeString(outputPath, md.toString());
  }
}

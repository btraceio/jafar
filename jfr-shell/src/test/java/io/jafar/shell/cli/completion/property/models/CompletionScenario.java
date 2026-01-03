package io.jafar.shell.cli.completion.property.models;

import io.jafar.shell.cli.completion.CompletionContext;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Represents a complete completion test scenario with input, expected, and actual results.
 *
 * <p>Used for debugging and reporting when property tests fail, bundling together all information
 * needed to understand why a particular completion invocation didn't meet expectations.
 *
 * @param generatedQuery the input query with cursor position
 * @param expectedCompletion what we expect to see
 * @param actualContext the context determined by CompletionContextAnalyzer
 * @param actualCandidates the candidates returned by the completer
 */
public record CompletionScenario(
    GeneratedQuery generatedQuery,
    ExpectedCompletion expectedCompletion,
    CompletionContext actualContext,
    List<Candidate> actualCandidates) {

  /**
   * Returns a formatted string describing this scenario for debugging.
   *
   * @return multi-line description of the scenario
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("=== Completion Scenario ===\n");
    sb.append(generatedQuery.describe());
    sb.append("\n\nExpected:\n");
    sb.append("  Context Type: ").append(expectedCompletion.contextType()).append("\n");
    if (expectedCompletion.eventType() != null) {
      sb.append("  Event Type: ").append(expectedCompletion.eventType()).append("\n");
    }
    if (!expectedCompletion.fieldPath().isEmpty()) {
      sb.append("  Field Path: ").append(expectedCompletion.fieldPath()).append("\n");
    }
    sb.append("\nActual:\n");
    sb.append("  Context Type: ").append(actualContext.type()).append("\n");
    if (actualContext.eventType() != null) {
      sb.append("  Event Type: ").append(actualContext.eventType()).append("\n");
    }
    if (!actualContext.fieldPath().isEmpty()) {
      sb.append("  Field Path: ").append(actualContext.fieldPath()).append("\n");
    }
    sb.append("  Candidates: ").append(actualCandidates.size()).append("\n");
    return sb.toString();
  }
}

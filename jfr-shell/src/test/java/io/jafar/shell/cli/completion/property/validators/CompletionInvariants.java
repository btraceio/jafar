package io.jafar.shell.cli.completion.property.validators;

import io.jafar.shell.cli.completion.CompletionContext;
import io.jafar.shell.cli.completion.property.models.ValidationResult;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jline.reader.Candidate;

/**
 * Defines universal invariants that must hold for all completion scenarios.
 *
 * <p>These are context-independent rules that apply regardless of what's being completed. Invariant
 * violations indicate fundamental problems with the completion system.
 *
 * <p>Invariants include:
 * - No duplicate candidates
 * - All candidates match partial input (when applicable)
 * - Completion always returns non-null lists
 * - Context type determination is deterministic
 * - No empty candidate values
 */
public class CompletionInvariants {

  /**
   * Checks all universal invariants for a completion result.
   *
   * @param context the completion context
   * @param candidates the list of candidates
   * @return validation result with any violations
   */
  public ValidationResult checkAllInvariants(
      CompletionContext context, List<Candidate> candidates) {

    ValidationResult result = new ValidationResult();

    checkNoDuplicates(candidates, result);
    checkNonNullCandidates(candidates, result);
    checkNonEmptyValues(candidates, result);
    checkPartialInputMatching(context, candidates, result);
    checkCandidateCompleteness(candidates, result);

    return result;
  }

  /**
   * Invariant: No duplicate candidate values.
   *
   * <p>Duplicates provide no value to users and indicate a bug in the completer logic.
   *
   * @param candidates the list of candidates
   * @param result validation result to add errors to
   */
  public void checkNoDuplicates(List<Candidate> candidates, ValidationResult result) {
    Set<String> seen = new HashSet<>();

    for (Candidate c : candidates) {
      String value = c.value();
      if (!seen.add(value)) {
        result.addError("Duplicate candidate: '" + value + "'");
      }
    }
  }

  /**
   * Invariant: All candidates must be non-null.
   *
   * @param candidates the list of candidates
   * @param result validation result to add errors to
   */
  public void checkNonNullCandidates(List<Candidate> candidates, ValidationResult result) {
    if (candidates == null) {
      result.addError("Candidate list is null");
      return;
    }

    for (int i = 0; i < candidates.size(); i++) {
      if (candidates.get(i) == null) {
        result.addError("Null candidate at index " + i);
      }
    }
  }

  /**
   * Invariant: All candidate values must be non-empty.
   *
   * <p>Empty values are meaningless completions and should not be returned.
   *
   * @param candidates the list of candidates
   * @param result validation result to add errors to
   */
  public void checkNonEmptyValues(List<Candidate> candidates, ValidationResult result) {
    for (Candidate c : candidates) {
      if (c.value() == null || c.value().isEmpty()) {
        result.addError("Empty candidate value: " + c);
      }
    }
  }

  /**
   * Invariant: Candidates should match partial input (when applicable).
   *
   * <p>If there's partial input, candidates should start with it (case-insensitive). This is a
   * warning rather than an error since some completers might intentionally suggest alternatives.
   *
   * @param context the completion context
   * @param candidates the list of candidates
   * @param result validation result to add warnings to
   */
  public void checkPartialInputMatching(
      CompletionContext context, List<Candidate> candidates, ValidationResult result) {

    String partial = context.partialInput();
    if (partial == null || partial.isEmpty()) {
      return; // No partial input to match
    }

    String partialLower = partial.toLowerCase();
    int matchCount = 0;
    int totalCount = candidates.size();

    for (Candidate c : candidates) {
      String relevantPortion = extractRelevantPortion(c.value()).toLowerCase();
      if (relevantPortion.startsWith(partialLower)) {
        matchCount++;
      }
    }

    // If less than 50% match, warn (some completers might suggest alternatives)
    if (totalCount > 0 && matchCount < totalCount / 2) {
      result.addWarning(
          "Only "
              + matchCount
              + "/"
              + totalCount
              + " candidates match partial input '"
              + partial
              + "'");
    }
  }

  /**
   * Invariant: Candidates should have the 'complete' flag set appropriately.
   *
   * <p>The complete flag indicates whether selecting the candidate should add a trailing space.
   * Most completions should have this set correctly.
   *
   * @param candidates the list of candidates
   * @param result validation result to add warnings to
   */
  public void checkCandidateCompleteness(List<Candidate> candidates, ValidationResult result) {
    // This is informational - we just collect statistics
    int completeCount = 0;
    int incompleteCount = 0;

    for (Candidate c : candidates) {
      if (c.complete()) {
        completeCount++;
      } else {
        incompleteCount++;
      }
    }

    // Add informational message if all are the same (might indicate missing customization)
    if (candidates.size() > 5 && completeCount == candidates.size()) {
      result.addWarning("All candidates marked as complete - verify this is intentional");
    } else if (candidates.size() > 5 && incompleteCount == candidates.size()) {
      result.addWarning("All candidates marked as incomplete - verify this is intentional");
    }
  }

  /**
   * Checks that completion is deterministic.
   *
   * <p>Running completion twice with the same input should produce the same context type. The
   * actual candidate list may vary (especially if using random metadata), but the context type
   * should be stable.
   *
   * @param context1 first completion context
   * @param context2 second completion context
   * @param result validation result to add errors to
   */
  public void checkDeterminism(
      CompletionContext context1, CompletionContext context2, ValidationResult result) {

    if (context1.type() != context2.type()) {
      result.addError(
          "Non-deterministic context type: "
              + context1.type()
              + " vs "
              + context2.type());
    }

    // Event type should also be deterministic
    if (context1.eventType() != null && context2.eventType() != null) {
      if (!context1.eventType().equals(context2.eventType())) {
        result.addWarning(
            "Event type differs: '"
                + context1.eventType()
                + "' vs '"
                + context2.eventType()
                + "'");
      }
    }
  }

  /**
   * Checks reasonable limits on candidate count.
   *
   * <p>Too many candidates overwhelm users, too few might indicate a problem.
   *
   * @param candidates the list of candidates
   * @param result validation result to add warnings to
   */
  public void checkReasonableCandidateCount(
      List<Candidate> candidates, ValidationResult result) {

    int count = candidates.size();

    if (count > 200) {
      result.addWarning(
          "Very large candidate count ("
              + count
              + ") - consider filtering or limiting results");
    } else if (count == 0) {
      result.addWarning(
          "No candidates returned - verify this is expected for the context");
    }
  }

  /**
   * Checks that candidate display strings are reasonable.
   *
   * <p>Display strings should be human-readable and not excessively long.
   *
   * @param candidates the list of candidates
   * @param result validation result to add warnings to
   */
  public void checkCandidateDisplayStrings(
      List<Candidate> candidates, ValidationResult result) {

    for (Candidate c : candidates) {
      String display = c.displ();
      if (display != null) {
        if (display.length() > 100) {
          result.addWarning("Very long display string (" + display.length() + " chars): " + display.substring(0, 50) + "...");
        }
        if (display.isEmpty()) {
          result.addWarning("Empty display string for candidate: " + c.value());
        }
      }
    }
  }

  // ==================== Helper Methods ====================

  /**
   * Extracts the relevant portion of a candidate for matching against partial input.
   *
   * <p>For paths, this is typically the last segment. For functions, it's the function name.
   *
   * @param value the candidate value
   * @return the relevant portion for matching
   */
  private String extractRelevantPortion(String value) {
    // For paths, extract the last segment
    if (value.contains("/")) {
      return value.substring(value.lastIndexOf("/") + 1);
    }
    // For functions, extract the name before the opening paren
    if (value.contains("(")) {
      return value.substring(0, value.indexOf("("));
    }
    return value;
  }
}

package io.jafar.shell.cli.completion.property.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks validation errors and warnings during completion testing.
 *
 * <p>Provides a structured way to accumulate multiple validation issues and report them together,
 * making property test failures more informative.
 */
public class ValidationResult {
  private final List<String> errors;
  private final List<String> warnings;

  public ValidationResult() {
    this.errors = new ArrayList<>();
    this.warnings = new ArrayList<>();
  }

  /**
   * Adds an error to this validation result.
   *
   * @param error the error message
   */
  public void addError(String error) {
    errors.add(error);
  }

  /**
   * Adds a warning to this validation result.
   *
   * @param warning the warning message
   */
  public void addWarning(String warning) {
    warnings.add(warning);
  }

  /**
   * Returns whether this validation passed (has no errors).
   *
   * @return true if there are no errors
   */
  public boolean isValid() {
    return errors.isEmpty();
  }

  /**
   * Returns whether this validation has any warnings.
   *
   * @return true if there are warnings
   */
  public boolean hasWarnings() {
    return !warnings.isEmpty();
  }

  /**
   * Returns the list of errors.
   *
   * @return unmodifiable list of error messages
   */
  public List<String> getErrors() {
    return Collections.unmodifiableList(errors);
  }

  /**
   * Returns the list of warnings.
   *
   * @return unmodifiable list of warning messages
   */
  public List<String> getWarnings() {
    return Collections.unmodifiableList(warnings);
  }

  /**
   * Returns a formatted report of all errors and warnings.
   *
   * @return multi-line string with all validation messages
   */
  public String getReport() {
    if (isValid() && !hasWarnings()) {
      return "Validation passed";
    }

    StringBuilder sb = new StringBuilder();
    if (!errors.isEmpty()) {
      sb.append("Errors:\n");
      for (String error : errors) {
        sb.append("  - ").append(error).append("\n");
      }
    }
    if (!warnings.isEmpty()) {
      if (!errors.isEmpty()) {
        sb.append("\n");
      }
      sb.append("Warnings:\n");
      for (String warning : warnings) {
        sb.append("  - ").append(warning).append("\n");
      }
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return getReport();
  }
}

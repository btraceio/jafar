package io.jafar.shell.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based validator for JfrPath queries. Detects common LLM mistakes without full parsing,
 * enabling targeted repair.
 */
public class QueryValidator {

  private final Set<String> availableEventTypes;

  /**
   * Creates a validator with knowledge of available event types.
   *
   * @param availableEventTypes set of event types present in the recording
   */
  public QueryValidator(Set<String> availableEventTypes) {
    this.availableEventTypes = availableEventTypes;
  }

  /**
   * Validate a JfrPath query for common mistakes.
   *
   * @param query query to validate
   * @return validation result with list of issues
   */
  public ValidationResult validate(String query) {
    List<Issue> issues = new ArrayList<>();

    // Rule 1: frames[0] -> frames/0
    if (query.contains("frames[") || query.contains("stackTrace[")) {
      issues.add(
          new Issue(
              IssueType.ARRAY_SYNTAX,
              "Array syntax: use frames/0 not frames[0]",
              "Replace [index] with /index"));
    }

    // Rule 2: disallow filter() operator
    if (query.contains("filter(")) {
      issues.add(
          new Issue(
              IssueType.INVALID_OPERATOR,
              "Invalid operator: filter() not supported",
              "Use [condition] syntax instead"));
    }

    // Rule 3: decorator fields need $decorator. prefix
    if (query.contains("decorator.") && !query.contains("$decorator.")) {
      issues.add(
          new Issue(
              IssueType.DECORATOR_PREFIX,
              "Missing $ prefix on decorator fields",
              "Change decorator.field to $decorator.field"));
    }

    // Rule 4: event type must have jdk. prefix or (multiple types)
    Pattern eventPattern = Pattern.compile("events/([a-zA-Z][a-zA-Z0-9.]*)");
    Matcher matcher = eventPattern.matcher(query);
    if (matcher.find()) {
      String eventType = matcher.group(1);
      if (!eventType.contains(".") && !eventType.equals("(")) {
        issues.add(
            new Issue(
                IssueType.EVENT_TYPE,
                "Event type missing namespace: " + eventType,
                "Add jdk. prefix (e.g., jdk." + eventType + ")"));
      }
    }

    // Rule 5: query must be single line (no embedded newlines)
    if (query.contains("\n")) {
      issues.add(
          new Issue(IssueType.MULTILINE, "Query contains newlines", "Collapse to single line"));
    }

    // Rule 6: check for common field name mistakes
    if (query.contains("bytes>") && (query.contains("FileRead") || query.contains("SocketRead"))) {
      if (!query.contains("bytesRead")) {
        issues.add(
            new Issue(
                IssueType.FIELD_NAME,
                "FileRead/SocketRead should use bytesRead not bytes",
                "Change bytes to bytesRead"));
      }
    }

    // Rule 7: ExecutionSample should use sampledThread
    if (query.contains("ExecutionSample") && query.contains("eventThread")) {
      issues.add(
          new Issue(
              IssueType.FIELD_NAME,
              "ExecutionSample should use sampledThread not eventThread",
              "Change eventThread to sampledThread"));
    }

    // Rule 8: Event types must exist in recording
    List<String> usedEventTypes = extractEventTypes(query);
    for (String eventType : usedEventTypes) {
      if (!availableEventTypes.contains(eventType)) {
        issues.add(
            new Issue(
                IssueType.EVENT_TYPE_NOT_FOUND,
                "Event type not found in recording: " + eventType,
                "Use alternative event type (will be auto-substituted)"));
      }
    }

    // Rule 9: Detect projection before groupBy anti-pattern
    if (query.matches(".*events/[^|]+/[^|]+\\s*\\|\\s*groupBy\\s*\\(\\s*value.*")) {
      issues.add(
          new Issue(
              IssueType.PROJECTION_BEFORE_GROUPBY,
              "Invalid pattern: projection before groupBy",
              "Use: events/<type> | groupBy(field/path) not events/<type>/field | groupBy(value)"));
    }

    return new ValidationResult(issues.isEmpty(), issues);
  }

  /**
   * Extract event types from a JfrPath query.
   *
   * @param query JfrPath query
   * @return list of event type names used in query
   */
  private List<String> extractEventTypes(String query) {
    List<String> eventTypes = new ArrayList<>();

    // Match patterns like: events/jdk.ExecutionSample or events/(jdk.Type1|jdk.Type2)
    Pattern singleType = Pattern.compile("events/([a-zA-Z0-9.]+)");
    Pattern multiType = Pattern.compile("events/\\(([^)]+)\\)");

    // Extract single event types
    Matcher singleMatcher = singleType.matcher(query);
    while (singleMatcher.find()) {
      String eventType = singleMatcher.group(1);
      if (!eventType.startsWith("(")) {
        eventTypes.add(eventType);
      }
    }

    // Extract multiple event types from (type1|type2|type3)
    Matcher multiMatcher = multiType.matcher(query);
    while (multiMatcher.find()) {
      String types = multiMatcher.group(1);
      for (String type : types.split("\\|")) {
        eventTypes.add(type.trim());
      }
    }

    return eventTypes;
  }

  /** Types of validation issues. */
  public enum IssueType {
    /** Array syntax error (e.g., frames[0] instead of frames/0). */
    ARRAY_SYNTAX,
    /** Invalid operator (e.g., filter() instead of []). */
    INVALID_OPERATOR,
    /** Missing decorator prefix (e.g., decorator.field instead of $decorator.field). */
    DECORATOR_PREFIX,
    /** Event type missing namespace (e.g., ExecutionSample instead of jdk.ExecutionSample). */
    EVENT_TYPE,
    /** Query contains newlines (should be single line). */
    MULTILINE,
    /** Wrong field name (e.g., bytes instead of bytesRead). */
    FIELD_NAME,
    /** Event type doesn't exist in recording (needs alternative substitution). */
    EVENT_TYPE_NOT_FOUND,
    /** Projection before groupBy anti-pattern (e.g., events/.../field | groupBy(value)). */
    PROJECTION_BEFORE_GROUPBY
  }

  /**
   * Validation issue found in query.
   *
   * @param type type of issue
   * @param description human-readable description
   * @param suggestion how to fix the issue
   */
  public record Issue(IssueType type, String description, String suggestion) {}

  /**
   * Result of validation.
   *
   * @param valid true if query is valid (no issues)
   * @param issues list of validation issues found
   */
  public record ValidationResult(boolean valid, List<Issue> issues) {
    /**
     * Check if query needs repair.
     *
     * @return true if validation found issues that need fixing
     */
    public boolean needsRepair() {
      return !valid && !issues.isEmpty();
    }
  }
}

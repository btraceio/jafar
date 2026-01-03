package io.jafar.shell.cli.completion.property.models;

import io.jafar.shell.cli.completion.MetadataService;
import io.jafar.shell.cli.completion.property.models.CursorPosition.PositionType;

/**
 * Represents a generated JfrPath query with cursor position for completion testing.
 *
 * <p>This class bundles together a JfrPath expression, the cursor position where completion should
 * be invoked, metadata about the position type, and access to metadata for validation.
 *
 * @param expression the JfrPath expression (without "show " prefix)
 * @param cursorPosition the character offset where completion is invoked
 * @param positionType the type of cursor position
 * @param metadataService service for accessing JFR event type metadata
 */
public record GeneratedQuery(
    String expression,
    int cursorPosition,
    PositionType positionType,
    MetadataService metadataService) {

  /**
   * Returns the full line as it would appear in the shell (with "show " prefix).
   *
   * @return the complete command line
   */
  public String getFullLine() {
    return "show " + expression;
  }

  /**
   * Returns the cursor position in the full line (accounting for "show " prefix).
   *
   * @return the cursor position in the full command line
   */
  public int getCursorInFullLine() {
    return 5 + cursorPosition; // "show " is 5 characters
  }

  /**
   * Returns a human-readable description of this query for debugging.
   *
   * @return formatted string describing the query and cursor position
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("Expression: ").append(expression).append("\n");
    sb.append("Cursor: ").append(cursorPosition).append(" (").append(positionType).append(")\n");
    sb.append("Visual: ");
    sb.append(expression.substring(0, cursorPosition));
    sb.append("▐"); // cursor marker
    if (cursorPosition < expression.length()) {
      sb.append(expression.substring(cursorPosition));
    }
    return sb.toString();
  }

  /**
   * Creates a simple query at the end of an expression.
   *
   * @param expression the JfrPath expression
   * @param metadata the metadata service
   * @return a GeneratedQuery with cursor at end
   */
  public static GeneratedQuery atEnd(String expression, MetadataService metadata) {
    return new GeneratedQuery(expression, expression.length(), PositionType.TOKEN_END, metadata);
  }

  /**
   * Creates a simple query at the start of an expression.
   *
   * @param expression the JfrPath expression
   * @param metadata the metadata service
   * @return a GeneratedQuery with cursor at start
   */
  public static GeneratedQuery atStart(String expression, MetadataService metadata) {
    return new GeneratedQuery(expression, 0, PositionType.TOKEN_START, metadata);
  }

  /**
   * Creates a simple query at a specific position in an expression.
   *
   * @param expression the JfrPath expression
   * @param position the cursor position in the expression
   * @param metadata the metadata service
   * @return a GeneratedQuery with cursor at specified position
   */
  public static GeneratedQuery atPosition(
      String expression, int position, MetadataService metadata) {
    // Determine position type based on where it is
    PositionType type = PositionType.STRUCTURAL;
    if (position == 0) {
      type = PositionType.TOKEN_START;
    } else if (position == expression.length()) {
      type = PositionType.TOKEN_END;
    }
    return new GeneratedQuery(expression, position, type, metadata);
  }
}

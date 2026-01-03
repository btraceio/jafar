package io.jafar.shell.cli.completion;

/**
 * Represents a single token in a JfrPath expression.
 *
 * <p>Each token has a type, value, and position information (start/end) that maps back to the
 * original input line. This enables accurate cursor tracking and context-aware completion.
 *
 * @param type The type of this token
 * @param value The string value of this token
 * @param start Character position in line where token starts (inclusive)
 * @param end Character position in line where token ends (exclusive)
 */
public record Token(TokenType type, String value, int start, int end) {

  /**
   * Returns the length of this token in characters.
   *
   * @return The number of characters in this token
   */
  public int length() {
    return end - start;
  }

  /**
   * Checks if the given cursor position is within this token (including boundaries).
   *
   * @param cursor The cursor position to check
   * @return true if cursor is at or between start and end positions
   */
  public boolean containsCursor(int cursor) {
    return cursor >= start && cursor <= end;
  }

  /**
   * Checks if the cursor is at the end of this token.
   *
   * @param cursor The cursor position to check
   * @return true if cursor is exactly at the end position
   */
  public boolean cursorAtEnd(int cursor) {
    return cursor == end;
  }

  /**
   * Checks if the cursor is at the start of this token.
   *
   * @param cursor The cursor position to check
   * @return true if cursor is exactly at the start position
   */
  public boolean cursorAtStart(int cursor) {
    return cursor == start;
  }

  /**
   * Returns a string representation of this token for debugging.
   *
   * @return A formatted string showing type, value, and position
   */
  @Override
  public String toString() {
    return String.format("%s['%s']@%d-%d", type, value, start, end);
  }
}

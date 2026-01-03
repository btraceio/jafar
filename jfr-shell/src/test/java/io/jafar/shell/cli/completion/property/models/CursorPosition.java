package io.jafar.shell.cli.completion.property.models;

import io.jafar.shell.cli.completion.Token;

/**
 * Represents a cursor position within a JfrPath expression with metadata about the position type.
 *
 * @param position the character offset in the expression
 * @param type the type of position (token boundary, structural character, etc.)
 * @param token the token at this position, may be null
 */
public record CursorPosition(int position, PositionType type, Token token) {

  /** Types of cursor positions for completion testing. */
  public enum PositionType {
    /** At the start of a token */
    TOKEN_START,
    /** In the middle of a token */
    TOKEN_MIDDLE,
    /** At the end of a token */
    TOKEN_END,
    /** Between two tokens (in whitespace or adjacent special chars) */
    BETWEEN_TOKENS,
    /** After a structural character (/, [, |, () */
    STRUCTURAL
  }
}

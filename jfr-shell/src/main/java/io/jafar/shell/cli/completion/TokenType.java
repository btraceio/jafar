package io.jafar.shell.cli.completion;

/**
 * Token types for JfrPath expression tokenization.
 *
 * <p>These token types represent the structure and syntax of JfrPath queries, enabling accurate
 * parsing and context-aware completion.
 */
public enum TokenType {
  // Structure tokens
  /** Forward slash: / */
  SLASH,

  /** Pipe operator: | */
  PIPE,

  /** Opening square bracket: [ */
  BRACKET_OPEN,

  /** Closing square bracket: ] */
  BRACKET_CLOSE,

  /** Opening parenthesis: ( */
  PAREN_OPEN,

  /** Closing parenthesis: ) */
  PAREN_CLOSE,

  /** Comma: , */
  COMMA,

  // Operator tokens
  /** Single equals (assignment): = */
  EQUALS,

  /** Double equals (comparison): == */
  DOUBLE_EQUALS,

  /** Not equals: != */
  NOT_EQUALS,

  /** Greater than: > */
  GT,

  /** Greater than or equal: >= */
  GTE,

  /** Less than: < */
  LT,

  /** Less than or equal: <= */
  LTE,

  /** Tilde (regex match): ~ */
  TILDE,

  // Logical operators
  /** Logical AND: && */
  AND,

  /** Logical OR: || */
  OR,

  /** Logical NOT: ! */
  NOT,

  // Content tokens
  /** Identifier (field names, event types, function names) */
  IDENTIFIER,

  /** String literal: "value" or 'value' */
  STRING_LITERAL,

  /** Number literal: 123, -45, 67.89 */
  NUMBER,

  // Special tokens
  /** Whitespace (spaces, tabs) - typically skipped but tracked for position mapping */
  WHITESPACE,

  /** Command word (first token): show, metadata, etc. */
  COMMAND,

  /** Command option: --format, --limit */
  OPTION,

  /** End of input */
  EOF,

  /** Unknown/unrecognized token */
  UNKNOWN
}

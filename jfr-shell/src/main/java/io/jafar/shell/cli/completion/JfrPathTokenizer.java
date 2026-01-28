package io.jafar.shell.cli.completion;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for JfrPath expressions that understands the structure of JFR query syntax.
 *
 * <p>This tokenizer performs a single-pass scan of the input line, identifying tokens such as
 * identifiers, operators, separators, and literals. It preserves exact character positions for
 * accurate cursor-to-token mapping during completion.
 *
 * <p>The tokenizer handles:
 *
 * <ul>
 *   <li>Structure tokens: / | [ ] ( ) ,
 *   <li>Operators: = == != > >= < <= ~
 *   <li>Logical operators: && ||
 *   <li>Content: identifiers, strings, numbers
 *   <li>Special: whitespace, options (--flag)
 * </ul>
 *
 * <p>Performance: Designed for sub-millisecond tokenization of typical JfrPath expressions (100-200
 * chars).
 */
public final class JfrPathTokenizer {

  /**
   * Tokenizes a JfrPath expression into a list of tokens.
   *
   * <p>The returned list preserves exact position information and includes all tokens (including
   * whitespace) for complete line coverage.
   *
   * @param line The input line to tokenize
   * @return List of tokens in order, ending with EOF token
   */
  public List<Token> tokenize(String line) {
    if (line == null || line.isEmpty()) {
      return List.of(new Token(TokenType.EOF, "", 0, 0));
    }

    List<Token> tokens = new ArrayList<>();
    int pos = 0;
    int len = line.length();

    while (pos < len) {
      char c = line.charAt(pos);

      // Whitespace
      if (Character.isWhitespace(c)) {
        int start = pos;
        while (pos < len && Character.isWhitespace(line.charAt(pos))) {
          pos++;
        }
        tokens.add(new Token(TokenType.WHITESPACE, line.substring(start, pos), start, pos));
        continue;
      }

      // String literals
      if (c == '"' || c == '\'') {
        tokens.add(tokenizeString(line, pos));
        pos = tokens.get(tokens.size() - 1).end();
        continue;
      }

      // Numbers (including negative)
      if (Character.isDigit(c)
          || (c == '-' && pos + 1 < len && Character.isDigit(line.charAt(pos + 1)))) {
        tokens.add(tokenizeNumber(line, pos));
        pos = tokens.get(tokens.size() - 1).end();
        continue;
      }

      // Options: --flag
      if (c == '-' && pos + 1 < len && line.charAt(pos + 1) == '-') {
        tokens.add(tokenizeOption(line, pos));
        pos = tokens.get(tokens.size() - 1).end();
        continue;
      }

      // Two-character operators (must check before single-char)
      if (pos + 1 < len) {
        String twoChar = line.substring(pos, pos + 2);
        TokenType twoCharType = matchTwoCharOperator(twoChar);
        if (twoCharType != null) {
          tokens.add(new Token(twoCharType, twoChar, pos, pos + 2));
          pos += 2;
          continue;
        }
      }

      // Single-character operators and separators
      TokenType singleCharType = matchSingleChar(c);
      if (singleCharType != null) {
        tokens.add(new Token(singleCharType, String.valueOf(c), pos, pos + 1));
        pos++;
        continue;
      }

      // Identifiers (includes dots for namespaces like jdk.ExecutionSample)
      if (Character.isJavaIdentifierStart(c)) {
        tokens.add(tokenizeIdentifier(line, pos));
        pos = tokens.get(tokens.size() - 1).end();
        continue;
      }

      // Unknown character - create UNKNOWN token and skip it
      tokens.add(new Token(TokenType.UNKNOWN, String.valueOf(c), pos, pos + 1));
      pos++;
    }

    // Always end with EOF token
    tokens.add(new Token(TokenType.EOF, "", len, len));
    return tokens;
  }

  /**
   * Finds the token at or immediately before the given cursor position.
   *
   * <p>If the cursor is between tokens, returns the token before the cursor. This is useful for
   * determining what the user is currently typing.
   *
   * @param tokens List of tokens to search
   * @param cursor Cursor position in the line
   * @return The token at or before cursor, or null if no token found
   */
  public Token tokenAtCursor(List<Token> tokens, int cursor) {
    if (tokens == null || tokens.isEmpty()) {
      return null;
    }

    // Binary search would be faster, but linear is fine for completion context (~50 tokens max)
    Token lastToken = null;
    for (Token token : tokens) {
      if (token.containsCursor(cursor)) {
        return token;
      }
      if (token.start() > cursor) {
        // Cursor is before this token, return previous
        return lastToken;
      }
      lastToken = token;
    }

    // Cursor is after all tokens, return last non-EOF token
    if (tokens.size() >= 2) {
      Token last = tokens.get(tokens.size() - 1);
      if (last.type() == TokenType.EOF) {
        return tokens.get(tokens.size() - 2);
      }
    }
    return tokens.get(tokens.size() - 1);
  }

  /**
   * Finds the token immediately before the given token in the list.
   *
   * @param tokens List of tokens
   * @param current The reference token
   * @return The previous token, or null if current is the first token
   */
  public Token previousToken(List<Token> tokens, Token current) {
    if (tokens == null || current == null) {
      return null;
    }

    int idx = tokens.indexOf(current);
    if (idx <= 0) {
      return null;
    }
    return tokens.get(idx - 1);
  }

  /**
   * Finds the next non-whitespace token starting from the given index.
   *
   * @param tokens List of tokens
   * @param fromIndex Index to start searching from
   * @return The next non-whitespace token, or null if none found
   */
  public Token nextNonWhitespace(List<Token> tokens, int fromIndex) {
    if (tokens == null || fromIndex < 0 || fromIndex >= tokens.size()) {
      return null;
    }

    for (int i = fromIndex; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.type() != TokenType.WHITESPACE && token.type() != TokenType.EOF) {
        return token;
      }
    }
    return null;
  }

  // Private helper methods

  private Token tokenizeString(String line, int start) {
    char quote = line.charAt(start);
    int pos = start + 1;
    StringBuilder value = new StringBuilder();
    value.append(quote);

    while (pos < line.length()) {
      char c = line.charAt(pos);

      if (c == '\\' && pos + 1 < line.length()) {
        // Escape sequence - include backslash and next char
        value.append(c);
        pos++;
        if (pos < line.length()) {
          value.append(line.charAt(pos));
          pos++;
        }
        continue;
      }

      value.append(c);
      pos++;

      if (c == quote) {
        // Found closing quote
        break;
      }
    }

    return new Token(TokenType.STRING_LITERAL, value.toString(), start, pos);
  }

  private Token tokenizeNumber(String line, int start) {
    int pos = start;

    // Handle optional negative sign
    if (line.charAt(pos) == '-') {
      pos++;
    }

    // Integer part
    while (pos < line.length() && Character.isDigit(line.charAt(pos))) {
      pos++;
    }

    // Decimal part
    if (pos < line.length() && line.charAt(pos) == '.') {
      pos++;
      while (pos < line.length() && Character.isDigit(line.charAt(pos))) {
        pos++;
      }
    }

    return new Token(TokenType.NUMBER, line.substring(start, pos), start, pos);
  }

  private Token tokenizeOption(String line, int start) {
    int pos = start + 2; // Skip --

    while (pos < line.length()) {
      char c = line.charAt(pos);
      if (Character.isLetterOrDigit(c) || c == '-') {
        pos++;
      } else {
        break;
      }
    }

    return new Token(TokenType.OPTION, line.substring(start, pos), start, pos);
  }

  private Token tokenizeIdentifier(String line, int start) {
    int pos = start;

    while (pos < line.length()) {
      char c = line.charAt(pos);
      // Allow dots for namespaced identifiers (jdk.ExecutionSample)
      // Allow $ for decorator fields ($decorator.field)
      if (Character.isJavaIdentifierPart(c) || c == '.' || c == '$') {
        pos++;
      } else {
        break;
      }
    }

    String value = line.substring(start, pos);

    // Determine if this is a command (first non-whitespace token)
    TokenType type = TokenType.IDENTIFIER;
    // Commands would be detected by position in token stream, not here
    // For now, everything is IDENTIFIER

    return new Token(type, value, start, pos);
  }

  private TokenType matchTwoCharOperator(String twoChar) {
    return switch (twoChar) {
      case "==" -> TokenType.DOUBLE_EQUALS;
      case "!=" -> TokenType.NOT_EQUALS;
      case ">=" -> TokenType.GTE;
      case "<=" -> TokenType.LTE;
      case "&&" -> TokenType.AND;
      case "||" -> TokenType.OR;
      default -> null;
    };
  }

  private TokenType matchSingleChar(char c) {
    return switch (c) {
      case '/' -> TokenType.SLASH;
      case '|' -> TokenType.PIPE;
      case '[' -> TokenType.BRACKET_OPEN;
      case ']' -> TokenType.BRACKET_CLOSE;
      case '(' -> TokenType.PAREN_OPEN;
      case ')' -> TokenType.PAREN_CLOSE;
      case ',' -> TokenType.COMMA;
      case '=' -> TokenType.EQUALS;
      case '>' -> TokenType.GT;
      case '<' -> TokenType.LT;
      case '~' -> TokenType.TILDE;
      case '!' -> TokenType.NOT;
      default -> null;
    };
  }
}

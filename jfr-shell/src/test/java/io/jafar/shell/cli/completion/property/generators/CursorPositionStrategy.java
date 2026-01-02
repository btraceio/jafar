package io.jafar.shell.cli.completion.property.generators;

import io.jafar.shell.cli.completion.JfrPathTokenizer;
import io.jafar.shell.cli.completion.Token;
import io.jafar.shell.cli.completion.TokenType;
import io.jafar.shell.cli.completion.property.models.CursorPosition;
import io.jafar.shell.cli.completion.property.models.CursorPosition.PositionType;
import java.util.ArrayList;
import java.util.List;

/**
 * Strategy for generating meaningful cursor positions within JfrPath expressions.
 *
 * <p>This class analyzes an expression and identifies positions where completion is likely to be
 * triggered: token boundaries, after structural characters, and optionally in the middle of
 * identifiers. By focusing on completion-relevant positions, we avoid testing meaningless positions
 * (like mid-whitespace) while maximizing coverage of actual completion scenarios.
 */
public class CursorPositionStrategy {

  private final JfrPathTokenizer tokenizer;
  private final boolean includeMiddleOfTokens;

  /**
   * Creates a cursor position strategy.
   *
   * @param includeMiddleOfTokens whether to include positions in the middle of identifier tokens
   */
  public CursorPositionStrategy(boolean includeMiddleOfTokens) {
    this.tokenizer = new JfrPathTokenizer();
    this.includeMiddleOfTokens = includeMiddleOfTokens;
  }

  /** Creates a cursor position strategy that includes middle-of-token positions. */
  public CursorPositionStrategy() {
    this(true);
  }

  /**
   * Generates all meaningful cursor positions for an expression.
   *
   * @param expression the JfrPath expression (without "show " prefix)
   * @return list of cursor positions with metadata
   */
  public List<CursorPosition> generatePositions(String expression) {
    if (expression == null || expression.isEmpty()) {
      return List.of(new CursorPosition(0, PositionType.TOKEN_START, null));
    }

    List<CursorPosition> positions = new ArrayList<>();

    // Add position at start
    positions.add(new CursorPosition(0, PositionType.TOKEN_START, null));

    // Add token-based positions
    positions.addAll(tokenBoundaryPositions(expression));

    // Add structural positions
    positions.addAll(structuralPositions(expression));

    // Optionally add middle-of-token positions
    if (includeMiddleOfTokens) {
      positions.addAll(middleOfTokenPositions(expression));
    }

    // Add position at end
    positions.add(new CursorPosition(expression.length(), PositionType.TOKEN_END, null));

    // Remove duplicates by position
    return deduplicateByPosition(positions);
  }

  /**
   * Generates positions at token boundaries (start and end of each token).
   *
   * @param expression the expression to analyze
   * @return list of cursor positions at token boundaries
   */
  private List<CursorPosition> tokenBoundaryPositions(String expression) {
    List<CursorPosition> positions = new ArrayList<>();

    // Prepend "show " to get correct tokenization
    String fullLine = "show " + expression;
    List<Token> tokens = tokenizer.tokenize(fullLine);

    for (Token token : tokens) {
      // Skip whitespace and EOF tokens
      if (token.type() == TokenType.WHITESPACE || token.type() == TokenType.EOF) {
        continue;
      }

      // Adjust positions by removing "show " offset
      int adjustedStart = Math.max(0, token.start() - 5);
      int adjustedEnd = Math.max(0, token.end() - 5);

      // Only add positions within the expression bounds
      if (adjustedStart >= 0 && adjustedStart <= expression.length()) {
        positions.add(new CursorPosition(adjustedStart, PositionType.TOKEN_START, token));
      }

      if (adjustedEnd >= 0 && adjustedEnd <= expression.length()) {
        positions.add(new CursorPosition(adjustedEnd, PositionType.TOKEN_END, token));
      }
    }

    return positions;
  }

  /**
   * Generates positions immediately after structural characters (/, [, ], |, (, ), ,).
   *
   * <p>These positions are critical for completion testing as they represent natural completion
   * trigger points in JfrPath syntax.
   *
   * @param expression the expression to analyze
   * @return list of cursor positions after structural characters
   */
  private List<CursorPosition> structuralPositions(String expression) {
    List<CursorPosition> positions = new ArrayList<>();

    for (int i = 0; i < expression.length(); i++) {
      char c = expression.charAt(i);

      // Check for structural characters that trigger completion
      if (isStructuralChar(c)) {
        int positionAfter = i + 1;
        if (positionAfter <= expression.length()) {
          positions.add(new CursorPosition(positionAfter, PositionType.STRUCTURAL, null));
        }
      }
    }

    return positions;
  }

  /**
   * Generates positions in the middle of identifier tokens.
   *
   * <p>This helps test completion with partial input (e.g., "show eve" → "events").
   *
   * @param expression the expression to analyze
   * @return list of cursor positions in the middle of identifiers
   */
  private List<CursorPosition> middleOfTokenPositions(String expression) {
    List<CursorPosition> positions = new ArrayList<>();

    // Prepend "show " to get correct tokenization
    String fullLine = "show " + expression;
    List<Token> tokens = tokenizer.tokenize(fullLine);

    for (Token token : tokens) {
      // Only consider identifier tokens that are long enough
      if (token.type() != TokenType.IDENTIFIER || token.value().length() < 3) {
        continue;
      }

      // Add position in the middle of the identifier
      int tokenLength = token.end() - token.start();
      int middleOffset = tokenLength / 2;
      int middlePosition = token.start() + middleOffset;

      // Adjust for "show " offset
      int adjustedPosition = Math.max(0, middlePosition - 5);

      if (adjustedPosition > 0 && adjustedPosition < expression.length()) {
        positions.add(new CursorPosition(adjustedPosition, PositionType.TOKEN_MIDDLE, token));
      }
    }

    return positions;
  }

  /**
   * Removes duplicate positions, keeping the first occurrence of each position value.
   *
   * @param positions the list of positions (may contain duplicates)
   * @return list with duplicates removed
   */
  private List<CursorPosition> deduplicateByPosition(List<CursorPosition> positions) {
    List<CursorPosition> result = new ArrayList<>();
    List<Integer> seenPositions = new ArrayList<>();

    for (CursorPosition pos : positions) {
      if (!seenPositions.contains(pos.position())) {
        result.add(pos);
        seenPositions.add(pos.position());
      }
    }

    return result;
  }

  /**
   * Checks if a character is a structural character in JfrPath syntax.
   *
   * @param c the character to check
   * @return true if the character is structural
   */
  private boolean isStructuralChar(char c) {
    return c == '/' || c == '[' || c == ']' || c == '|' || c == '(' || c == ')' || c == ',';
  }

  /**
   * Generates a single strategic cursor position for an expression.
   *
   * <p>This is useful when you want just one meaningful position rather than all possible
   * positions. The strategy prioritizes completion-likely positions.
   *
   * @param expression the expression
   * @return a strategic cursor position
   */
  public CursorPosition strategicPosition(String expression) {
    if (expression == null || expression.isEmpty()) {
      return new CursorPosition(0, PositionType.TOKEN_START, null);
    }

    // Priority 1: Position after last structural character
    for (int i = expression.length() - 1; i >= 0; i--) {
      char c = expression.charAt(i);
      if (isStructuralChar(c)) {
        return new CursorPosition(i + 1, PositionType.STRUCTURAL, null);
      }
    }

    // Priority 2: Position at end of expression
    return new CursorPosition(expression.length(), PositionType.TOKEN_END, null);
  }

  /**
   * Generates positions that are specifically relevant for a given completion context type.
   *
   * <p>This allows targeted testing of specific completion scenarios without generating every
   * possible position.
   *
   * @param expression the expression
   * @param targetContext the context type to target (e.g., FILTER_FIELD, PIPELINE_OPERATOR)
   * @return list of positions relevant for the target context
   */
  public List<CursorPosition> positionsForContext(String expression, String targetContext) {
    List<CursorPosition> positions = new ArrayList<>();

    switch (targetContext) {
      case "FILTER_FIELD":
        // After '[' or after '&& ' or '|| '
        for (int i = 0; i < expression.length(); i++) {
          if (expression.charAt(i) == '[') {
            positions.add(new CursorPosition(i + 1, PositionType.STRUCTURAL, null));
          }
        }
        break;

      case "PIPELINE_OPERATOR":
        // After '|'
        for (int i = 0; i < expression.length(); i++) {
          if (expression.charAt(i) == '|' && (i + 1 >= expression.length() || expression.charAt(i + 1) != '|')) {
            positions.add(new CursorPosition(i + 1, PositionType.STRUCTURAL, null));
          }
        }
        break;

      case "FUNCTION_PARAM":
        // After '('
        for (int i = 0; i < expression.length(); i++) {
          if (expression.charAt(i) == '(') {
            positions.add(new CursorPosition(i + 1, PositionType.STRUCTURAL, null));
          }
        }
        break;

      case "FIELD_PATH":
        // After '/' but not immediately after a root type
        boolean foundFirstSlash = false;
        for (int i = 0; i < expression.length(); i++) {
          if (expression.charAt(i) == '/') {
            if (foundFirstSlash) {
              positions.add(new CursorPosition(i + 1, PositionType.STRUCTURAL, null));
            }
            foundFirstSlash = true;
          }
        }
        break;

      default:
        // Return all positions for unknown contexts
        return generatePositions(expression);
    }

    return positions.isEmpty() ? List.of(strategicPosition(expression)) : positions;
  }
}

package io.jafar.shell.cli.completion.property.mutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mutation operators for systematically transforming JfrPath expressions.
 *
 * <p>These operators are designed to test completion robustness at syntax boundaries by creating
 * variations of valid expressions that may trigger edge cases.
 */
public class MutationOperators {

  private final Random random;

  public MutationOperators() {
    this(new Random());
  }

  public MutationOperators(Random random) {
    this.random = random;
  }

  /** Result of a mutation operation. */
  public record MutationResult(
      String mutatedExpression,
      int suggestedCursorPosition,
      MutationType type,
      String description) {

    public static MutationResult unchanged(String expr) {
      return new MutationResult(expr, expr.length(), MutationType.NONE, "No mutation applied");
    }
  }

  /** Types of mutations that can be applied. */
  public enum MutationType {
    NONE,
    INSERT_CHAR,
    DELETE_CHAR,
    SWAP_CHARS,
    REPLACE_OPERATOR,
    INSERT_WHITESPACE,
    REMOVE_WHITESPACE,
    DUPLICATE_TOKEN,
    TRUNCATE,
    DOUBLE_DELIMITER,
    INCOMPLETE_BRACKET,
    INCOMPLETE_PAREN
  }

  // ==================== Character-Level Mutations ====================

  /** Inserts a character at the specified position. */
  public MutationResult insertCharacter(String expr, int pos, char ch) {
    if (pos < 0 || pos > expr.length()) {
      return MutationResult.unchanged(expr);
    }

    String result = expr.substring(0, pos) + ch + expr.substring(pos);
    return new MutationResult(
        result, pos + 1, MutationType.INSERT_CHAR, "Inserted '" + ch + "' at position " + pos);
  }

  /** Inserts a random character at a random position. */
  public MutationResult insertRandomCharacter(String expr) {
    if (expr.isEmpty()) {
      return MutationResult.unchanged(expr);
    }

    int pos = random.nextInt(expr.length() + 1);
    char ch = randomChar();
    return insertCharacter(expr, pos, ch);
  }

  /** Deletes the character at the specified position. */
  public MutationResult deleteCharacter(String expr, int pos) {
    if (pos < 0 || pos >= expr.length()) {
      return MutationResult.unchanged(expr);
    }

    char deleted = expr.charAt(pos);
    String result = expr.substring(0, pos) + expr.substring(pos + 1);
    int cursor = Math.min(pos, result.length());
    return new MutationResult(
        result, cursor, MutationType.DELETE_CHAR, "Deleted '" + deleted + "' at position " + pos);
  }

  /** Deletes a random character. */
  public MutationResult deleteRandomCharacter(String expr) {
    if (expr.isEmpty()) {
      return MutationResult.unchanged(expr);
    }

    int pos = random.nextInt(expr.length());
    return deleteCharacter(expr, pos);
  }

  /** Swaps two adjacent characters. */
  public MutationResult swapCharacters(String expr, int pos) {
    if (pos < 0 || pos >= expr.length() - 1) {
      return MutationResult.unchanged(expr);
    }

    char[] chars = expr.toCharArray();
    char temp = chars[pos];
    chars[pos] = chars[pos + 1];
    chars[pos + 1] = temp;
    String result = new String(chars);
    return new MutationResult(
        result,
        pos + 2,
        MutationType.SWAP_CHARS,
        "Swapped chars at positions " + pos + " and " + (pos + 1));
  }

  /** Swaps two random adjacent characters. */
  public MutationResult swapRandomCharacters(String expr) {
    if (expr.length() < 2) {
      return MutationResult.unchanged(expr);
    }

    int pos = random.nextInt(expr.length() - 1);
    return swapCharacters(expr, pos);
  }

  // ==================== Operator Mutations ====================

  private static final String[][] OPERATOR_PAIRS = {
    {"==", "!="}, {"!=", "=="}, {">", "<"}, {"<", ">"},
    {">=", "<="}, {"<=", ">="}, {"&&", "||"}, {"||", "&&"},
    {"~", "="}, {">", ">="}, {"<", "<="}
  };

  /** Replaces an operator with a similar one. */
  public MutationResult replaceOperator(String expr, String oldOp, String newOp) {
    int idx = expr.indexOf(oldOp);
    if (idx == -1) {
      return MutationResult.unchanged(expr);
    }

    String result = expr.substring(0, idx) + newOp + expr.substring(idx + oldOp.length());
    return new MutationResult(
        result,
        idx + newOp.length(),
        MutationType.REPLACE_OPERATOR,
        "Replaced '" + oldOp + "' with '" + newOp + "'");
  }

  /** Replaces a random operator with a similar one. */
  public MutationResult replaceRandomOperator(String expr) {
    List<int[]> operatorPositions = new ArrayList<>();

    for (String[] pair : OPERATOR_PAIRS) {
      String op = pair[0];
      int idx = 0;
      while ((idx = expr.indexOf(op, idx)) != -1) {
        operatorPositions.add(new int[] {idx, op.length()});
        idx += op.length();
      }
    }

    if (operatorPositions.isEmpty()) {
      return MutationResult.unchanged(expr);
    }

    int[] selected = operatorPositions.get(random.nextInt(operatorPositions.size()));
    int pos = selected[0];
    int len = selected[1];
    String oldOp = expr.substring(pos, pos + len);

    // Find replacement
    for (String[] pair : OPERATOR_PAIRS) {
      if (pair[0].equals(oldOp)) {
        return replaceOperator(expr, oldOp, pair[1]);
      }
    }

    return MutationResult.unchanged(expr);
  }

  // ==================== Whitespace Mutations ====================

  /** Inserts whitespace at the specified position. */
  public MutationResult insertWhitespace(String expr, int pos) {
    return insertCharacter(expr, pos, ' ');
  }

  /** Inserts whitespace at a random position. */
  public MutationResult insertRandomWhitespace(String expr) {
    if (expr.isEmpty()) {
      return MutationResult.unchanged(expr);
    }

    int pos = random.nextInt(expr.length() + 1);
    MutationResult result = insertWhitespace(expr, pos);
    return new MutationResult(
        result.mutatedExpression(),
        result.suggestedCursorPosition(),
        MutationType.INSERT_WHITESPACE,
        "Inserted whitespace at position " + pos);
  }

  /** Removes whitespace at the specified position. */
  public MutationResult removeWhitespace(String expr, int pos) {
    if (pos < 0 || pos >= expr.length() || !Character.isWhitespace(expr.charAt(pos))) {
      return MutationResult.unchanged(expr);
    }

    return deleteCharacter(expr, pos);
  }

  /** Removes a random whitespace character. */
  public MutationResult removeRandomWhitespace(String expr) {
    List<Integer> whitespacePositions = new ArrayList<>();
    for (int i = 0; i < expr.length(); i++) {
      if (Character.isWhitespace(expr.charAt(i))) {
        whitespacePositions.add(i);
      }
    }

    if (whitespacePositions.isEmpty()) {
      return MutationResult.unchanged(expr);
    }

    int pos = whitespacePositions.get(random.nextInt(whitespacePositions.size()));
    MutationResult result = removeWhitespace(expr, pos);
    return new MutationResult(
        result.mutatedExpression(),
        result.suggestedCursorPosition(),
        MutationType.REMOVE_WHITESPACE,
        "Removed whitespace at position " + pos);
  }

  // ==================== Structural Mutations ====================

  /** Duplicates a delimiter character (/, |, [, etc.). */
  public MutationResult duplicateDelimiter(String expr) {
    char[] delimiters = {'/', '|', '[', ']', '(', ')', ','};
    List<Integer> delimiterPositions = new ArrayList<>();

    for (int i = 0; i < expr.length(); i++) {
      char c = expr.charAt(i);
      for (char delim : delimiters) {
        if (c == delim) {
          delimiterPositions.add(i);
          break;
        }
      }
    }

    if (delimiterPositions.isEmpty()) {
      return MutationResult.unchanged(expr);
    }

    int pos = delimiterPositions.get(random.nextInt(delimiterPositions.size()));
    char delim = expr.charAt(pos);
    String result = expr.substring(0, pos + 1) + delim + expr.substring(pos + 1);
    return new MutationResult(
        result,
        pos + 2,
        MutationType.DOUBLE_DELIMITER,
        "Duplicated delimiter '" + delim + "' at position " + pos);
  }

  /** Truncates the expression at the specified position. */
  public MutationResult truncateAtPosition(String expr, int pos) {
    if (pos < 0 || pos > expr.length()) {
      return MutationResult.unchanged(expr);
    }

    String result = expr.substring(0, pos);
    return new MutationResult(
        result, result.length(), MutationType.TRUNCATE, "Truncated at position " + pos);
  }

  /** Truncates at a random structural boundary. */
  public MutationResult truncateAtBoundary(String expr) {
    List<Integer> boundaries = findStructuralBoundaries(expr);
    if (boundaries.isEmpty()) {
      return MutationResult.unchanged(expr);
    }

    int pos = boundaries.get(random.nextInt(boundaries.size()));
    return truncateAtPosition(expr, pos);
  }

  /** Creates an incomplete bracket by removing the closing bracket. */
  public MutationResult createIncompleteBracket(String expr) {
    int openBracket = expr.indexOf('[');
    int closeBracket = expr.indexOf(']');

    if (openBracket == -1 || closeBracket == -1 || closeBracket <= openBracket) {
      return MutationResult.unchanged(expr);
    }

    String result = expr.substring(0, closeBracket) + expr.substring(closeBracket + 1);
    return new MutationResult(
        result,
        closeBracket,
        MutationType.INCOMPLETE_BRACKET,
        "Removed closing bracket at position " + closeBracket);
  }

  /** Creates an incomplete parenthesis by removing the closing paren. */
  public MutationResult createIncompleteParen(String expr) {
    int lastCloseParen = expr.lastIndexOf(')');
    if (lastCloseParen == -1) {
      return MutationResult.unchanged(expr);
    }

    // Find matching open paren
    int depth = 0;
    int matchingOpen = -1;
    for (int i = lastCloseParen; i >= 0; i--) {
      char c = expr.charAt(i);
      if (c == ')') depth++;
      else if (c == '(') {
        depth--;
        if (depth == 0) {
          matchingOpen = i;
          break;
        }
      }
    }

    if (matchingOpen == -1) {
      return MutationResult.unchanged(expr);
    }

    String result = expr.substring(0, lastCloseParen) + expr.substring(lastCloseParen + 1);
    return new MutationResult(
        result,
        lastCloseParen,
        MutationType.INCOMPLETE_PAREN,
        "Removed closing paren at position " + lastCloseParen);
  }

  // ==================== Targeted Mutations for Known Issues ====================

  /** Creates mutations targeting filter completion issues. */
  public List<MutationResult> createFilterMutations(String expr) {
    List<MutationResult> mutations = new ArrayList<>();

    // Find filter boundaries
    int bracketOpen = expr.indexOf('[');
    int bracketClose = expr.indexOf(']');

    if (bracketOpen == -1) {
      // Add opening bracket at end of event type
      int slashAfterEvents = expr.indexOf('/', 7); // After "events/"
      if (slashAfterEvents == -1) {
        slashAfterEvents = expr.length();
      }
      mutations.add(
          new MutationResult(
              expr.substring(0, slashAfterEvents) + "[",
              slashAfterEvents + 1,
              MutationType.INCOMPLETE_BRACKET,
              "Added incomplete filter bracket"));
    } else {
      // Mid-field completion
      String beforeBracket = expr.substring(0, bracketOpen + 1);
      String afterBracket = bracketClose != -1 ? expr.substring(bracketClose) : "";
      mutations.add(
          new MutationResult(
              beforeBracket + "st",
              bracketOpen + 3,
              MutationType.TRUNCATE,
              "Partial field name in filter"));

      // After field, before operator
      mutations.add(
          new MutationResult(
              beforeBracket + "startTime ",
              bracketOpen + 11,
              MutationType.TRUNCATE,
              "Field name with space, waiting for operator"));

      // After operator, before value
      mutations.add(
          new MutationResult(
              beforeBracket + "startTime > ",
              bracketOpen + 13,
              MutationType.TRUNCATE,
              "After operator, waiting for value"));

      // After logical operator
      mutations.add(
          new MutationResult(
              beforeBracket + "startTime > 0 && ",
              bracketOpen + 18,
              MutationType.TRUNCATE,
              "After && operator"));

      // Incomplete close bracket
      if (bracketClose != -1) {
        mutations.add(createIncompleteBracket(expr));
      }
    }

    return mutations;
  }

  /** Creates mutations targeting pipeline completion issues. */
  public List<MutationResult> createPipelineMutations(String expr) {
    List<MutationResult> mutations = new ArrayList<>();

    int pipePos = expr.indexOf('|');

    if (pipePos == -1) {
      // Add pipe at end
      mutations.add(
          new MutationResult(
              expr + " |", expr.length() + 2, MutationType.TRUNCATE, "Added incomplete pipe"));

      mutations.add(
          new MutationResult(
              expr + " | ",
              expr.length() + 3,
              MutationType.TRUNCATE,
              "Added pipe with trailing space"));
    } else {
      // Double pipe
      mutations.add(
          new MutationResult(
              expr.substring(0, pipePos) + "||" + expr.substring(pipePos + 1),
              pipePos + 2,
              MutationType.DOUBLE_DELIMITER,
              "Double pipe"));

      // Pipe with extra space
      mutations.add(
          new MutationResult(
              expr.substring(0, pipePos) + "| |" + expr.substring(pipePos + 1),
              pipePos + 3,
              MutationType.INSERT_WHITESPACE,
              "Pipe with extra space"));

      // Mid-function completion
      int funcStart = pipePos + 1;
      while (funcStart < expr.length() && Character.isWhitespace(expr.charAt(funcStart))) {
        funcStart++;
      }
      if (funcStart < expr.length()) {
        int endIdx = Math.min(funcStart + 3, expr.length());
        mutations.add(
            new MutationResult(
                expr.substring(0, endIdx),
                endIdx,
                MutationType.TRUNCATE,
                "Mid-function name completion"));
      }

      // Incomplete function paren
      int parenOpen = expr.indexOf('(', pipePos);
      int parenClose = expr.indexOf(')', parenOpen);
      if (parenOpen != -1 && parenClose != -1) {
        mutations.add(createIncompleteParen(expr));
      }
    }

    return mutations;
  }

  /** Creates mutations targeting nested field path issues. */
  public List<MutationResult> createNestedPathMutations(String expr) {
    List<MutationResult> mutations = new ArrayList<>();

    // Find all slashes after the event type
    int typeEnd = expr.indexOf('/', 7); // After "events/"
    if (typeEnd == -1) {
      typeEnd = expr.length();
    }

    // Trailing slash
    if (!expr.endsWith("/")) {
      mutations.add(
          new MutationResult(
              expr + "/", expr.length() + 1, MutationType.INSERT_CHAR, "Added trailing slash"));
    }

    // Double slash after type
    mutations.add(
        new MutationResult(
            expr.substring(0, typeEnd) + "//" + expr.substring(typeEnd + 1),
            typeEnd + 2,
            MutationType.DOUBLE_DELIMITER,
            "Double slash after type"));

    // Double slash in path
    int lastSlash = expr.lastIndexOf('/');
    if (lastSlash > typeEnd) {
      mutations.add(
          new MutationResult(
              expr.substring(0, lastSlash) + "//" + expr.substring(lastSlash + 1),
              lastSlash + 2,
              MutationType.DOUBLE_DELIMITER,
              "Double slash in path"));
    }

    // Truncate mid-field
    if (lastSlash != -1 && lastSlash < expr.length() - 3) {
      mutations.add(
          new MutationResult(
              expr.substring(0, lastSlash + 3),
              lastSlash + 3,
              MutationType.TRUNCATE,
              "Truncated mid-field name"));
    }

    return mutations;
  }

  // ==================== Helper Methods ====================

  private char randomChar() {
    String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_./[]|()";
    return chars.charAt(random.nextInt(chars.length()));
  }

  private List<Integer> findStructuralBoundaries(String expr) {
    List<Integer> boundaries = new ArrayList<>();
    char[] delimiters = {'/', '|', '[', ']', '(', ')', ',', ' '};

    for (int i = 0; i < expr.length(); i++) {
      char c = expr.charAt(i);
      for (char delim : delimiters) {
        if (c == delim) {
          boundaries.add(i);
          boundaries.add(i + 1);
          break;
        }
      }
    }

    // Remove duplicates and sort
    return boundaries.stream().distinct().sorted().toList();
  }

  /** Applies a random mutation to the expression. */
  public MutationResult applyRandomMutation(String expr) {
    int choice = random.nextInt(12);

    return switch (choice) {
      case 0 -> insertRandomCharacter(expr);
      case 1 -> deleteRandomCharacter(expr);
      case 2 -> swapRandomCharacters(expr);
      case 3 -> replaceRandomOperator(expr);
      case 4 -> insertRandomWhitespace(expr);
      case 5 -> removeRandomWhitespace(expr);
      case 6 -> duplicateDelimiter(expr);
      case 7 -> truncateAtBoundary(expr);
      case 8 -> createIncompleteBracket(expr);
      case 9 -> createIncompleteParen(expr);
      case 10 -> {
        List<MutationResult> filterMutations = createFilterMutations(expr);
        yield filterMutations.isEmpty()
            ? MutationResult.unchanged(expr)
            : filterMutations.get(random.nextInt(filterMutations.size()));
      }
      case 11 -> {
        List<MutationResult> pipelineMutations = createPipelineMutations(expr);
        yield pipelineMutations.isEmpty()
            ? MutationResult.unchanged(expr)
            : pipelineMutations.get(random.nextInt(pipelineMutations.size()));
      }
      default -> MutationResult.unchanged(expr);
    };
  }

  /** Applies multiple mutations to create a heavily mutated expression. */
  public MutationResult applyMultipleMutations(String expr, int count) {
    String current = expr;
    StringBuilder descriptions = new StringBuilder();

    for (int i = 0; i < count; i++) {
      MutationResult result = applyRandomMutation(current);
      if (result.type() != MutationType.NONE) {
        current = result.mutatedExpression();
        if (!descriptions.isEmpty()) {
          descriptions.append("; ");
        }
        descriptions.append(result.description());
      }
    }

    return new MutationResult(
        current,
        current.length(),
        MutationType.NONE, // Multiple mutations
        descriptions.isEmpty() ? "No mutations applied" : descriptions.toString());
  }
}

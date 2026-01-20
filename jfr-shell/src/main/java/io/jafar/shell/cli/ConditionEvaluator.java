package io.jafar.shell.cli;

import io.jafar.shell.core.VariableStore;

/**
 * Evaluates condition expressions for if/elif statements. Supports comparisons, arithmetic, logical
 * operators, and built-in functions.
 *
 * <p>Grammar:
 *
 * <pre>
 * expr       := orExpr
 * orExpr     := andExpr ('||' andExpr)*
 * andExpr    := notExpr ('&&' notExpr)*
 * notExpr    := '!' notExpr | comparison
 * comparison := addExpr (compOp addExpr)?
 * addExpr    := mulExpr (('+' | '-') mulExpr)*
 * mulExpr    := unary (('*' | '/') unary)*
 * unary      := '-' unary | primary
 * primary    := '(' expr ')' | function | number | string | identifier
 * function   := 'exists' '(' identifier ')' | 'empty' '(' identifier ')'
 * compOp     := '==' | '!=' | '>' | '>=' | '<' | '<='
 * </pre>
 */
public final class ConditionEvaluator {

  private final VariableSubstitutor substitutor;
  private final VariableStore sessionStore;
  private final VariableStore globalStore;

  private String input;
  private int pos;

  /**
   * Creates a condition evaluator.
   *
   * @param sessionStore session variables (may be null)
   * @param globalStore global variables (may be null)
   */
  public ConditionEvaluator(VariableStore sessionStore, VariableStore globalStore) {
    this.sessionStore = sessionStore;
    this.globalStore = globalStore;
    this.substitutor = new VariableSubstitutor(sessionStore, globalStore);
  }

  /**
   * Evaluates a condition expression.
   *
   * @param condition the condition string
   * @return true if condition evaluates to true
   * @throws Exception if evaluation fails
   */
  public boolean evaluate(String condition) throws Exception {
    // First substitute variables
    String substituted = substitutor.substitute(condition);

    // Then parse and evaluate
    this.input = substituted;
    this.pos = 0;

    Object result = parseOrExpr();
    skipWhitespace();

    if (pos < input.length()) {
      throw new IllegalArgumentException(
          "Unexpected characters at position " + pos + ": " + input.substring(pos));
    }

    return toBoolean(result);
  }

  private Object parseOrExpr() throws Exception {
    Object left = parseAndExpr();

    while (match("||") || matchKeyword("or")) {
      Object right = parseAndExpr();
      left = toBoolean(left) || toBoolean(right);
    }

    return left;
  }

  private Object parseAndExpr() throws Exception {
    Object left = parseNotExpr();

    while (match("&&") || matchKeyword("and")) {
      Object right = parseNotExpr();
      left = toBoolean(left) && toBoolean(right);
    }

    return left;
  }

  private Object parseNotExpr() throws Exception {
    if (match("!") || matchKeyword("not")) {
      Object value = parseNotExpr();
      return !toBoolean(value);
    }
    return parseComparison();
  }

  private Object parseComparison() throws Exception {
    Object left = parseAddExpr();

    skipWhitespace();
    if (match("==")) {
      Object right = parseAddExpr();
      return compare(left, right) == 0;
    } else if (match("!=")) {
      Object right = parseAddExpr();
      return compare(left, right) != 0;
    } else if (match(">=")) {
      Object right = parseAddExpr();
      return compare(left, right) >= 0;
    } else if (match("<=")) {
      Object right = parseAddExpr();
      return compare(left, right) <= 0;
    } else if (match(">")) {
      Object right = parseAddExpr();
      return compare(left, right) > 0;
    } else if (match("<")) {
      Object right = parseAddExpr();
      return compare(left, right) < 0;
    } else if (matchKeyword("contains")) {
      Object right = parseAddExpr();
      return containsCheck(left, right);
    }

    return left;
  }

  private Object parseAddExpr() throws Exception {
    Object left = parseMulExpr();

    while (true) {
      skipWhitespace();
      if (match("+")) {
        Object right = parseMulExpr();
        left = toNumber(left) + toNumber(right);
      } else if (matchMinus()) {
        Object right = parseMulExpr();
        left = toNumber(left) - toNumber(right);
      } else {
        break;
      }
    }

    return left;
  }

  private Object parseMulExpr() throws Exception {
    Object left = parseUnary();

    while (true) {
      skipWhitespace();
      if (match("*")) {
        Object right = parseUnary();
        left = toNumber(left) * toNumber(right);
      } else if (match("/")) {
        Object right = parseUnary();
        double divisor = toNumber(right);
        if (divisor == 0) {
          throw new ArithmeticException("Division by zero");
        }
        left = toNumber(left) / divisor;
      } else {
        break;
      }
    }

    return left;
  }

  private Object parseUnary() throws Exception {
    skipWhitespace();
    if (match("-")) {
      Object value = parseUnary();
      return -toNumber(value);
    }
    return parsePrimary();
  }

  private Object parsePrimary() throws Exception {
    skipWhitespace();

    // Parenthesized expression
    if (match("(")) {
      Object result = parseOrExpr();
      if (!match(")")) {
        throw new IllegalArgumentException("Expected ')' at position " + pos);
      }
      return result;
    }

    // String literal
    if (peek() == '"') {
      return parseString();
    }

    // Number
    if (Character.isDigit(peek())
        || (peek() == '-'
            && pos + 1 < input.length()
            && Character.isDigit(input.charAt(pos + 1)))) {
      return parseNumber();
    }

    // Function or identifier
    String ident = parseIdentifier();
    if (ident.isEmpty()) {
      throw new IllegalArgumentException("Expected expression at position " + pos);
    }

    // Check for function call
    skipWhitespace();
    if (match("(")) {
      return evaluateFunction(ident);
    }

    // Check for boolean literals
    if ("true".equalsIgnoreCase(ident)) {
      return true;
    }
    if ("false".equalsIgnoreCase(ident)) {
      return false;
    }

    // Otherwise treat as string value
    return ident;
  }

  private Object evaluateFunction(String name) throws Exception {
    skipWhitespace();
    String arg = parseIdentifier();
    skipWhitespace();
    if (!match(")")) {
      throw new IllegalArgumentException("Expected ')' after function argument at position " + pos);
    }

    return switch (name.toLowerCase()) {
      case "exists" -> variableExists(arg);
      case "empty" -> variableEmpty(arg);
      default -> throw new IllegalArgumentException("Unknown function: " + name);
    };
  }

  private boolean variableExists(String name) {
    if (sessionStore != null && sessionStore.contains(name)) {
      return true;
    }
    return globalStore != null && globalStore.contains(name);
  }

  private boolean variableEmpty(String name) throws Exception {
    if (!variableExists(name)) {
      return true;
    }

    VariableStore.Value val =
        (sessionStore != null && sessionStore.contains(name))
            ? sessionStore.get(name)
            : globalStore.get(name);

    if (val instanceof VariableStore.ScalarValue sv) {
      Object v = sv.value();
      if (v == null) return true;
      if (v instanceof String s) return s.isEmpty();
      return false;
    }

    if (val instanceof VariableStore.LazyQueryValue lqv) {
      return lqv.size() == 0;
    }

    return true;
  }

  private String parseString() {
    if (peek() != '"') {
      return "";
    }
    pos++; // skip opening quote

    StringBuilder sb = new StringBuilder();
    while (pos < input.length() && peek() != '"') {
      if (peek() == '\\' && pos + 1 < input.length()) {
        pos++;
        char escaped = input.charAt(pos++);
        sb.append(
            switch (escaped) {
              case 'n' -> '\n';
              case 't' -> '\t';
              case 'r' -> '\r';
              case '"' -> '"';
              case '\\' -> '\\';
              default -> escaped;
            });
      } else {
        sb.append(input.charAt(pos++));
      }
    }

    if (peek() == '"') {
      pos++; // skip closing quote
    }

    return sb.toString();
  }

  private double parseNumber() {
    int start = pos;
    if (peek() == '-') {
      pos++;
    }
    while (pos < input.length() && (Character.isDigit(peek()) || peek() == '.')) {
      pos++;
    }
    String numStr = input.substring(start, pos);
    return Double.parseDouble(numStr);
  }

  private String parseIdentifier() {
    skipWhitespace();
    int start = pos;
    while (pos < input.length()
        && (Character.isLetterOrDigit(peek()) || peek() == '_' || peek() == '.')) {
      pos++;
    }
    return input.substring(start, pos);
  }

  private void skipWhitespace() {
    while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
      pos++;
    }
  }

  private char peek() {
    return pos < input.length() ? input.charAt(pos) : '\0';
  }

  private boolean match(String expected) {
    skipWhitespace();
    if (input.regionMatches(pos, expected, 0, expected.length())) {
      pos += expected.length();
      return true;
    }
    return false;
  }

  // Special handling for minus to avoid conflict with negative numbers
  private boolean matchMinus() {
    skipWhitespace();
    if (pos < input.length() && input.charAt(pos) == '-') {
      // Check if this is subtraction (preceded by value) not negative number
      if (pos + 1 < input.length() && !Character.isDigit(input.charAt(pos + 1))) {
        pos++;
        return true;
      }
      // Also match if followed by whitespace
      if (pos + 1 < input.length() && Character.isWhitespace(input.charAt(pos + 1))) {
        pos++;
        return true;
      }
    }
    return false;
  }

  /**
   * Matches a keyword operator ensuring word boundaries. This prevents matching 'or' inside 'fork'
   * or 'and' inside 'band'.
   *
   * @param keyword the keyword to match (case-insensitive)
   * @return true if keyword matched at current position with proper word boundaries
   */
  private boolean matchKeyword(String keyword) {
    skipWhitespace();
    int keywordLen = keyword.length();

    if (pos + keywordLen > input.length()) {
      return false;
    }

    // Case-insensitive match
    if (!input.regionMatches(true, pos, keyword, 0, keywordLen)) {
      return false;
    }

    // Ensure word boundary - not part of a larger identifier
    if (pos + keywordLen < input.length()) {
      char nextChar = input.charAt(pos + keywordLen);
      if (Character.isLetterOrDigit(nextChar) || nextChar == '_') {
        return false;
      }
    }

    pos += keywordLen;
    return true;
  }

  /**
   * Checks if left contains right (as strings).
   *
   * @param left the value to search in
   * @param right the value to search for
   * @return true if left contains right
   */
  private boolean containsCheck(Object left, Object right) {
    if (left == null || right == null) {
      return false;
    }
    String leftStr = String.valueOf(left);
    String rightStr = String.valueOf(right);
    return leftStr.contains(rightStr);
  }

  private boolean toBoolean(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Number n) {
      return n.doubleValue() != 0;
    }
    if (value instanceof String s) {
      return !s.isEmpty() && !"false".equalsIgnoreCase(s) && !"0".equals(s);
    }
    return value != null;
  }

  private double toNumber(Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value instanceof String s) {
      try {
        return Double.parseDouble(s);
      } catch (NumberFormatException e) {
        return s.isEmpty() ? 0 : 1;
      }
    }
    if (value instanceof Boolean b) {
      return b ? 1 : 0;
    }
    return 0;
  }

  private int compare(Object left, Object right) {
    // If both are numbers, compare numerically
    if ((left instanceof Number || isNumericString(left))
        && (right instanceof Number || isNumericString(right))) {
      double l = toNumber(left);
      double r = toNumber(right);
      return Double.compare(l, r);
    }

    // Otherwise compare as strings
    String l = String.valueOf(left);
    String r = String.valueOf(right);
    return l.compareTo(r);
  }

  private boolean isNumericString(Object value) {
    if (!(value instanceof String s)) {
      return false;
    }
    try {
      Double.parseDouble(s);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}

package io.jafar.shell.cli;

import io.jafar.shell.core.LazyQueryValue;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.llm.LlmSettings;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;

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
 * function   := 'exists' '(' reference ')' | 'empty' '(' reference ')'
 * reference  := identifier ('[' digits ']')? ('.' identifier)* | llm-setting-name
 * compOp     := '==' | '!=' | '>' | '>=' | '<' | '<='
 * </pre>
 */
public final class ConditionEvaluator {

  /** Resolves names and references with the same rules as {@code ${...}} substitution. */
  private final VariableSubstitutor substitutor;

  private String input;
  private int pos;

  /**
   * Creates a condition evaluator.
   *
   * @param sessionStore session variables (may be null)
   * @param globalStore global variables (may be null)
   */
  public ConditionEvaluator(VariableStore sessionStore, VariableStore globalStore) {
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
    String arg = parseReferenceToken();
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

  /**
   * Reads an {@code exists()}/{@code empty()} argument: a reference such as {@code res[2].a.b},
   * or an {@code llm.*} setting name, which may contain '-' ({@code llm.base-url}). The token is
   * validated when it is resolved, not here.
   */
  private String parseReferenceToken() {
    skipWhitespace();
    int start = pos;
    while (pos < input.length()) {
      char c = input.charAt(pos);
      if (Character.isLetterOrDigit(c)
          || c == '_'
          || c == '.'
          || c == '-'
          || c == '['
          || c == ']') {
        pos++;
      } else {
        break;
      }
    }
    return input.substring(start, pos);
  }

  /**
   * {@code exists(ref)}: true when {@code ref} names a variable or a set {@code llm.*} setting, or
   * is a reference ({@code name[idx].field.path}) that resolves to a non-null value under the
   * rules {@code ${ref}} uses.
   */
  private boolean variableExists(String arg) throws Exception {
    String settingName = settingName(arg);
    if (substitutor.resolve(settingName != null ? settingName : arg) != null) {
      return true;
    }
    Matcher ref = settingName == null ? reference(arg) : null;
    return ref != null && resolveReference(ref) != null;
  }

  /**
   * {@code empty(ref)}: true when {@code ref} does not exist, or what it denotes is null, an empty
   * string, an empty map or collection, or a query result with no rows.
   */
  private boolean variableEmpty(String arg) throws Exception {
    // A whole-name match wins, so names stored as-is (llm.* settings) resolve to themselves
    // rather than being split into variable + field.
    String settingName = settingName(arg);
    VariableStore.Value val = substitutor.resolve(settingName != null ? settingName : arg);
    if (val != null) {
      if (val instanceof LazyQueryValue lqv) {
        return lqv.size() == 0;
      }
      if (val instanceof VariableStore.ScalarValue || val instanceof VariableStore.MapValue) {
        return isEmptyValue(val.get());
      }
      return true;
    }
    Matcher ref = settingName == null ? reference(arg) : null;
    return ref == null || isEmptyValue(resolveReference(ref));
  }

  /**
   * The canonical name ({@code set} stores settings lower-cased) when {@code arg} is an {@code
   * llm.*} setting, else null. A setting is a name, never a variable plus a field, even when unset.
   * Every setting name contains a dot, so plain names skip the lookup.
   */
  private static String settingName(String arg) {
    if (arg.indexOf('.') < 0) {
      return null;
    }
    return LlmSettings.lookup(arg).map(LlmSettings.Setting::name).orElse(null);
  }

  /**
   * Parses {@code arg} as a {@code ${...}} reference when it carries an index or a field path.
   * Returns null for a plain name, which is looked up as a whole only, as before. A token with
   * reference syntax that {@code ${...}} would not accept ({@code m..a}, {@code m.}, {@code r[x]},
   * {@code a-b}) is rejected rather than read as a missing variable, which would make {@code
   * empty()} silently true.
   */
  private static Matcher reference(String arg) {
    if (arg.indexOf('.') < 0
        && arg.indexOf('[') < 0
        && arg.indexOf(']') < 0
        && arg.indexOf('-') < 0) {
      return null;
    }
    Matcher m = VariableSubstitutor.REFERENCE_PATTERN.matcher(arg);
    if (!m.matches()) {
      throw new IllegalArgumentException("Invalid variable reference: " + arg);
    }
    return m;
  }

  /**
   * Resolves a parsed reference exactly as {@code ${...}} would, returning the unformatted value,
   * or null when the base variable, the row or the field is missing.
   */
  private Object resolveReference(Matcher ref) throws Exception {
    VariableStore.Value base = substitutor.resolve(ref.group(1));
    if (base == null) {
      return null;
    }
    String path = ref.group(3);
    return substitutor.extractRaw(base, ref.group(2), path == null ? null : path.substring(1));
  }

  private static boolean isEmptyValue(Object v) {
    if (v == null) return true;
    if (v instanceof CharSequence cs) return cs.length() == 0;
    if (v instanceof Map<?, ?> m) return m.isEmpty();
    if (v instanceof Collection<?> c) return c.isEmpty();
    if (v.getClass().isArray()) return Array.getLength(v) == 0;
    return false;
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

    // Ensure word boundary before keyword - not part of a larger identifier
    if (pos > 0) {
      char prevChar = input.charAt(pos - 1);
      if (Character.isLetterOrDigit(prevChar) || prevChar == '_') {
        return false;
      }
    }

    // Ensure word boundary after keyword - not part of a larger identifier
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

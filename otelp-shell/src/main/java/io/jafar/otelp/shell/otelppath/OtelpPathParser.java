package io.jafar.otelp.shell.otelppath;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent parser for the OtelpPath query language.
 *
 * <p>Grammar (simplified):
 *
 * <pre>
 * query      := root ('[' predicates ']')? ('|' pipeline_op)*
 * root       := 'samples'
 * predicates := predicate (('and'|'or') predicate)*
 * predicate  := field op literal
 * op         := '=' | '==' | '!=' | '>' | '>=' | '<' | '<=' | '~'
 * literal    := string | number
 * pipeline   := op_name '(' args ')'
 * </pre>
 */
public final class OtelpPathParser {

  private final String input;
  private int pos;

  private OtelpPathParser(String input) {
    this.input = input;
    this.pos = 0;
  }

  /**
   * Parses an OtelpPath query string.
   *
   * @param input the query string
   * @return parsed {@link OtelpPath.Query}
   * @throws OtelpPathParseException if the query is syntactically invalid
   */
  public static OtelpPath.Query parse(String input) {
    if (input == null || input.isBlank()) {
      throw new OtelpPathParseException("Empty query");
    }
    return new OtelpPathParser(input.trim()).parseQuery();
  }

  private OtelpPath.Query parseQuery() {
    skipWs();
    OtelpPath.Root root = parseRoot();
    skipWs();

    List<OtelpPath.Predicate> predicates = new ArrayList<>();
    if (!isAtEnd() && peek() == '[') {
      advance(); // consume '['
      predicates = parsePredicates();
      expect(']');
      skipWs();
    }

    List<OtelpPath.PipelineOp> pipeline = new ArrayList<>();
    while (!isAtEnd() && peek() == '|') {
      advance(); // consume '|'
      skipWs();
      pipeline.add(parsePipelineOp());
      skipWs();
    }

    if (!isAtEnd()) {
      throw new OtelpPathParseException(
          "Unexpected input at position " + pos + ": '" + remaining() + "'");
    }

    return new OtelpPath.Query(root, predicates, pipeline);
  }

  private OtelpPath.Root parseRoot() {
    if (matchKeyword("samples")) {
      return OtelpPath.Root.SAMPLES;
    }
    throw new OtelpPathParseException(
        "Expected 'samples' at position " + pos + ", got: '" + remaining() + "'");
  }

  private List<OtelpPath.Predicate> parsePredicates() {
    skipWs();
    List<OtelpPath.Predicate> predicates = new ArrayList<>();
    predicates.add(parsePredicate());
    skipWs();

    while (!isAtEnd() && peek() != ']') {
      boolean isAnd;
      if (matchKeyword("and") || matchKeyword("&&")) {
        isAnd = true;
      } else if (matchKeyword("or") || matchKeyword("||")) {
        isAnd = false;
      } else {
        break;
      }
      skipWs();
      OtelpPath.Predicate right = parsePredicate();
      OtelpPath.Predicate combined =
          new OtelpPath.LogicalPredicate(predicates.remove(predicates.size() - 1), isAnd, right);
      predicates.add(combined);
      skipWs();
    }
    return predicates;
  }

  private OtelpPath.Predicate parsePredicate() {
    skipWs();
    String field = parseIdentifierOrPath();
    skipWs();
    OtelpPath.Op op = parseOp();
    skipWs();
    Object literal = parseLiteral();
    return new OtelpPath.FieldPredicate(field, op, literal);
  }

  private String parseIdentifierOrPath() {
    StringBuilder sb = new StringBuilder();
    sb.append(parseIdentifier());
    while (!isAtEnd() && peek() == '/') {
      sb.append('/');
      advance();
      sb.append(parseIdentifier());
    }
    return sb.toString();
  }

  private OtelpPath.Op parseOp() {
    if (pos + 1 < input.length()) {
      String two = input.substring(pos, pos + 2);
      OtelpPath.Op op =
          switch (two) {
            case "==" -> OtelpPath.Op.EQ;
            case "!=" -> OtelpPath.Op.NE;
            case ">=" -> OtelpPath.Op.GE;
            case "<=" -> OtelpPath.Op.LE;
            default -> null;
          };
      if (op != null) {
        pos += 2;
        return op;
      }
    }
    char c = peek();
    advance();
    return switch (c) {
      case '=' -> OtelpPath.Op.EQ;
      case '>' -> OtelpPath.Op.GT;
      case '<' -> OtelpPath.Op.LT;
      case '~' -> OtelpPath.Op.REGEX;
      default -> throw new OtelpPathParseException("Expected operator at position " + (pos - 1));
    };
  }

  private Object parseLiteral() {
    char c = peek();
    if (c == '\'' || c == '"') {
      return parseString(c);
    }
    if (c == '-' || Character.isDigit(c)) {
      return parseNumber();
    }
    // Try to parse as bare word (unquoted string)
    return parseIdentifier();
  }

  private String parseString(char quote) {
    advance(); // consume opening quote
    StringBuilder sb = new StringBuilder();
    while (!isAtEnd() && peek() != quote) {
      char c = peek();
      if (c == '\\' && pos + 1 < input.length()) {
        advance();
        sb.append(peek());
      } else {
        sb.append(c);
      }
      advance();
    }
    if (!isAtEnd()) advance(); // consume closing quote
    return sb.toString();
  }

  private Number parseNumber() {
    int start = pos;
    if (!isAtEnd() && peek() == '-') advance();
    while (!isAtEnd() && (Character.isDigit(peek()) || peek() == '.')) advance();
    String numStr = input.substring(start, pos);
    try {
      if (numStr.contains(".")) return Double.parseDouble(numStr);
      return Long.parseLong(numStr);
    } catch (NumberFormatException e) {
      throw new OtelpPathParseException("Invalid number: " + numStr);
    }
  }

  private OtelpPath.PipelineOp parsePipelineOp() {
    String name = parseIdentifier().toLowerCase();
    skipWs();
    return switch (name) {
      case "count" -> {
        consumeEmptyParens();
        yield new OtelpPath.CountOp();
      }
      case "top" -> parseTopOp();
      case "groupby", "group" -> parseGroupByOp();
      case "stats" -> parseStatsOp();
      case "head" -> new OtelpPath.HeadOp(parseSingleIntArg());
      case "tail" -> new OtelpPath.TailOp(parseSingleIntArg());
      case "filter", "where" -> parseFilterOp();
      case "select" -> parseSelectOp();
      case "sortby", "sort", "orderby" -> parseSortByOp();
      case "stackprofile" -> parseStackProfileOp();
      case "distinct", "unique" -> new OtelpPath.DistinctOp(parseSingleStringArg());
      default -> throw new OtelpPathParseException("Unknown pipeline operator: " + name);
    };
  }

  private OtelpPath.TopOp parseTopOp() {
    expect('(');
    skipWs();
    int n = (int) parseLongArg();
    skipWs();
    String byField = null;
    boolean ascending = false;
    if (!isAtEnd() && peek() == ',') {
      advance(); // consume ','
      skipWs();
      byField = parseIdentifierOrPath();
      skipWs();
      if (!isAtEnd() && peek() == ',') {
        advance();
        skipWs();
        String dir = parseIdentifier().toLowerCase();
        ascending = dir.equals("asc");
      }
    }
    skipWs();
    expect(')');
    return new OtelpPath.TopOp(n, byField, ascending);
  }

  private OtelpPath.GroupByOp parseGroupByOp() {
    expect('(');
    skipWs();
    String keyField = parseIdentifierOrPath();
    skipWs();
    String aggFunc = "count";
    String valueField = null;
    if (!isAtEnd() && peek() == ',') {
      advance(); // consume ','
      skipWs();
      String agg = parseIdentifier().toLowerCase();
      skipWs();
      if (!isAtEnd() && peek() == '(') {
        advance(); // consume '('
        skipWs();
        valueField = parseIdentifierOrPath();
        skipWs();
        expect(')');
        aggFunc = agg;
      } else {
        aggFunc = agg;
      }
    }
    skipWs();
    expect(')');
    return new OtelpPath.GroupByOp(keyField, aggFunc, valueField);
  }

  private OtelpPath.StatsOp parseStatsOp() {
    expect('(');
    skipWs();
    String field = parseIdentifierOrPath();
    skipWs();
    expect(')');
    return new OtelpPath.StatsOp(field);
  }

  private OtelpPath.FilterOp parseFilterOp() {
    expect('(');
    skipWs();
    List<OtelpPath.Predicate> predicates = parsePredicatesInParens();
    skipWs();
    expect(')');
    return new OtelpPath.FilterOp(predicates);
  }

  private List<OtelpPath.Predicate> parsePredicatesInParens() {
    List<OtelpPath.Predicate> predicates = new ArrayList<>();
    predicates.add(parsePredicate());
    skipWs();
    while (!isAtEnd() && peek() != ')') {
      boolean isAnd;
      if (matchKeyword("and") || matchKeyword("&&")) {
        isAnd = true;
      } else if (matchKeyword("or") || matchKeyword("||")) {
        isAnd = false;
      } else {
        break;
      }
      skipWs();
      OtelpPath.Predicate right = parsePredicate();
      OtelpPath.Predicate combined =
          new OtelpPath.LogicalPredicate(predicates.remove(predicates.size() - 1), isAnd, right);
      predicates.add(combined);
      skipWs();
    }
    return predicates;
  }

  private OtelpPath.SelectOp parseSelectOp() {
    expect('(');
    skipWs();
    List<String> fields = new ArrayList<>();
    fields.add(parseIdentifierOrPath());
    skipWs();
    while (!isAtEnd() && peek() == ',') {
      advance(); // consume ','
      skipWs();
      fields.add(parseIdentifierOrPath());
      skipWs();
    }
    expect(')');
    return new OtelpPath.SelectOp(fields);
  }

  private OtelpPath.SortByOp parseSortByOp() {
    expect('(');
    skipWs();
    String field = parseIdentifierOrPath();
    skipWs();
    boolean ascending = false;
    if (!isAtEnd() && peek() == ',') {
      advance();
      skipWs();
      String dir = parseIdentifier().toLowerCase();
      ascending = dir.equals("asc");
    }
    skipWs();
    expect(')');
    return new OtelpPath.SortByOp(field, ascending);
  }

  private OtelpPath.StackProfileOp parseStackProfileOp() {
    if (isAtEnd() || peek() != '(') {
      return new OtelpPath.StackProfileOp(null);
    }
    advance(); // consume '('
    skipWs();
    String valueField = null;
    if (!isAtEnd() && peek() != ')') {
      valueField = parseIdentifierOrPath();
      skipWs();
    }
    expect(')');
    return new OtelpPath.StackProfileOp(valueField);
  }

  // ---- Helpers ----

  private int parseSingleIntArg() {
    expect('(');
    skipWs();
    long n = parseLongArg();
    skipWs();
    expect(')');
    return (int) n;
  }

  private String parseSingleStringArg() {
    expect('(');
    skipWs();
    String s = parseIdentifierOrPath();
    skipWs();
    expect(')');
    return s;
  }

  private long parseLongArg() {
    int start = pos;
    if (!isAtEnd() && peek() == '-') advance();
    while (!isAtEnd() && Character.isDigit(peek())) advance();
    try {
      return Long.parseLong(input.substring(start, pos));
    } catch (NumberFormatException e) {
      throw new OtelpPathParseException("Expected integer at position " + start);
    }
  }

  private void consumeEmptyParens() {
    if (isAtEnd()) return;
    skipWs();
    if (peek() == '(') {
      advance();
      skipWs();
      expect(')');
    }
  }

  private String parseIdentifier() {
    skipWs();
    int start = pos;
    while (!isAtEnd() && isIdentChar(peek())) advance();
    if (pos == start) {
      throw new OtelpPathParseException(
          "Expected identifier at position " + pos + ", got: '" + remaining() + "'");
    }
    return input.substring(start, pos);
  }

  private boolean isIdentChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
  }

  private boolean matchKeyword(String kw) {
    if (input.startsWith(kw, pos)) {
      int end = pos + kw.length();
      if (end >= input.length() || !isIdentChar(input.charAt(end))) {
        pos = end;
        skipWs();
        return true;
      }
    }
    return false;
  }

  private void expect(char c) {
    if (isAtEnd() || peek() != c) {
      throw new OtelpPathParseException(
          "Expected '"
              + c
              + "' at position "
              + pos
              + ", got: '"
              + (isAtEnd() ? "<end>" : String.valueOf(peek()))
              + "'");
    }
    advance();
  }

  private void skipWs() {
    while (!isAtEnd() && Character.isWhitespace(peek())) advance();
  }

  private char peek() {
    return input.charAt(pos);
  }

  private void advance() {
    pos++;
  }

  private boolean isAtEnd() {
    return pos >= input.length();
  }

  private String remaining() {
    return pos < input.length() ? input.substring(pos) : "<end>";
  }
}

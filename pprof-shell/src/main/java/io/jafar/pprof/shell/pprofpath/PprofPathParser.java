package io.jafar.pprof.shell.pprofpath;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent parser for the pprof query language.
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
public final class PprofPathParser {

  private final String input;
  private int pos;

  private PprofPathParser(String input) {
    this.input = input;
    this.pos = 0;
  }

  /**
   * Parses a pprof query string.
   *
   * @param input the query string
   * @return parsed {@link PprofPath.Query}
   * @throws PprofPathParseException if the query is syntactically invalid
   */
  public static PprofPath.Query parse(String input) {
    if (input == null || input.isBlank()) {
      throw new PprofPathParseException("Empty query");
    }
    return new PprofPathParser(input.trim()).parseQuery();
  }

  private PprofPath.Query parseQuery() {
    skipWs();
    PprofPath.Root root = parseRoot();
    skipWs();

    List<PprofPath.Predicate> predicates = new ArrayList<>();
    if (!isAtEnd() && peek() == '[') {
      advance(); // consume '['
      predicates = parsePredicates();
      expect(']');
      skipWs();
    }

    List<PprofPath.PipelineOp> pipeline = new ArrayList<>();
    while (!isAtEnd() && peek() == '|') {
      advance(); // consume '|'
      skipWs();
      pipeline.add(parsePipelineOp());
      skipWs();
    }

    if (!isAtEnd()) {
      throw new PprofPathParseException(
          "Unexpected input at position " + pos + ": '" + remaining() + "'");
    }

    return new PprofPath.Query(root, predicates, pipeline);
  }

  private PprofPath.Root parseRoot() {
    if (matchKeyword("samples")) {
      return PprofPath.Root.SAMPLES;
    }
    throw new PprofPathParseException(
        "Expected 'samples' at position " + pos + ", got: '" + remaining() + "'");
  }

  private List<PprofPath.Predicate> parsePredicates() {
    skipWs();
    List<PprofPath.Predicate> predicates = new ArrayList<>();
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
      PprofPath.Predicate right = parsePredicate();
      PprofPath.Predicate combined =
          new PprofPath.LogicalPredicate(predicates.remove(predicates.size() - 1), isAnd, right);
      predicates.add(combined);
      skipWs();
    }
    return predicates;
  }

  private PprofPath.Predicate parsePredicate() {
    skipWs();
    String field = parseIdentifierOrPath();
    skipWs();
    PprofPath.Op op = parseOp();
    skipWs();
    Object literal = parseLiteral();
    return new PprofPath.FieldPredicate(field, op, literal);
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

  private PprofPath.Op parseOp() {
    if (pos + 1 < input.length()) {
      String two = input.substring(pos, pos + 2);
      PprofPath.Op op =
          switch (two) {
            case "==" -> PprofPath.Op.EQ;
            case "!=" -> PprofPath.Op.NE;
            case ">=" -> PprofPath.Op.GE;
            case "<=" -> PprofPath.Op.LE;
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
      case '=' -> PprofPath.Op.EQ;
      case '>' -> PprofPath.Op.GT;
      case '<' -> PprofPath.Op.LT;
      case '~' -> PprofPath.Op.REGEX;
      default -> throw new PprofPathParseException("Expected operator at position " + (pos - 1));
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
      throw new PprofPathParseException("Invalid number: " + numStr);
    }
  }

  private PprofPath.PipelineOp parsePipelineOp() {
    String name = parseIdentifier().toLowerCase();
    skipWs();
    return switch (name) {
      case "count" -> {
        consumeEmptyParens();
        yield new PprofPath.CountOp();
      }
      case "top" -> parseTopOp();
      case "groupby", "group" -> parseGroupByOp();
      case "stats" -> parseStatsOp();
      case "head" -> new PprofPath.HeadOp(parseSingleIntArg());
      case "tail" -> new PprofPath.TailOp(parseSingleIntArg());
      case "filter", "where" -> parseFilterOp();
      case "select" -> parseSelectOp();
      case "sortby", "sort", "orderby" -> parseSortByOp();
      case "stackprofile" -> parseStackProfileOp();
      case "distinct", "unique" -> new PprofPath.DistinctOp(parseSingleStringArg());
      default -> throw new PprofPathParseException("Unknown pipeline operator: " + name);
    };
  }

  private PprofPath.TopOp parseTopOp() {
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
    return new PprofPath.TopOp(n, byField, ascending);
  }

  private PprofPath.GroupByOp parseGroupByOp() {
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
    return new PprofPath.GroupByOp(keyField, aggFunc, valueField);
  }

  private PprofPath.StatsOp parseStatsOp() {
    expect('(');
    skipWs();
    String field = parseIdentifierOrPath();
    skipWs();
    expect(')');
    return new PprofPath.StatsOp(field);
  }

  private PprofPath.FilterOp parseFilterOp() {
    expect('(');
    skipWs();
    List<PprofPath.Predicate> predicates = parsePredicatesInParens();
    skipWs();
    expect(')');
    return new PprofPath.FilterOp(predicates);
  }

  private List<PprofPath.Predicate> parsePredicatesInParens() {
    List<PprofPath.Predicate> predicates = new ArrayList<>();
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
      PprofPath.Predicate right = parsePredicate();
      PprofPath.Predicate combined =
          new PprofPath.LogicalPredicate(predicates.remove(predicates.size() - 1), isAnd, right);
      predicates.add(combined);
      skipWs();
    }
    return predicates;
  }

  private PprofPath.SelectOp parseSelectOp() {
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
    return new PprofPath.SelectOp(fields);
  }

  private PprofPath.SortByOp parseSortByOp() {
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
    return new PprofPath.SortByOp(field, ascending);
  }

  private PprofPath.StackProfileOp parseStackProfileOp() {
    if (isAtEnd() || peek() != '(') {
      return new PprofPath.StackProfileOp(null);
    }
    advance(); // consume '('
    skipWs();
    String valueField = null;
    if (!isAtEnd() && peek() != ')') {
      valueField = parseIdentifierOrPath();
      skipWs();
    }
    expect(')');
    return new PprofPath.StackProfileOp(valueField);
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
      throw new PprofPathParseException("Expected integer at position " + start);
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
      throw new PprofPathParseException(
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
      throw new PprofPathParseException(
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

package io.jafar.shell.core.sampling.path;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent parser for the samples query language shared by pprof and OTLP profiling
 * shells.
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
public final class SamplesPathParser {

  private final String input;
  private int pos;

  private SamplesPathParser(String input) {
    this.input = input;
    this.pos = 0;
  }

  /**
   * Parses a samples query string.
   *
   * @param input the query string
   * @return parsed {@link SamplesPath.Query}
   * @throws SamplesPathParseException if the query is syntactically invalid
   */
  public static SamplesPath.Query parse(String input) {
    if (input == null || input.isBlank()) {
      throw new SamplesPathParseException("Empty query");
    }
    return new SamplesPathParser(input.trim()).parseQuery();
  }

  private SamplesPath.Query parseQuery() {
    skipWs();
    SamplesPath.Root root = parseRoot();
    skipWs();

    List<SamplesPath.Predicate> predicates = new ArrayList<>();
    if (!isAtEnd() && peek() == '[') {
      advance(); // consume '['
      predicates = parsePredicates();
      expect(']');
      skipWs();
    }

    List<SamplesPath.PipelineOp> pipeline = new ArrayList<>();
    while (!isAtEnd() && peek() == '|') {
      advance(); // consume '|'
      skipWs();
      pipeline.add(parsePipelineOp());
      skipWs();
    }

    if (!isAtEnd()) {
      throw new SamplesPathParseException(
          "Unexpected input at position " + pos + ": '" + remaining() + "'");
    }

    return new SamplesPath.Query(root, predicates, pipeline);
  }

  private SamplesPath.Root parseRoot() {
    if (matchKeyword("samples")) {
      return SamplesPath.Root.SAMPLES;
    }
    throw new SamplesPathParseException(
        "Expected 'samples' at position " + pos + ", got: '" + remaining() + "'");
  }

  private List<SamplesPath.Predicate> parsePredicates() {
    skipWs();
    List<SamplesPath.Predicate> predicates = new ArrayList<>();
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
      SamplesPath.Predicate right = parsePredicate();
      SamplesPath.Predicate combined =
          new SamplesPath.LogicalPredicate(predicates.remove(predicates.size() - 1), isAnd, right);
      predicates.add(combined);
      skipWs();
    }
    return predicates;
  }

  private SamplesPath.Predicate parsePredicate() {
    skipWs();
    String field = parseIdentifierOrPath();
    skipWs();
    SamplesPath.Op op = parseOp();
    skipWs();
    Object literal = parseLiteral();
    return new SamplesPath.FieldPredicate(field, op, literal);
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

  private SamplesPath.Op parseOp() {
    if (pos + 1 < input.length()) {
      String two = input.substring(pos, pos + 2);
      SamplesPath.Op op =
          switch (two) {
            case "==" -> SamplesPath.Op.EQ;
            case "!=" -> SamplesPath.Op.NE;
            case ">=" -> SamplesPath.Op.GE;
            case "<=" -> SamplesPath.Op.LE;
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
      case '=' -> SamplesPath.Op.EQ;
      case '>' -> SamplesPath.Op.GT;
      case '<' -> SamplesPath.Op.LT;
      case '~' -> SamplesPath.Op.REGEX;
      default -> throw new SamplesPathParseException("Expected operator at position " + (pos - 1));
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
      throw new SamplesPathParseException("Invalid number: " + numStr);
    }
  }

  private SamplesPath.PipelineOp parsePipelineOp() {
    String name = parseIdentifier().toLowerCase();
    skipWs();
    return switch (name) {
      case "count" -> {
        consumeEmptyParens();
        yield new SamplesPath.CountOp();
      }
      case "top" -> parseTopOp();
      case "groupby", "group" -> parseGroupByOp();
      case "stats" -> parseStatsOp();
      case "head" -> new SamplesPath.HeadOp(parseSingleIntArg());
      case "tail" -> new SamplesPath.TailOp(parseSingleIntArg());
      case "filter", "where" -> parseFilterOp();
      case "select" -> parseSelectOp();
      case "sortby", "sort", "orderby" -> parseSortByOp();
      case "stackprofile" -> parseStackProfileOp();
      case "distinct", "unique" -> new SamplesPath.DistinctOp(parseSingleStringArg());
      default -> throw new SamplesPathParseException("Unknown pipeline operator: " + name);
    };
  }

  private SamplesPath.TopOp parseTopOp() {
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
    return new SamplesPath.TopOp(n, byField, ascending);
  }

  private SamplesPath.GroupByOp parseGroupByOp() {
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
    return new SamplesPath.GroupByOp(keyField, aggFunc, valueField);
  }

  private SamplesPath.StatsOp parseStatsOp() {
    expect('(');
    skipWs();
    String field = parseIdentifierOrPath();
    skipWs();
    expect(')');
    return new SamplesPath.StatsOp(field);
  }

  private SamplesPath.FilterOp parseFilterOp() {
    expect('(');
    skipWs();
    List<SamplesPath.Predicate> predicates = parsePredicatesInParens();
    skipWs();
    expect(')');
    return new SamplesPath.FilterOp(predicates);
  }

  private List<SamplesPath.Predicate> parsePredicatesInParens() {
    List<SamplesPath.Predicate> predicates = new ArrayList<>();
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
      SamplesPath.Predicate right = parsePredicate();
      SamplesPath.Predicate combined =
          new SamplesPath.LogicalPredicate(predicates.remove(predicates.size() - 1), isAnd, right);
      predicates.add(combined);
      skipWs();
    }
    return predicates;
  }

  private SamplesPath.SelectOp parseSelectOp() {
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
    return new SamplesPath.SelectOp(fields);
  }

  private SamplesPath.SortByOp parseSortByOp() {
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
    return new SamplesPath.SortByOp(field, ascending);
  }

  private SamplesPath.StackProfileOp parseStackProfileOp() {
    if (isAtEnd() || peek() != '(') {
      return new SamplesPath.StackProfileOp(null);
    }
    advance(); // consume '('
    skipWs();
    String valueField = null;
    if (!isAtEnd() && peek() != ')') {
      valueField = parseIdentifierOrPath();
      skipWs();
    }
    expect(')');
    return new SamplesPath.StackProfileOp(valueField);
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
      throw new SamplesPathParseException("Expected integer at position " + start);
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
      throw new SamplesPathParseException(
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
      throw new SamplesPathParseException(
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

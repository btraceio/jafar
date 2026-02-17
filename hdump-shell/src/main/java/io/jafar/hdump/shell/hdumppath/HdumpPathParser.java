package io.jafar.hdump.shell.hdumppath;

import io.jafar.shell.core.expr.BinaryExpr;
import io.jafar.shell.core.expr.FieldRef;
import io.jafar.shell.core.expr.NumberLiteral;
import io.jafar.shell.core.expr.ValueExpr;
import io.jafar.shell.core.expr.ValueExpr.ArithOp;
import io.jafar.hdump.shell.hdumppath.HdumpPath.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent parser for HdumpPath query language.
 *
 * <p>Grammar (simplified):
 *
 * <pre>
 * query      := root ('/' type_spec)? ('[' predicates ']')? ('|' pipeline)*
 * root       := 'objects' | 'classes' | 'gcroots'
 * type_spec  := ('instanceof' '/')? class_pattern
 * predicates := predicate (('and'|'or') predicate)*
 * predicate  := field_path op literal | 'not' predicate | '(' predicates ')'
 * pipeline   := op_name ('(' args ')')?
 * </pre>
 */
public final class HdumpPathParser {

  private final String input;
  private int pos;

  private HdumpPathParser(String input) {
    this.input = input;
    this.pos = 0;
  }

  /**
   * Parses an HdumpPath query string.
   *
   * @param input the query string
   * @return parsed Query object
   * @throws HdumpPathParseException if parsing fails
   */
  public static Query parse(String input) {
    if (input == null || input.isBlank()) {
      throw new HdumpPathParseException("Empty query");
    }
    return new HdumpPathParser(input.trim()).parseQuery();
  }

  private Query parseQuery() {
    skipWs();

    // Special case: checkLeaks(...) without root defaults to "objects |"
    if (matchKeyword("checkLeaks")) {
      return parseCheckLeaksShorthand();
    }

    // Parse root
    Root root = parseRoot();
    skipWs();

    // Parse optional type specification
    String typePattern = null;
    boolean instanceof_ = false;

    if (peek() == '/') {
      advance(); // consume '/'
      skipWs();

      // Check for 'instanceof' keyword
      if (matchKeyword("instanceof")) {
        instanceof_ = true;
        skipWs();
        if (peek() == '/') {
          advance();
          skipWs();
        }
      }

      // Parse class/type pattern.
      // Allow '[' only when it starts a JVM array descriptor (e.g. [Ljava.lang.Object; or [I).
      if (peek() != '|' && !isAtEnd() && (peek() != '[' || isArrayDescriptorAhead())) {
        typePattern = parseTypePattern();
      }
    }

    skipWs();

    // Parse optional predicates
    List<Predicate> predicates = new ArrayList<>();
    if (peek() == '[') {
      advance(); // consume '['
      predicates = parsePredicates();
      expect(']');
    }

    skipWs();

    // Parse pipeline operations
    List<PipelineOp> pipeline = new ArrayList<>();
    while (peek() == '|') {
      advance(); // consume '|'
      skipWs();
      pipeline.add(parsePipelineOp());
      skipWs();
    }

    if (!isAtEnd()) {
      throw new HdumpPathParseException("Unexpected input at position " + pos + ": " + remaining());
    }

    return new Query(root, typePattern, instanceof_, predicates, pipeline);
  }

  private Root parseRoot() {
    if (matchKeyword("objects")) {
      return Root.OBJECTS;
    } else if (matchKeyword("classes")) {
      return Root.CLASSES;
    } else if (matchKeyword("gcroots")) {
      return Root.GCROOTS;
    } else {
      throw new HdumpPathParseException(
          "Expected 'objects', 'classes', or 'gcroots' at position " + pos);
    }
  }

  private String parseTypePattern() {
    StringBuilder sb = new StringBuilder();
    if (peek() == '[') {
      // JVM array descriptor: [I, [Ljava.lang.Object;, [[I, etc.
      parseArrayDescriptor(sb);
    } else {
      // Regular class name: read until '[', '|', or whitespace
      while (!isAtEnd() && peek() != '[' && peek() != '|' && !Character.isWhitespace(peek())) {
        sb.append(advance());
      }
      // Handle Java array notation suffix: ClassName[] or ClassName[][]
      if (!isAtEnd() && peek() == '[' && pos + 1 < input.length() && input.charAt(pos + 1) == ']') {
        String className = sb.toString();
        sb.setLength(0);
        int dims = 0;
        while (!isAtEnd() && peek() == '[' && pos + 1 < input.length() && input.charAt(pos + 1) == ']') {
          dims++;
          advance(); // consume '['
          advance(); // consume ']'
        }
        for (int i = 0; i < dims; i++) {
          sb.append('[');
        }
        String primitiveCode = primitiveArrayCode(className);
        if (primitiveCode != null) {
          sb.append(primitiveCode);
        } else {
          sb.append('L').append(className).append(';');
        }
      }
    }
    return sb.toString().isEmpty() ? null : sb.toString();
  }

  /**
   * Parses a JVM array type descriptor into {@code sb}.
   * Handles: {@code [I}, {@code [Ljava.lang.Object;}, {@code [[Z}, etc.
   */
  private void parseArrayDescriptor(StringBuilder sb) {
    if (isAtEnd() || peek() != '[') {
      throw new HdumpPathParseException("Expected '[' for array type descriptor at position " + pos);
    }
    sb.append(advance()); // consume '['
    if (isAtEnd()) {
      throw new HdumpPathParseException("Incomplete array type descriptor at position " + pos);
    }
    char next = peek();
    if (next == '[') {
      parseArrayDescriptor(sb);
    } else if (next == 'L') {
      sb.append(advance()); // consume 'L'
      while (!isAtEnd() && peek() != ';' && peek() != '|' && !Character.isWhitespace(peek())) {
        sb.append(advance());
      }
      if (!isAtEnd() && peek() == ';') {
        sb.append(advance()); // consume ';'
      }
    } else if ("ZCIJFDSB".indexOf(next) >= 0) {
      sb.append(advance()); // consume primitive type code
    } else {
      throw new HdumpPathParseException(
          "Invalid array type descriptor at position " + pos + ": expected L, [, or primitive code (Z/C/I/J/F/D/S/B)");
    }
  }

  /** Returns true when the current position holds {@code [} and the next char looks like a JVM array descriptor. */
  private boolean isArrayDescriptorAhead() {
    if (pos + 1 >= input.length()) return false;
    char next = input.charAt(pos + 1);
    return next == '[' || next == 'L' || "ZCIJFDSB".indexOf(next) >= 0;
  }

  /** Maps a Java primitive type name to its JVM array type code, or {@code null} if not primitive. */
  private static String primitiveArrayCode(String name) {
    return switch (name) {
      case "boolean" -> "Z";
      case "char" -> "C";
      case "int" -> "I";
      case "long" -> "J";
      case "float" -> "F";
      case "double" -> "D";
      case "short" -> "S";
      case "byte" -> "B";
      default -> null;
    };
  }

  private List<Predicate> parsePredicates() {
    List<Predicate> predicates = new ArrayList<>();
    skipWs();

    if (peek() == ']') {
      return predicates; // Empty predicate list
    }

    BoolExpr expr = parseBoolExpr();
    predicates.add(new ExprPredicate(expr));

    return predicates;
  }

  private BoolExpr parseBoolExpr() {
    return parseOrExpr();
  }

  private BoolExpr parseOrExpr() {
    BoolExpr left = parseAndExpr();
    skipWs();

    while (matchKeyword("or")) {
      skipWs();
      BoolExpr right = parseAndExpr();
      left = new LogicalExpr(left, LogicalOp.OR, right);
      skipWs();
    }

    return left;
  }

  private BoolExpr parseAndExpr() {
    BoolExpr left = parseNotExpr();
    skipWs();

    while (matchKeyword("and")) {
      skipWs();
      BoolExpr right = parseNotExpr();
      left = new LogicalExpr(left, LogicalOp.AND, right);
      skipWs();
    }

    return left;
  }

  private BoolExpr parseNotExpr() {
    skipWs();
    if (matchKeyword("not")) {
      skipWs();
      return new NotExpr(parseNotExpr());
    }
    return parsePrimaryBool();
  }

  private BoolExpr parsePrimaryBool() {
    skipWs();

    if (peek() == '(') {
      advance(); // consume '('
      BoolExpr expr = parseBoolExpr();
      skipWs();
      expect(')');
      return expr;
    }

    // Parse comparison: fieldPath op literal
    List<String> fieldPath = parseFieldPath();
    skipWs();
    Op op = parseOp();
    skipWs();
    Object literal = parseLiteral();

    return new CompExpr(fieldPath, op, literal);
  }

  private List<String> parseFieldPath() {
    List<String> path = new ArrayList<>();
    path.add(parseIdentifier());

    while (peek() == '.') {
      advance(); // consume '.'
      path.add(parseIdentifier());
    }

    return path;
  }

  private String parseIdentifier() {
    StringBuilder sb = new StringBuilder();
    skipWs();

    // First character must be letter or underscore or $
    if (!isAtEnd() && (Character.isLetter(peek()) || peek() == '_' || peek() == '$')) {
      sb.append(advance());
    } else {
      throw new HdumpPathParseException("Expected identifier at position " + pos);
    }

    // Subsequent characters can include digits
    while (!isAtEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_' || peek() == '$')) {
      sb.append(advance());
    }

    return sb.toString();
  }

  private Op parseOp() {
    if (match(">=")) return Op.GE;
    if (match("<=")) return Op.LE;
    if (match("!=")) return Op.NE;
    if (match("==")) return Op.EQ;
    if (match("=")) return Op.EQ;
    if (match(">")) return Op.GT;
    if (match("<")) return Op.LT;
    if (match("~")) return Op.REGEX;

    throw new HdumpPathParseException("Expected operator at position " + pos);
  }

  private Object parseLiteral() {
    skipWs();

    // String literal
    if (peek() == '"' || peek() == '\'') {
      return parseStringLiteral();
    }

    // Boolean
    if (matchKeyword("true")) return true;
    if (matchKeyword("false")) return false;

    // Null
    if (matchKeyword("null")) return null;

    // Number
    return parseNumber();
  }

  private String parseStringLiteral() {
    char quote = advance();
    StringBuilder sb = new StringBuilder();

    while (!isAtEnd() && peek() != quote) {
      if (peek() == '\\') {
        advance(); // consume backslash
        if (!isAtEnd()) {
          char escaped = advance();
          sb.append(
              switch (escaped) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case '\\' -> '\\';
                case '"' -> '"';
                case '\'' -> '\'';
                default -> escaped;
              });
        }
      } else {
        sb.append(advance());
      }
    }

    if (isAtEnd()) {
      throw new HdumpPathParseException("Unterminated string literal");
    }
    advance(); // consume closing quote

    return sb.toString();
  }

  private Number parseNumber() {
    StringBuilder sb = new StringBuilder();

    // Optional sign
    if (peek() == '-' || peek() == '+') {
      sb.append(advance());
    }

    // Integer part
    while (!isAtEnd() && Character.isDigit(peek())) {
      sb.append(advance());
    }

    // Optional decimal part
    boolean isFloat = false;
    if (peek() == '.') {
      isFloat = true;
      sb.append(advance());
      while (!isAtEnd() && Character.isDigit(peek())) {
        sb.append(advance());
      }
    }

    // Optional exponent
    if (peek() == 'e' || peek() == 'E') {
      isFloat = true;
      sb.append(advance());
      if (peek() == '-' || peek() == '+') {
        sb.append(advance());
      }
      while (!isAtEnd() && Character.isDigit(peek())) {
        sb.append(advance());
      }
    }

    // Size suffixes
    long multiplier = 1;
    if (matchKeyword("KB") || matchKeyword("kb") || matchKeyword("K") || matchKeyword("k")) {
      multiplier = 1024;
    } else if (matchKeyword("MB") || matchKeyword("mb") || matchKeyword("M") || matchKeyword("m")) {
      multiplier = 1024 * 1024;
    } else if (matchKeyword("GB") || matchKeyword("gb") || matchKeyword("G") || matchKeyword("g")) {
      multiplier = 1024 * 1024 * 1024;
    }

    String numStr = sb.toString();
    if (numStr.isEmpty()) {
      throw new HdumpPathParseException("Expected number at position " + pos);
    }

    if (isFloat) {
      return Double.parseDouble(numStr) * multiplier;
    } else {
      return Long.parseLong(numStr) * multiplier;
    }
  }

  private PipelineOp parsePipelineOp() {
    String opName = parseIdentifier().toLowerCase();
    skipWs();

    return switch (opName) {
      case "select" -> parseSelectOp();
      case "top" -> parseTopOp();
      case "groupby", "group" -> parseGroupByOp();
      case "count" -> { consumeOptionalEmptyParens(); yield new CountOp(); }
      case "sum" -> parseSumOp();
      case "stats" -> parseStatsOp();
      case "sortby", "sort", "orderby", "order" -> parseSortByOp();
      case "head" -> parseHeadOp();
      case "tail" -> parseTailOp();
      case "filter", "where" -> parseFilterOp();
      case "distinct", "unique" -> parseDistinctOp();
      case "pathtoroot", "pathroot", "path" -> parsePathToRootOp();
      case "checkleaks", "leaks" -> parseCheckLeaksOp();
      case "dominators", "dominated" -> parseDominatorsOp();
      default -> throw new HdumpPathParseException("Unknown pipeline operation: " + opName);
    };
  }

  private SelectOp parseSelectOp() {
    expect('(');
    List<SelectField> fields = new ArrayList<>();

    do {
      skipWs();
      String field = parseIdentifier();
      String alias = null;
      skipWs();

      if (matchKeyword("as")) {
        skipWs();
        alias = parseIdentifier();
        skipWs();
      }

      fields.add(new SelectField(field, alias));
    } while (matchChar(','));

    expect(')');
    return new SelectOp(fields);
  }

  private TopOp parseTopOp() {
    expect('(');
    skipWs();

    int n = parseNumber().intValue();
    String orderBy = null;
    boolean descending = true;

    skipWs();
    if (matchChar(',')) {
      skipWs();
      orderBy = parseIdentifier();
      skipWs();

      if (matchChar(',')) {
        skipWs();
        if (matchKeyword("asc")) {
          descending = false;
        } else if (matchKeyword("desc")) {
          descending = true;
        }
      }
    }

    expect(')');
    return new TopOp(n, orderBy, descending);
  }

  private GroupByOp parseGroupByOp() {
    expect('(');
    List<String> groupFields = new ArrayList<>();
    AggOp aggOp = AggOp.COUNT; // Default aggregation
    ValueExpr valueExpr = null;
    String sortBy = null;
    boolean ascending = false; // Default to descending (most common for aggregates)

    do {
      skipWs();
      // Check for agg= parameter
      if (lookahead("agg=") || lookahead("agg =")) {
        matchKeyword("agg");
        skipWs();
        expect('=');
        skipWs();
        String aggName = parseIdentifier().toLowerCase();
        aggOp =
            switch (aggName) {
              case "count" -> AggOp.COUNT;
              case "sum" -> AggOp.SUM;
              case "avg" -> AggOp.AVG;
              case "min" -> AggOp.MIN;
              case "max" -> AggOp.MAX;
              default -> throw new HdumpPathParseException("Unknown aggregation: " + aggName);
            };
      } else if (lookahead("value=") || lookahead("value =")) {
        matchKeyword("value");
        skipWs();
        expect('=');
        skipWs();
        valueExpr = parseValueExpr();
      } else if (lookahead("sortBy=") || lookahead("sortBy =") || lookahead("sort=")
          || lookahead("sort =")) {
        if (lookahead("sortBy")) {
          matchKeyword("sortBy");
        } else {
          matchKeyword("sort");
        }
        skipWs();
        expect('=');
        skipWs();
        String sortValue = parseIdentifier().toLowerCase();
        if ("key".equals(sortValue) || "value".equals(sortValue)) {
          sortBy = sortValue;
        } else {
          throw new HdumpPathParseException(
              "sortBy=/sort= must be 'key' or 'value', got: " + sortValue);
        }
      } else if (lookahead("asc=") || lookahead("asc =")) {
        matchKeyword("asc");
        skipWs();
        expect('=');
        skipWs();
        String ascValue = parseIdentifier().toLowerCase();
        ascending = "true".equals(ascValue) || "yes".equals(ascValue) || "1".equals(ascValue);
      } else {
        // Check for aggregate function call syntax: sum(expr), avg(expr), etc.
        String ident = parseIdentifier();
        skipWs();
        if (peek() == '(') {
          // This might be an aggregate function call
          AggOp funcAgg = tryParseAggOp(ident.toLowerCase());
          if (funcAgg != null) {
            advance(); // consume '('
            skipWs();
            valueExpr = parseValueExpr();
            skipWs();
            expect(')');
            aggOp = funcAgg;
          } else {
            // Not an aggregate function, treat as group field
            groupFields.add(ident);
          }
        } else {
          groupFields.add(ident);
        }
      }
      skipWs();
    } while (matchChar(','));

    expect(')');
    return new GroupByOp(groupFields, aggOp, valueExpr, sortBy, ascending);
  }

  /** Try to parse an aggregate operation from the given name, returns null if not recognized. */
  private AggOp tryParseAggOp(String name) {
    return switch (name) {
      case "count" -> AggOp.COUNT;
      case "sum" -> AggOp.SUM;
      case "avg" -> AggOp.AVG;
      case "min" -> AggOp.MIN;
      case "max" -> AggOp.MAX;
      default -> null;
    };
  }

  // === Value expression parsing (for groupBy value=) ===

  private ValueExpr parseValueExpr() {
    return parseAdditiveExpr();
  }

  private ValueExpr parseAdditiveExpr() {
    ValueExpr left = parseMultiplicativeExpr();
    skipWs();

    while (peek() == '+' || peek() == '-') {
      char opChar = advance();
      ArithOp op = ArithOp.fromSymbol(String.valueOf(opChar));
      skipWs();
      ValueExpr right = parseMultiplicativeExpr();
      left = new BinaryExpr(left, op, right);
      skipWs();
    }

    return left;
  }

  private ValueExpr parseMultiplicativeExpr() {
    ValueExpr left = parsePrimaryValueExpr();
    skipWs();

    while (peek() == '*' || peek() == '/') {
      char opChar = advance();
      ArithOp op = ArithOp.fromSymbol(String.valueOf(opChar));
      skipWs();
      ValueExpr right = parsePrimaryValueExpr();
      left = new BinaryExpr(left, op, right);
      skipWs();
    }

    return left;
  }

  private ValueExpr parsePrimaryValueExpr() {
    skipWs();

    // Parenthesized expression
    if (peek() == '(') {
      advance();
      ValueExpr expr = parseValueExpr();
      skipWs();
      expect(')');
      return expr;
    }

    // Number literal
    if (Character.isDigit(peek()) || peek() == '-' || peek() == '+') {
      // Check if it's actually a number (not just + or - followed by non-digit)
      if (peek() == '-' || peek() == '+') {
        int savedPos = pos;
        advance();
        skipWs();
        if (!Character.isDigit(peek())) {
          pos = savedPos;
          // Not a number, must be identifier
          return new FieldRef(parseIdentifier());
        }
        pos = savedPos;
      }
      return new NumberLiteral(parseNumber().doubleValue());
    }

    // Field reference
    return new FieldRef(parseIdentifier());
  }

  private SumOp parseSumOp() {
    expect('(');
    skipWs();
    String field = parseIdentifier();
    skipWs();
    expect(')');
    return new SumOp(field);
  }

  private StatsOp parseStatsOp() {
    expect('(');
    skipWs();
    String field = parseIdentifier();
    skipWs();
    expect(')');
    return new StatsOp(field);
  }

  private SortByOp parseSortByOp() {
    expect('(');
    List<SortField> fields = new ArrayList<>();

    do {
      skipWs();
      String field = parseIdentifier();
      boolean descending = false;
      skipWs();

      if (matchKeyword("desc")) {
        descending = true;
      } else if (matchKeyword("asc")) {
        descending = false;
      }

      fields.add(new SortField(field, descending));
      skipWs();
    } while (matchChar(','));

    expect(')');
    return new SortByOp(fields);
  }

  private HeadOp parseHeadOp() {
    expect('(');
    skipWs();
    int n = parseNumber().intValue();
    skipWs();
    expect(')');
    return new HeadOp(n);
  }

  private TailOp parseTailOp() {
    expect('(');
    skipWs();
    int n = parseNumber().intValue();
    skipWs();
    expect(')');
    return new TailOp(n);
  }

  private FilterOp parseFilterOp() {
    expect('(');
    skipWs();
    BoolExpr expr = parseBoolExpr();
    skipWs();
    expect(')');
    return new FilterOp(new ExprPredicate(expr));
  }

  private DistinctOp parseDistinctOp() {
    expect('(');
    skipWs();
    String field = parseIdentifier();
    skipWs();
    expect(')');
    return new DistinctOp(field);
  }

  /**
   * Parses checkLeaks shorthand: checkLeaks(...) → objects | checkLeaks(...)
   * This allows users to type just "checkLeaks(detector="duplicate-strings")"
   * instead of "show objects | checkLeaks(detector="duplicate-strings")"
   */
  private Query parseCheckLeaksShorthand() {
    // We already matched "checkLeaks", now parse it as a pipeline op
    PipelineOp checkLeaksOp = parseCheckLeaksOp();

    // Create a query with Root.OBJECTS and the checkLeaks operation
    return new Query(Root.OBJECTS, null, false, List.of(), List.of(checkLeaksOp));
  }

  // === Lexer utilities ===

  private char peek() {
    return isAtEnd() ? '\0' : input.charAt(pos);
  }

  private char advance() {
    return input.charAt(pos++);
  }

  private boolean isAtEnd() {
    return pos >= input.length();
  }

  private void skipWs() {
    while (!isAtEnd() && Character.isWhitespace(peek())) {
      pos++;
    }
  }

  private boolean match(String expected) {
    if (input.substring(pos).startsWith(expected)) {
      pos += expected.length();
      return true;
    }
    return false;
  }

  private boolean matchChar(char c) {
    skipWs();
    if (peek() == c) {
      advance();
      return true;
    }
    return false;
  }

  private boolean matchKeyword(String keyword) {
    int savedPos = pos;
    if (input.substring(pos).toLowerCase().startsWith(keyword.toLowerCase())) {
      int endPos = pos + keyword.length();
      // Ensure it's not part of a longer identifier
      if (endPos >= input.length()
          || !Character.isLetterOrDigit(input.charAt(endPos)) && input.charAt(endPos) != '_') {
        pos = endPos;
        return true;
      }
    }
    pos = savedPos;
    return false;
  }

  private boolean lookahead(String expected) {
    return input.substring(pos).toLowerCase().startsWith(expected.toLowerCase());
  }

  private void expect(char c) {
    skipWs();
    if (peek() != c) {
      throw new HdumpPathParseException(
          "Expected '" + c + "' at position " + pos + ", found '" + peek() + "'");
    }
    advance();
  }

  /** Consumes {@code ()} if present, allowing no-arg ops to be written as either {@code op} or {@code op()}. */
  private void consumeOptionalEmptyParens() {
    skipWs();
    if (peek() == '(') {
      advance();
      skipWs();
      expect(')');
    }
  }

  private String remaining() {
    return input.substring(pos);
  }

  private PathToRootOp parsePathToRootOp() {
    // pathToRoot has no parameters - it's a zero-arg operator
    // Optional empty parens allowed: pathToRoot() or pathToRoot
    skipWs();
    if (peek() == '(') {
      advance();
      skipWs();
      expect(')');
    }
    return new PathToRootOp();
  }

  private CheckLeaksOp parseCheckLeaksOp() {
    expect('(');
    String detector = null;
    String filter = null;
    Integer threshold = null;
    Integer minSize = null;

    do {
      skipWs();
      if (lookahead("detector=") || lookahead("detector =")) {
        matchKeyword("detector");
        skipWs();
        expect('=');
        skipWs();
        detector = parseStringOrIdentifier();
      } else if (lookahead("filter=") || lookahead("filter =")) {
        matchKeyword("filter");
        skipWs();
        expect('=');
        skipWs();
        // Filter is a variable reference like $myQuery
        if (peek() == '$') {
          advance();
          filter = parseIdentifier();
        } else {
          filter = parseStringOrIdentifier();
        }
      } else if (lookahead("threshold=") || lookahead("threshold =")) {
        matchKeyword("threshold");
        skipWs();
        expect('=');
        skipWs();
        threshold = parseNumber().intValue();
      } else if (lookahead("minsize=") || lookahead("minsize =")) {
        matchKeyword("minsize");
        skipWs();
        expect('=');
        skipWs();
        minSize = parseNumber().intValue();
      } else {
        throw new HdumpPathParseException(
            "Expected detector=, filter=, threshold=, or minsize= parameter");
      }
      skipWs();
    } while (matchChar(','));

    expect(')');
    return new CheckLeaksOp(detector, filter, threshold, minSize);
  }

  private DominatorsOp parseDominatorsOp() {
    // dominators accepts optional mode parameter
    // Syntax: dominators() or dominators("byClass") or dominators("tree")
    skipWs();
    String mode = null;
    if (peek() == '(') {
      advance();
      skipWs();
      if (peek() != ')') {
        mode = parseStringOrIdentifier();
      }
      skipWs();
      expect(')');
    }
    return mode != null ? new DominatorsOp(mode) : new DominatorsOp();
  }

  private String parseStringOrIdentifier() {
    skipWs();
    if (peek() == '"' || peek() == '\'') {
      return parseStringLiteral();
    } else {
      return parseIdentifier();
    }
  }
}

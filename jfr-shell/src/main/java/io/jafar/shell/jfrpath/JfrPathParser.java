package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal JfrPath parser v0. Supports: root segment (events|metadata|chunks|cp), path segments,
 * filters in brackets like: events/jdk.ExecutionSample[thread/name~"main"][duration>10]
 */
public final class JfrPathParser {
  private final String input;
  private int pos = 0;

  private JfrPathParser(String input) {
    this.input = input;
  }

  public static Query parse(String input) {
    return new JfrPathParser(input).parseQuery();
  }

  private Query parseQuery() {
    skipWs();
    String rootTok = readUntil('/', '[', ' ');
    Root root =
        switch (rootTok.toLowerCase(Locale.ROOT)) {
          case "events" -> Root.EVENTS;
          case "metadata" -> Root.METADATA;
          case "chunks" -> Root.CHUNKS;
          case "cp" -> Root.CP;
          default -> throw error("Unknown root: " + rootTok);
        };
    if (peek() == '/') pos++;

    // Check for multi-event type syntax: events/(type1|type2|...)
    List<String> eventTypes = new ArrayList<>();
    boolean isMultiType = false;

    if (root == Root.EVENTS || root == Root.METADATA || root == Root.CP) {
      if (peek() == '(') {
        eventTypes = parseMultiEventType();
        isMultiType = eventTypes.size() > 1;
      } else {
        String type = readUntil('/', '[', ' ', '|');
        if (!type.isEmpty()) eventTypes.add(type);
      }
    }

    List<String> segments = new ArrayList<>();
    if (!eventTypes.isEmpty()) {
      segments.add(eventTypes.get(0)); // backward compat
    }

    List<Predicate> preds = new ArrayList<>();
    // Interleave segments and filters: a/b[c>0]/d[e=1]
    while (!eof()) {
      if (peek() == '|' || Character.isWhitespace((char) peek())) break;
      if (peek() != '[') {
        // For multi-type queries, we already consumed the type list
        if (!isMultiType || segments.size() > 0) {
          String seg = readUntil('/', '[', ' ', '|');
          if (!seg.isEmpty()) segments.add(seg);
        }
      }
      // consume zero or more filters after the current segment, prefixing their paths
      while (!eof() && peek() == '[') {
        pos++; // [
        Predicate p = parsePredicate();
        if (p instanceof FieldPredicate fp) {
          List<String> prefix =
              segments.size() <= 1
                  ? java.util.List.of()
                  : new java.util.ArrayList<>(segments.subList(1, segments.size()));
          List<String> combined = new java.util.ArrayList<>(prefix.size() + fp.fieldPath.size());
          combined.addAll(prefix);
          combined.addAll(fp.fieldPath);
          p = new FieldPredicate(combined, fp.op, fp.literal, fp.matchMode);
        }
        preds.add(p);
        expect(']');
      }
      if (peek() == '/') {
        pos++;
        continue;
      }
      if (peek() == '|' || Character.isWhitespace((char) peek())) break;
      if (peek() != '[') break;
    }
    // Optional pipeline: | fn(args)? (| fn(args)?)*
    List<JfrPath.PipelineOp> pipeline = new ArrayList<>();
    skipWs();
    while (!eof() && peek() == '|') {
      pos++; // |
      skipWs();
      pipeline.add(parsePipelineOp());
      skipWs();
    }
    skipWs();
    if (!eof()) throw error("Trailing characters at position " + pos);

    // Return multi-type query if detected, otherwise use legacy constructor
    if (isMultiType) {
      return new Query(root, eventTypes, preds, pipeline, true);
    }
    return new Query(root, segments, preds, pipeline);
  }

  private List<String> parseMultiEventType() {
    expect('(');
    List<String> types = new ArrayList<>();

    while (!eof() && peek() != ')') {
      skipWs();
      String type = parseEventTypeName();
      if (type.isEmpty()) {
        if (peek() == '|') throw error("Empty event type before pipe separator");
        if (peek() == ')') throw error("Empty event type before closing parenthesis");
        throw error("Expected event type name");
      }
      types.add(type);
      skipWs();

      if (peek() == '|') {
        pos++; // consume pipe
        skipWs();
        if (peek() == '|') throw error("Consecutive pipe separators not allowed");
        if (peek() == ')') throw error("Empty event type after pipe separator");
      } else if (peek() == ')') {
        break;
      } else if (!eof()) {
        throw error("Expected '|' or ')' after event type, got '" + (char) peek() + "'");
      }
    }

    expect(')');
    if (types.isEmpty()) throw error("Empty event type list");
    return types;
  }

  private Predicate parsePredicate() {
    skipWs();
    int start = pos;
    try {
      io.jafar.shell.jfrpath.JfrPath.BoolExpr expr = parseBoolExpr();
      return new io.jafar.shell.jfrpath.JfrPath.ExprPredicate(expr);
    } catch (IllegalArgumentException ex) {
      pos = start;
      return parseLegacyPredicate();
    }
  }

  private Predicate parseLegacyPredicate() {
    List<String> fieldPath = new ArrayList<>();
    MatchMode mode = null;
    int save = pos;
    String maybeMode = readIdent();
    if (!maybeMode.isEmpty() && (peek() == ':')) {
      pos++; // ':'
      String mm = maybeMode.toLowerCase(Locale.ROOT);
      switch (mm) {
        case "any" -> mode = MatchMode.ANY;
        case "all" -> mode = MatchMode.ALL;
        case "none" -> mode = MatchMode.NONE;
        default -> {
          mode = null;
          pos = save;
        }
      }
    } else {
      pos = save;
    }
    fieldPath.addAll(parsePathSegments());
    skipWs();
    Op op;
    if (match("=~") || match("~")) op = Op.REGEX;
    else if (match(">=")) op = Op.GE;
    else if (match("<=")) op = Op.LE;
    else if (match("==") || match("=")) op = Op.EQ;
    else if (match("!=")) op = Op.NE;
    else if (match(">")) op = Op.GT;
    else if (match("<")) op = Op.LT;
    else throw error("Expected operator after field path");
    skipWs();
    Object lit = parseLiteral();
    if (mode != null) return new FieldPredicate(fieldPath, op, lit, mode);
    return new FieldPredicate(fieldPath, op, lit);
  }

  // booleanExpr := andExpr ( 'or' andExpr )*
  private io.jafar.shell.jfrpath.JfrPath.BoolExpr parseBoolExpr() {
    var left = parseAndExpr();
    skipWs();
    while (startsWithIgnoreCase("or")) {
      pos += 2;
      skipWs();
      var right = parseAndExpr();
      left =
          new io.jafar.shell.jfrpath.JfrPath.LogicalExpr(
              left, right, io.jafar.shell.jfrpath.JfrPath.LogicalExpr.Lop.OR);
      skipWs();
    }
    return left;
  }

  // andExpr := notExpr ( 'and' notExpr )*
  private io.jafar.shell.jfrpath.JfrPath.BoolExpr parseAndExpr() {
    var left = parseNotExpr();
    skipWs();
    while (startsWithIgnoreCase("and")) {
      pos += 3;
      skipWs();
      var right = parseNotExpr();
      left =
          new io.jafar.shell.jfrpath.JfrPath.LogicalExpr(
              left, right, io.jafar.shell.jfrpath.JfrPath.LogicalExpr.Lop.AND);
      skipWs();
    }
    return left;
  }

  private io.jafar.shell.jfrpath.JfrPath.BoolExpr parseNotExpr() {
    skipWs();
    if (startsWithIgnoreCase("not")) {
      pos += 3;
      skipWs();
      return new io.jafar.shell.jfrpath.JfrPath.NotExpr(parsePrimaryBool());
    }
    return parsePrimaryBool();
  }

  private io.jafar.shell.jfrpath.JfrPath.BoolExpr parsePrimaryBool() {
    skipWs();
    if (peek() == '(') {
      pos++;
      var e = parseBoolExpr();
      expect(')');
      return e;
    }
    int save = pos;
    String name = readIdent();
    if (!name.isEmpty() && peek() == '(') {
      pos++;
      var args = parseFuncArgs();
      expect(')');
      return new io.jafar.shell.jfrpath.JfrPath.FuncBoolExpr(name, args);
    }
    pos = save;
    var ve = parseValueExpr();
    skipWs();
    Op op;
    if (match("=~") || match("~")) op = Op.REGEX;
    else if (match(">=")) op = Op.GE;
    else if (match("<=")) op = Op.LE;
    else if (match("==") || match("=")) op = Op.EQ;
    else if (match("!=")) op = Op.NE;
    else if (match(">")) op = Op.GT;
    else if (match("<")) op = Op.LT;
    else throw error("Expected operator in comparison");
    skipWs();
    Object lit = parseLiteral();
    return new io.jafar.shell.jfrpath.JfrPath.CompExpr(ve, op, lit);
  }

  private io.jafar.shell.jfrpath.JfrPath.ValueExpr parseValueExpr() {
    skipWs();
    int save = pos;
    String name = readIdent();
    if (!name.isEmpty() && peek() == '(') {
      pos++;
      var args = parseFuncArgs();
      expect(')');
      return new io.jafar.shell.jfrpath.JfrPath.FuncValueExpr(name, args);
    }
    pos = save;
    var segs = parsePathSegments();
    return new io.jafar.shell.jfrpath.JfrPath.PathRef(segs);
  }

  private java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> parseFuncArgs() {
    java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args = new java.util.ArrayList<>();
    skipWs();
    if (peek() == ')') return args;
    while (!eof() && peek() != ')') {
      skipWs();
      if (peek() == '"' || peek() == '\'') {
        args.add(new io.jafar.shell.jfrpath.JfrPath.LiteralArg(parseLiteral()));
      } else if (Character.isDigit((char) peek())) {
        args.add(new io.jafar.shell.jfrpath.JfrPath.LiteralArg(parseLiteral()));
      } else {
        args.add(new io.jafar.shell.jfrpath.JfrPath.PathArg(parsePathSegments()));
      }
      skipWs();
      if (peek() == ',') {
        pos++;
        skipWs();
      }
    }
    return args;
  }

  private java.util.List<String> parsePathSegments() {
    java.util.List<String> segs = new java.util.ArrayList<>();
    while (!eof()) {
      String ident = readIdent();
      if (ident.isEmpty()) break;
      StringBuilder seg = new StringBuilder(ident);
      while (peek() == '[') {
        int b = pos;
        pos++;
        while (!eof() && peek() != ']') pos++;
        expect(']');
        seg.append(input, b, pos);
      }
      segs.add(seg.toString());
      // Accept both / and . as path separators for nested field access
      if (peek() == '/' || peek() == '.') {
        pos++;
        continue;
      }
      break;
    }
    if (segs.isEmpty()) throw error("Expected path");
    return segs;
  }

  private Object parseLiteral() {
    if (peek() == '"' || peek() == '\'') {
      char quote = (char) peek();
      pos++;
      StringBuilder sb = new StringBuilder();
      while (!eof() && peek() != quote) {
        char c = (char) peek();
        if (c == '\\') {
          pos++;
          if (eof()) break;
          c = (char) peek();
        }
        sb.append(c);
        pos++;
      }
      expect(quote);
      return sb.toString();
    }
    // boolean literals
    if (startsWithIgnoreCase("true")) {
      pos += 4;
      return Boolean.TRUE;
    }
    if (startsWithIgnoreCase("false")) {
      pos += 5;
      return Boolean.FALSE;
    }

    // number (int or decimal)
    int start = pos;
    boolean dot = false;
    while (!eof()) {
      char c = (char) peek();
      if (Character.isDigit(c)) {
        pos++;
        continue;
      }
      if (c == '.' && !dot) {
        dot = true;
        pos++;
        continue;
      }
      break;
    }
    if (pos == start) throw error("Expected literal");
    String num = input.substring(start, pos);
    try {
      if (dot) return Double.parseDouble(num);
      return Long.parseLong(num);
    } catch (NumberFormatException e) {
      throw error("Invalid number literal: " + num);
    }
  }

  private String readIdent() {
    int start = pos;
    while (!eof()) {
      char c = (char) peek();
      if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '-') {
        pos++;
      } else break;
    }
    return input.substring(start, pos);
  }

  private String readUntil(char... terms) {
    int start = pos;
    outer:
    while (!eof()) {
      char c = (char) peek();
      for (char t : terms) {
        if (c == t) break outer;
      }
      pos++;
    }
    return input.substring(start, pos).trim();
  }

  private void skipWs() {
    while (!eof() && Character.isWhitespace(input.charAt(pos))) pos++;
  }

  private boolean match(String s) {
    if (input.startsWith(s, pos)) {
      pos += s.length();
      return true;
    }
    return false;
  }

  private boolean startsWithIgnoreCase(String s) {
    int n = s.length();
    if (pos + n > input.length()) return false;
    for (int i = 0; i < n; i++) {
      char a = Character.toLowerCase(input.charAt(pos + i));
      char b = Character.toLowerCase(s.charAt(i));
      if (a != b) return false;
    }
    return true;
  }

  private JfrPath.PipelineOp parsePipelineOp() {
    String name = readIdent().toLowerCase(Locale.ROOT);
    skipWs();
    List<String> valuePath = List.of();
    if ("count".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        expect(')');
      }
      return new JfrPath.CountOp();
    } else if ("stats".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.StatsOp(valuePath);
    } else if ("quantiles".equals(name) || "quantile".equals(name)) {
      List<Double> qs = new ArrayList<>();
      if (peek() == '(') {
        pos++;
        skipWs();
        boolean first = true;
        while (!eof() && peek() != ')') {
          if (!first) {
            if (peek() == ',') {
              pos++;
              skipWs();
            }
          }
          first = false;
          // accept either number or path spec prefixed by 'path='
          if (startsWithIgnoreCase("path=")) {
            pos += 5; // path=
            valuePath = parsePathArg();
          } else {
            Object lit = parseLiteral();
            if (!(lit instanceof Number)) throw error("quantiles() expects numeric quantiles 0..1");
            qs.add(((Number) lit).doubleValue());
          }
          skipWs();
        }
        expect(')');
      }
      return new JfrPath.QuantilesOp(qs, valuePath);
    } else if ("sketch".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.SketchOp(valuePath);
    } else if ("sum".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.SumOp(valuePath);
    } else if ("groupby".equals(name)) {
      List<String> keyPath = List.of();
      String aggFunc = "count";
      List<String> aggValuePath = List.of();
      String sortBy = null;
      boolean ascending = false;
      if (peek() == '(') {
        pos++;
        skipWs();
        // First arg is always the key path
        keyPath = parsePathArg();
        skipWs();
        // Parse optional agg=, value=, sortBy=, asc= parameters
        while (peek() == ',') {
          pos++;
          skipWs();
          if (startsWithIgnoreCase("agg=")) {
            pos += 4;
            skipWs();
            aggFunc = readIdent().toLowerCase(Locale.ROOT);
          } else if (startsWithIgnoreCase("value=")) {
            pos += 6;
            skipWs();
            aggValuePath = parsePathArg();
          } else if (startsWithIgnoreCase("sortBy=")) {
            pos += 7;
            skipWs();
            String sortVal = readIdent().toLowerCase(Locale.ROOT);
            if (!"key".equals(sortVal) && !"value".equals(sortVal)) {
              throw error("sortBy= expects 'key' or 'value'");
            }
            sortBy = sortVal;
          } else if (startsWithIgnoreCase("asc=")) {
            pos += 4;
            skipWs();
            String ascVal = readIdent().toLowerCase(Locale.ROOT);
            if ("true".equals(ascVal)) {
              ascending = true;
            } else if ("false".equals(ascVal)) {
              ascending = false;
            } else {
              throw error("asc= expects 'true' or 'false'");
            }
          } else {
            throw error("groupBy() expects agg=, value=, sortBy=, or asc= parameters");
          }
          skipWs();
        }
        expect(')');
      }
      return new JfrPath.GroupByOp(keyPath, aggFunc, aggValuePath, sortBy, ascending);
    } else if ("top".equals(name)) {
      int n = 10; // default
      List<String> byPath = List.of("value");
      boolean ascending = false;
      if (peek() == '(') {
        pos++;
        skipWs();
        // First arg is the count
        Object lit = parseLiteral();
        if (!(lit instanceof Number)) throw error("top() expects numeric count");
        n = ((Number) lit).intValue();
        skipWs();
        // Parse optional by= and asc= parameters
        while (peek() == ',') {
          pos++;
          skipWs();
          if (startsWithIgnoreCase("by=")) {
            pos += 3;
            skipWs();
            byPath = parsePathArg();
          } else if (startsWithIgnoreCase("asc=")) {
            pos += 4;
            skipWs();
            Object boolLit = parseLiteral();
            ascending = Boolean.TRUE.equals(boolLit);
          } else {
            throw error("top() expects by= or asc= parameters");
          }
          skipWs();
        }
        expect(')');
      }
      return new JfrPath.TopOp(n, byPath, ascending);
    } else if ("len".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.LenOp(valuePath);
    } else if ("uppercase".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.UppercaseOp(valuePath);
    } else if ("lowercase".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.LowercaseOp(valuePath);
    } else if ("trim".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.TrimOp(valuePath);
    } else if ("abs".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.AbsOp(valuePath);
    } else if ("round".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.RoundOp(valuePath);
    } else if ("floor".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.FloorOp(valuePath);
    } else if ("ceil".equals(name)) {
      if (peek() == '(') {
        pos++;
        skipWs();
        if (peek() != ')') {
          valuePath = parsePathArg();
        }
        expect(')');
      }
      return new JfrPath.CeilOp(valuePath);
    } else if ("contains".equals(name)) {
      String substr = null;
      if (peek() == '(') {
        pos++;
        skipWs();
        // optional path first
        if (peek() != ')' && peek() != '"' && peek() != '\'') {
          valuePath = parsePathArg();
          skipWs();
          if (peek() == ',') {
            pos++;
            skipWs();
          }
        }
        Object lit = null;
        if (peek() == '"' || peek() == '\'') {
          lit = parseLiteral();
        }
        substr = lit == null ? null : String.valueOf(lit);
        expect(')');
      }
      return new JfrPath.ContainsOp(valuePath, substr);
    } else if ("replace".equals(name)) {
      String target = null, repl = null;
      if (peek() == '(') {
        pos++;
        skipWs();
        // optional path first
        if (peek() != ')' && peek() != '"' && peek() != '\'') {
          valuePath = parsePathArg();
          skipWs();
          if (peek() == ',') {
            pos++;
            skipWs();
          }
        }
        Object l1 = (peek() == '"' || peek() == '\'') ? parseLiteral() : null;
        skipWs();
        if (peek() == ',') {
          pos++;
          skipWs();
        }
        Object l2 = (peek() == '"' || peek() == '\'') ? parseLiteral() : null;
        target = l1 == null ? null : String.valueOf(l1);
        repl = l2 == null ? null : String.valueOf(l2);
        expect(')');
      }
      return new JfrPath.ReplaceOp(valuePath, target, repl);
    } else if ("decoratebytime".equals(name)) {
      return parseDecorateByTime();
    } else if ("decoratebykey".equals(name)) {
      return parseDecorateByKey();
    } else if ("select".equals(name)) {
      return parseSelect();
    } else if ("tomap".equals(name)) {
      return parseToMap();
    } else if ("timerange".equals(name)) {
      return parseTimeRange();
    } else {
      throw error("Unknown pipeline function: " + name);
    }
  }

  private JfrPath.DecorateByTimeOp parseDecorateByTime() {
    expect('(');
    skipWs();

    // First arg: decorator event type (identifier like jdk.ExecutionSample)
    String decoratorType = parseEventTypeName();
    skipWs();

    List<String> decoratorFields = List.of();
    List<String> threadPathPrimary = null;
    List<String> threadPathDecorator = null;

    // Parse named parameters: fields=..., threadPath=..., decoratorThreadPath=...
    while (peek() == ',') {
      pos++; // ,
      skipWs();

      if (startsWithIgnoreCase("fields=")) {
        pos += 7; // fields=
        skipWs();
        decoratorFields = parseFieldList();
      } else if (startsWithIgnoreCase("threadPath=")) {
        pos += 11; // threadPath=
        skipWs();
        threadPathPrimary = parsePathArg();
      } else if (startsWithIgnoreCase("decoratorThreadPath=")) {
        pos += 20; // decoratorThreadPath=
        skipWs();
        threadPathDecorator = parsePathArg();
      } else {
        throw error("decorateByTime expects fields=, threadPath=, or decoratorThreadPath=");
      }
      skipWs();
    }

    expect(')');
    return new JfrPath.DecorateByTimeOp(
        decoratorType, decoratorFields, threadPathPrimary, threadPathDecorator);
  }

  private JfrPath.DecorateByKeyOp parseDecorateByKey() {
    expect('(');
    skipWs();

    // First arg: decorator event type (identifier like jdk.ExecutionSample)
    String decoratorType = parseEventTypeName();
    skipWs();

    JfrPath.KeyExpr primaryKey = null;
    JfrPath.KeyExpr decoratorKey = null;
    List<String> decoratorFields = List.of();

    // Parse key=..., decoratorKey=..., fields=...
    while (peek() == ',') {
      pos++;
      skipWs();

      if (startsWithIgnoreCase("key=")) {
        pos += 4;
        skipWs();
        primaryKey = parseKeyExpr();
      } else if (startsWithIgnoreCase("decoratorKey=")) {
        pos += 13;
        skipWs();
        decoratorKey = parseKeyExpr();
      } else if (startsWithIgnoreCase("fields=")) {
        pos += 7;
        skipWs();
        decoratorFields = parseFieldList();
      } else {
        throw error("decorateByKey expects key=, decoratorKey=, or fields=");
      }
      skipWs();
    }

    expect(')');

    if (primaryKey == null || decoratorKey == null) {
      throw error("decorateByKey requires both key= and decoratorKey=");
    }

    return new JfrPath.DecorateByKeyOp(decoratorType, primaryKey, decoratorKey, decoratorFields);
  }

  private String parseEventTypeName() {
    // Parse event type name like "jdk.ExecutionSample" or "datadog.Endpoint"
    // This allows dots in the identifier, similar to how event types are parsed in the main path
    int start = pos;
    while (!eof()) {
      char c = (char) peek();
      if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '$' || c == '-') {
        pos++;
      } else {
        break;
      }
    }
    if (pos == start) {
      throw error("Expected event type name");
    }
    return input.substring(start, pos);
  }

  private List<String> parseFieldList() {
    List<String> fields = new ArrayList<>();
    while (!eof() && peek() != ',' && peek() != ')') {
      String field = readIdent();
      if (field.isEmpty()) break;
      fields.add(field);
      skipWs();
      if (peek() == ',') {
        pos++; // ,
        skipWs();
        if (peek() == ')' || peek() == ',') break; // trailing comma or end of params
      } else break;
    }
    return fields;
  }

  private JfrPath.KeyExpr parseKeyExpr() {
    // For v1: simple path only
    List<String> path = parsePathArg();
    return new JfrPath.PathKeyExpr(path);
  }

  private List<String> parsePathArg() {
    List<String> fp = new ArrayList<>();
    while (!eof()) {
      String seg = readIdent();
      if (seg.isEmpty()) break;
      fp.add(seg);
      // Accept both / and . as path separators for nested field access
      if (peek() == '/' || peek() == '.') {
        pos++;
        continue;
      }
      break;
    }
    if (fp.isEmpty()) throw error("Expected path");
    return fp;
  }

  private JfrPath.SelectOp parseSelect() {
    expect('(');
    skipWs();

    List<JfrPath.SelectItem> items = new ArrayList<>();
    while (!eof() && peek() != ')') {
      JfrPath.SelectItem item = parseSelectItem();
      items.add(item);
      skipWs();
      if (peek() == ',') {
        pos++;
        skipWs();
      }
    }
    expect(')');

    if (items.isEmpty()) {
      throw error("select() requires at least one field");
    }

    return new JfrPath.SelectOp(items);
  }

  private JfrPath.ToMapOp parseToMap() {
    expect('(');
    skipWs();

    // First argument: key field path
    List<String> keyField = parsePathArg();
    skipWs();

    // Require comma separator
    if (peek() != ',') {
      throw error("toMap() requires two arguments: toMap(keyField, valueField)");
    }
    pos++; // consume comma
    skipWs();

    // Second argument: value field path
    List<String> valueField = parsePathArg();
    skipWs();

    expect(')');

    return new JfrPath.ToMapOp(keyField, valueField);
  }

  private JfrPath.TimeRangeOp parseTimeRange() {
    List<String> valuePath = List.of();
    List<String> durationPath = List.of();
    String format = null;

    if (peek() == '(') {
      pos++;
      skipWs();

      // Parse optional arguments: path, duration=, and/or format=
      while (peek() != ')' && !eof()) {
        skipWs();
        if (startsWithIgnoreCase("format=")) {
          pos += 7;
          skipWs();
          Object lit = parseLiteral();
          format = lit == null ? null : String.valueOf(lit);
        } else if (startsWithIgnoreCase("duration=")) {
          pos += 9;
          skipWs();
          durationPath = parsePathArg();
        } else if (peek() != ')' && peek() != ',') {
          // Assume it's a path argument (startTime field)
          valuePath = parsePathArg();
        }
        skipWs();
        if (peek() == ',') {
          pos++;
          skipWs();
        }
      }
      expect(')');
    }

    return new JfrPath.TimeRangeOp(valuePath, durationPath, format);
  }

  private JfrPath.SelectItem parseSelectItem() {
    int start = pos;

    // Try to detect if this is an expression or simple field path
    boolean isExpression = detectExpression();

    if (isExpression) {
      // Parse full expression
      JfrPath.Expr expr = parseExpression();
      skipWs();

      // 'as' is required for expressions
      if (!matchKeyword("as")) {
        throw error("Expression selections require 'as alias' clause");
      }
      skipWs();

      String alias = readIdentifier();
      if (alias.isEmpty()) {
        throw error("Expected alias after 'as'");
      }

      return new JfrPath.ExpressionSelection(expr, alias);
    } else {
      // Parse as simple field path
      List<String> fieldPath = parsePathArg();
      skipWs();

      // 'as' is optional for simple fields
      String alias = null;
      if (matchKeyword("as")) {
        skipWs();
        alias = readIdentifier();
        if (alias.isEmpty()) {
          throw error("Expected alias after 'as'");
        }
      }

      return new JfrPath.FieldSelection(fieldPath, alias);
    }
  }

  private boolean detectExpression() {
    // Look ahead to detect expression patterns
    // Simpler approach: scan for expression indicators
    int saved = pos;
    try {
      skipWs();

      // Check for opening parenthesis (grouped expression)
      if (peek() == '(') {
        return true;
      }

      // Check for literal (number or string)
      char first = (char) peek();
      if (Character.isDigit(first) || first == '"' || first == '\'') {
        return true;
      }

      // Scan forward looking for expression indicators
      // Stop at comma or closing paren (end of select item)
      int depth = 0;
      while (!eof()) {
        char c = (char) peek();

        if (c == '(' && depth == 0) {
          // Check if this is a function call or grouped expression
          // Back up to see if preceded by identifier
          int checkPos = pos - 1;
          while (checkPos >= saved && Character.isWhitespace(input.charAt(checkPos))) {
            checkPos--;
          }
          if (checkPos >= saved
              && (Character.isLetterOrDigit(input.charAt(checkPos))
                  || input.charAt(checkPos) == '_')) {
            // Preceded by identifier - it's a function call
            return true;
          }
          // Parenthesis not preceded by identifier - grouped expression
          return true;
        }

        if (c == '(') depth++;
        if (c == ')') {
          depth--;
          if (depth < 0) break; // End of select item
        }
        if (c == ',' && depth == 0) break; // End of select item

        // Look for arithmetic operators (except division which might be path separator)
        if (depth == 0 && (c == '+' || c == '-' || c == '*')) {
          return true;
        }

        // Look for division: `/` followed by whitespace or digit indicates arithmetic
        if (depth == 0 && c == '/') {
          int nextPos = pos + 1;
          if (nextPos < input.length()) {
            char next = input.charAt(nextPos);
            if (Character.isWhitespace(next)
                || Character.isDigit(next)
                || next == '('
                || next == '"'
                || next == '\'') {
              return true; // Arithmetic division
            }
          }
        }

        pos++;
      }

      return false;
    } finally {
      pos = saved; // Reset position
    }
  }

  private JfrPath.Expr parseExpression() {
    return parseAdditiveExpr();
  }

  private JfrPath.Expr parseAdditiveExpr() {
    JfrPath.Expr left = parseMultiplicativeExpr();

    while (!eof()) {
      skipWs();
      JfrPath.Op op = null;
      if (peek() == '+') {
        op = JfrPath.Op.PLUS;
        pos++;
      } else if (peek() == '-') {
        op = JfrPath.Op.MINUS;
        pos++;
      } else {
        break;
      }

      skipWs();
      JfrPath.Expr right = parseMultiplicativeExpr();
      left = new JfrPath.BinExpr(op, left, right);
    }

    return left;
  }

  private JfrPath.Expr parseMultiplicativeExpr() {
    JfrPath.Expr left = parsePrimaryExpr();

    while (!eof()) {
      skipWs();
      JfrPath.Op op = null;
      if (peek() == '*') {
        op = JfrPath.Op.MULT;
        pos++;
      } else if (peek() == '/') {
        int saved = pos;
        pos++;
        skipWs();
        // Check it's not a path separator by looking if followed by letter (field name)
        // Path separators are followed by field names (letters), not numbers
        if (!Character.isLetter((char) peek())) {
          op = JfrPath.Op.DIV;
        } else {
          pos = saved; // It's a path separator, not division
          break;
        }
      } else {
        break;
      }

      if (op != null) {
        skipWs();
        JfrPath.Expr right = parsePrimaryExpr();
        left = new JfrPath.BinExpr(op, left, right);
      }
    }

    return left;
  }

  private JfrPath.Expr parsePrimaryExpr() {
    skipWs();

    // Parenthesized expression
    if (peek() == '(') {
      pos++;
      JfrPath.Expr expr = parseExpression();
      skipWs();
      expect(')');
      return expr;
    }

    // String literal or template
    if (peek() == '"' || peek() == '\'') {
      return parseStringLiteralOrTemplate();
    }

    // Number literal
    if (Character.isDigit((char) peek())
        || (peek() == '-' && Character.isDigit((char) peekAhead(1)))) {
      return new JfrPath.Literal(parseNumberLiteral());
    }

    // Function call or field reference
    int identifierStart = pos; // Save position before reading identifier
    String identifier = readIdentifier();
    if (identifier.isEmpty()) {
      throw error("Expected expression");
    }
    skipWs();

    if (peek() == '(') {
      // Function call
      return parseFunctionCall(identifier);
    } else {
      // Field reference - need to parse full path
      pos = identifierStart; // Reset to start of identifier
      List<String> fieldPath = parsePathArg();
      return new JfrPath.FieldRef(fieldPath);
    }
  }

  private JfrPath.FuncExpr parseFunctionCall(String funcName) {
    expect('(');
    List<JfrPath.Expr> args = new ArrayList<>();

    skipWs();
    while (!eof() && peek() != ')') {
      args.add(parseExpression());
      skipWs();
      if (peek() == ',') {
        pos++;
        skipWs();
      }
    }

    expect(')');
    return new JfrPath.FuncExpr(funcName, args);
  }

  private JfrPath.Expr parseStringLiteralOrTemplate() {
    char quote = (char) peek();
    int startPos = pos;

    // First pass: parse the string and check if it contains ${...}
    String rawString = parseStringLiteral();

    // Check if the string contains ${...} patterns
    boolean hasTemplates = rawString.contains("${");
    if (!hasTemplates) {
      // Simple string literal
      return new JfrPath.Literal(rawString);
    }

    // Parse as string template
    List<String> parts = new ArrayList<>();
    List<JfrPath.Expr> expressions = new ArrayList<>();

    StringBuilder currentPart = new StringBuilder();
    int i = 0;
    while (i < rawString.length()) {
      if (i < rawString.length() - 1
          && rawString.charAt(i) == '$'
          && rawString.charAt(i + 1) == '{') {
        // Found ${, save current part and parse expression
        parts.add(currentPart.toString());
        currentPart = new StringBuilder();

        // Find matching }
        i += 2; // Skip ${
        int exprStart = i;
        int braceCount = 1;
        while (i < rawString.length() && braceCount > 0) {
          if (rawString.charAt(i) == '{') {
            braceCount++;
          } else if (rawString.charAt(i) == '}') {
            braceCount--;
          }
          i++;
        }

        if (braceCount != 0) {
          throw error("Unclosed ${ in string template");
        }

        // Parse the expression inside ${...}
        String exprText = rawString.substring(exprStart, i - 1);
        JfrPath.Expr expr = parseEmbeddedExpression(exprText);
        expressions.add(expr);
      } else {
        currentPart.append(rawString.charAt(i));
        i++;
      }
    }

    // Add final part
    parts.add(currentPart.toString());

    if (expressions.isEmpty()) {
      // No expressions found, return as literal
      return new JfrPath.Literal(rawString);
    }

    return new JfrPath.StringTemplate(parts, expressions);
  }

  private JfrPath.Expr parseEmbeddedExpression(String exprText) {
    // Create a temporary parser for the embedded expression
    JfrPathParser exprParser = new JfrPathParser(exprText);
    return exprParser.parseExpression();
  }

  private String parseStringLiteral() {
    char quote = (char) peek();
    expect(quote);

    StringBuilder sb = new StringBuilder();
    while (!eof() && peek() != quote) {
      char c = (char) peek();
      pos++;
      if (c == '\\' && !eof()) {
        char next = (char) peek();
        pos++;
        switch (next) {
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          case '\\' -> sb.append('\\');
          case '"' -> sb.append('"');
          case '\'' -> sb.append('\'');
          default -> sb.append(next);
        }
      } else {
        sb.append(c);
      }
    }

    expect(quote);
    return sb.toString();
  }

  private Object parseNumberLiteral() {
    int start = pos;

    if (peek() == '-') {
      pos++;
    }

    boolean hasDecimal = false;
    while (!eof() && (Character.isDigit((char) peek()) || peek() == '.')) {
      if (peek() == '.') {
        if (hasDecimal) break; // Second decimal point, stop
        hasDecimal = true;
      }
      pos++;
    }

    String numStr = input.substring(start, pos);
    try {
      if (hasDecimal) {
        return Double.parseDouble(numStr);
      } else {
        return Long.parseLong(numStr);
      }
    } catch (NumberFormatException e) {
      throw error("Invalid number: " + numStr);
    }
  }

  private int peekAhead(int offset) {
    int idx = pos + offset;
    return (idx >= 0 && idx < input.length()) ? input.charAt(idx) : -1;
  }

  private boolean matchKeyword(String keyword) {
    int saved = pos;
    skipWs();
    String token = readIdentifier();
    if (keyword.equalsIgnoreCase(token)) {
      return true;
    }
    pos = saved;
    return false;
  }

  private String readIdentifier() {
    int start = pos;
    while (!eof() && (Character.isLetterOrDigit((char) peek()) || peek() == '_' || peek() == '-')) {
      pos++;
    }
    return input.substring(start, pos);
  }

  private void expect(char c) {
    if (eof() || input.charAt(pos) != c) throw error("Expected '" + c + "'");
    pos++;
  }

  private int peek() {
    return eof() ? -1 : input.charAt(pos);
  }

  private boolean eof() {
    return pos >= input.length();
  }

  private IllegalArgumentException error(String msg) {
    return new IllegalArgumentException(msg + " [at " + pos + "]");
  }
}

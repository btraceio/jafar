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
    List<String> segments = new ArrayList<>();
    List<Predicate> preds = new ArrayList<>();
    // Interleave segments and filters: a/b[c>0]/d[e=1]
    while (!eof()) {
      if (peek() == '|' || Character.isWhitespace((char) peek())) break;
      if (peek() != '[') {
        String seg = readUntil('/', '[', ' ', '|');
        if (!seg.isEmpty()) segments.add(seg);
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
    return new Query(root, segments, preds, pipeline);
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
    if (match("~")) op = Op.REGEX;
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
    if (match("~")) op = Op.REGEX;
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
      if (peek() == '/') {
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
      if (peek() == '(') {
        pos++;
        skipWs();
        // First arg is always the key path
        keyPath = parsePathArg();
        skipWs();
        // Parse optional agg= and value= parameters
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
          } else {
            throw error("groupBy() expects agg= or value= parameters");
          }
          skipWs();
        }
        expect(')');
      }
      return new JfrPath.GroupByOp(keyPath, aggFunc, aggValuePath);
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
    } else {
      throw error("Unknown pipeline function: " + name);
    }
  }

  private List<String> parsePathArg() {
    List<String> fp = new ArrayList<>();
    while (!eof()) {
      String seg = readIdent();
      if (seg.isEmpty()) break;
      fp.add(seg);
      if (peek() == '/') {
        pos++;
        continue;
      }
      break;
    }
    if (fp.isEmpty()) throw error("Expected path");
    return fp;
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

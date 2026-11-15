package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.Values;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Filter and predicate matching over event maps, including list match modes and deep path matching.
 */
final class MatchEngine {
  /** Bounded memoization of compiled per-event regexes; at capacity compiles fresh, no insert. */
  private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

  private static final int PATTERN_CACHE_MAX = 128;

  private static Pattern compiledPattern(String re, int flags) {
    String key = flags + ":" + re;
    Pattern cached = PATTERN_CACHE.get(key);
    if (cached != null) return cached;
    Pattern compiled = Pattern.compile(re, flags);
    if (PATTERN_CACHE.size() < PATTERN_CACHE_MAX) {
      PATTERN_CACHE.putIfAbsent(key, compiled);
    }
    return compiled;
  }

  static boolean matchesAll(
      Map<String, Object> map, List<Predicate> predicates, JfrPath.MatchMode defaultListMatchMode) {
    for (Predicate p : predicates) {
      if (p instanceof FieldPredicate fp) {
        if (!deepMatch(
            map,
            fp.fieldPath,
            0,
            fp.op,
            fp.literal,
            fp.matchMode != null ? fp.matchMode : defaultListMatchMode)) {
          return false;
        }
      } else if (p instanceof io.jafar.shell.jfrpath.JfrPath.ExprPredicate ep) {
        if (!evalBoolExpr(map, ep.expr)) return false;
      }
    }
    return true;
  }

  static boolean evalBoolExpr(
      Map<String, Object> root, io.jafar.shell.jfrpath.JfrPath.BoolExpr expr) {
    if (expr instanceof io.jafar.shell.jfrpath.JfrPath.CompExpr ce) {
      Object val = evalValueExpr(root, ce.lhs);
      // Unwrap simple types before comparison
      val = PathValues.unwrapSimpleType(val);
      return compare(val, ce.op, ce.literal);
    } else if (expr instanceof io.jafar.shell.jfrpath.JfrPath.FuncBoolExpr fb) {
      String n = fb.name.toLowerCase(java.util.Locale.ROOT);
      java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args = fb.args;
      switch (n) {
        case "contains" -> {
          ensureArgs(args, 2);
          Object s = resolveArg(root, args.get(0));
          String sub = String.valueOf(resolveArg(root, args.get(1)));
          return s != null && String.valueOf(s).contains(sub);
        }
        case "startswith" -> {
          ensureArgs(args, 2);
          Object s = resolveArg(root, args.get(0));
          String pre = String.valueOf(resolveArg(root, args.get(1)));
          return s != null && String.valueOf(s).startsWith(pre);
        }
        case "endswith" -> {
          ensureArgs(args, 2);
          Object s = resolveArg(root, args.get(0));
          String suf = String.valueOf(resolveArg(root, args.get(1)));
          return s != null && String.valueOf(s).endsWith(suf);
        }
        case "matches" -> {
          ensureArgsMin(args, 2);
          Object s = resolveArg(root, args.get(0));
          String re = String.valueOf(resolveArg(root, args.get(1)));
          int flags = 0;
          if (args.size() >= 3
              && "i".equalsIgnoreCase(String.valueOf(resolveArg(root, args.get(2)))))
            flags = java.util.regex.Pattern.CASE_INSENSITIVE;
          return s != null && compiledPattern(re, flags).matcher(String.valueOf(s)).find();
        }
        case "exists" -> {
          ensureArgs(args, 1);
          return resolveArg(root, args.get(0)) != null;
        }
        case "empty" -> {
          ensureArgs(args, 1);
          Object v = resolveArg(root, args.get(0));
          if (v == null) return true;
          if (v instanceof CharSequence cs) return cs.length() == 0;
          Object arr = PathValues.unwrapArrayLike(v);
          if (arr != null) return java.lang.reflect.Array.getLength(arr) == 0;
          return false;
        }
        case "between" -> {
          ensureArgs(args, 3);
          Object v = resolveArg(root, args.get(0));
          if (!(v instanceof Number betweenNum)) return false;
          Object loArg = resolveArg(root, args.get(1));
          Object hiArg = resolveArg(root, args.get(2));
          if (loArg instanceof String && hiArg instanceof String) {
            long x = betweenNum.longValue();
            return x >= TimeFormats.parseEpochNanos(String.valueOf(loArg))
                && x <= TimeFormats.parseEpochNanos(String.valueOf(hiArg));
          }
          double x = betweenNum.doubleValue();
          return x >= Coerce.toDouble(loArg) && x <= Coerce.toDouble(hiArg);
        }
        case "before" -> {
          ensureArgs(args, 2);
          Object beforeVal = resolveArg(root, args.get(0));
          if (!(beforeVal instanceof Number beforeNum)) return false;
          Object beforeArg = resolveArg(root, args.get(1));
          long beforeThreshold =
              beforeArg instanceof Number tn
                  ? tn.longValue()
                  : TimeFormats.parseEpochNanos(String.valueOf(beforeArg));
          return beforeNum.longValue() < beforeThreshold;
        }
        case "after" -> {
          ensureArgs(args, 2);
          Object afterVal = resolveArg(root, args.get(0));
          if (!(afterVal instanceof Number afterNum)) return false;
          Object afterArg = resolveArg(root, args.get(1));
          long afterThreshold =
              afterArg instanceof Number tn
                  ? tn.longValue()
                  : TimeFormats.parseEpochNanos(String.valueOf(afterArg));
          return afterNum.longValue() > afterThreshold;
        }
        case "on" -> {
          ensureArgs(args, 2);
          Object onVal = resolveArg(root, args.get(0));
          if (!(onVal instanceof Number onNum)) return false;
          long epochNanos = onNum.longValue();
          String dateStr = String.valueOf(resolveArg(root, args.get(1)));
          LocalDate date;
          try {
            date = LocalDate.parse(dateStr);
          } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "on() requires date in yyyy-MM-dd format: " + dateStr);
          }
          long startOfDay =
              date.atStartOfDay(TimeFormats.SYSTEM_ZONE).toInstant().getEpochSecond()
                  * 1_000_000_000L;
          long endOfDay =
              date.plusDays(1).atStartOfDay(TimeFormats.SYSTEM_ZONE).toInstant().getEpochSecond()
                  * 1_000_000_000L;
          return epochNanos >= startOfDay && epochNanos < endOfDay;
        }
        default -> throw new IllegalArgumentException("Unknown function in filter: " + fb.name);
      }
    } else if (expr instanceof io.jafar.shell.jfrpath.JfrPath.LogicalExpr le) {
      boolean l = evalBoolExpr(root, le.left);
      if (le.op == io.jafar.shell.jfrpath.JfrPath.LogicalExpr.Lop.AND)
        return l && evalBoolExpr(root, le.right);
      else return l || evalBoolExpr(root, le.right);
    } else if (expr instanceof io.jafar.shell.jfrpath.JfrPath.NotExpr ne) {
      return !evalBoolExpr(root, ne.inner);
    }
    return false;
  }

  private static void ensureArgs(java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args, int n) {
    if (args.size() != n) throw new IllegalArgumentException("Function expects " + n + " args");
  }

  private static void ensureArgsMin(
      java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args, int n) {
    if (args.size() < n)
      throw new IllegalArgumentException("Function expects at least " + n + " args");
  }

  static Object evalValueExpr(
      Map<String, Object> root, io.jafar.shell.jfrpath.JfrPath.ValueExpr vexpr) {
    if (vexpr instanceof io.jafar.shell.jfrpath.JfrPath.PathRef pr) {
      return PathValues.resolveSingle(root, pr.path);
    } else if (vexpr instanceof io.jafar.shell.jfrpath.JfrPath.FuncValueExpr fv) {
      String n = fv.name.toLowerCase(java.util.Locale.ROOT);
      java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args = fv.args;
      switch (n) {
        case "len" -> {
          ensureArgs(args, 1);
          Object v = resolveArg(root, args.get(0));
          Integer L = PathValues.valueLength(v);
          return L;
        }
        default -> throw new IllegalArgumentException("Unknown value function: " + fv.name);
      }
    }
    return null;
  }

  static Object resolveArg(Map<String, Object> root, io.jafar.shell.jfrpath.JfrPath.Arg arg) {
    if (arg instanceof io.jafar.shell.jfrpath.JfrPath.LiteralArg la) return la.value;
    if (arg instanceof io.jafar.shell.jfrpath.JfrPath.PathArg pa)
      return PathValues.resolveSingle(root, pa.path);
    return null;
  }

  @SuppressWarnings("unchecked")
  static boolean deepMatch(
      Object current, List<String> path, int idx, Op op, Object lit, JfrPath.MatchMode mode) {
    if (current instanceof ComplexType ct) {
      current = ct.getValue();
    }
    if (current instanceof ArrayType at) {
      current = at.getArray();
    }
    if (idx >= path.size()) {
      // At leaf; unwrap simple types (single-value maps) and apply comparison
      if (System.getProperty("jfr.shell.query.debug") != null) {
        System.err.println("[DEBUG] deepMatch() at leaf");
        System.err.println(
            "[DEBUG]   before unwrap: "
                + current
                + " (type: "
                + (current != null ? current.getClass().getName() : "null")
                + ")");
      }
      current = PathValues.unwrapSimpleType(current);
      if (System.getProperty("jfr.shell.query.debug") != null) {
        System.err.println(
            "[DEBUG]   after unwrap: "
                + current
                + " (type: "
                + (current != null ? current.getClass().getName() : "null")
                + ")");
      }
      return compare(current, op, lit);
    }
    String seg = path.get(idx);
    // Parse possible index/slice syntax in segment
    String segName = seg;
    Integer segIndex = null;
    int b = seg.indexOf('[');
    int e = seg.endsWith("]") ? seg.lastIndexOf(']') : -1;
    Integer sliceFrom = null, sliceTo = null;
    if (b >= 0 && e > b) {
      segName = seg.substring(0, b);
      String inside = seg.substring(b + 1, e);
      int c = inside.indexOf(':');
      if (c >= 0) {
        try {
          String a = inside.substring(0, c).trim();
          String bb = inside.substring(c + 1).trim();
          sliceFrom = a.isEmpty() ? 0 : Integer.parseInt(a);
          sliceTo = bb.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(bb);
        } catch (Exception ignore) {
        }
      } else {
        try {
          segIndex = Integer.parseInt(inside.trim());
        } catch (Exception ignore) {
        }
      }
    }
    // If current is a map-like structure
    if (current instanceof java.util.Map<?, ?> m) {
      Object next = ((java.util.Map<String, Object>) m).get(segName);
      if (segIndex != null) {
        Object arr = PathValues.unwrapArrayLike(next);
        if (arr == null) return false;
        int len = java.lang.reflect.Array.getLength(arr);
        if (segIndex < 0 || segIndex >= len) return false;
        next = java.lang.reflect.Array.get(arr, segIndex);
      } else if (sliceFrom != null) {
        Object arr = PathValues.unwrapArrayLike(next);
        if (arr == null) return false;
        int len = java.lang.reflect.Array.getLength(arr);
        int from = Math.max(0, sliceFrom);
        int to = Math.min(len, sliceTo == null ? len : sliceTo);
        int matches = 0, total = Math.max(0, to - from);
        for (int i = from; i < to; i++) {
          Object el = java.lang.reflect.Array.get(arr, i);
          if (deepMatch(el, path, idx + 1, op, lit, mode)) matches++;
        }
        return applyMode(matches, total, mode);
      }
      return deepMatch(next, path, idx + 1, op, lit, mode);
    }
    // If current is an array/list, apply match mode across elements without consuming seg
    if (current != null && current.getClass().isArray()) {
      int len = java.lang.reflect.Array.getLength(current);
      int matches = 0;
      for (int i = 0; i < len; i++) {
        Object el = java.lang.reflect.Array.get(current, i);
        if (deepMatch(el, path, idx, op, lit, mode)) matches++;
      }
      return applyMode(matches, len, mode);
    }
    if (current instanceof java.util.Collection<?> coll) {
      int len = 0, matches = 0;
      for (Object el : coll) {
        len++;
        if (deepMatch(el, path, idx, op, lit, mode)) matches++;
      }
      return applyMode(matches, len, mode);
    }
    // No further navigation possible
    return false;
  }

  static boolean applyMode(int matches, int total, JfrPath.MatchMode mode) {
    return switch (mode) {
      case ANY -> matches > 0;
      case ALL -> total > 0 && matches == total;
      case NONE -> matches == 0;
    };
  }

  static boolean compare(Object actual, Op op, Object lit) {
    if (actual == null) return false;

    if (System.getProperty("jfr.shell.query.debug") != null) {
      System.err.println("[DEBUG] compare()");
      System.err.println(
          "[DEBUG]   actual: " + actual + " (type: " + actual.getClass().getName() + ")");
      System.err.println("[DEBUG]   op: " + op);
      System.err.println("[DEBUG]   lit: " + lit);
    }

    boolean result =
        switch (op) {
          case EQ -> Objects.equals(Coerce.coerceForEquality(actual, lit), lit);
          case NE -> !Objects.equals(Coerce.coerceForEquality(actual, lit), lit);
          case GT -> Coerce.compareNumeric(actual, lit) > 0;
          case GE -> Coerce.compareNumeric(actual, lit) >= 0;
          case LT -> Coerce.compareNumeric(actual, lit) < 0;
          case LE -> Coerce.compareNumeric(actual, lit) <= 0;
          case REGEX -> {
            String actualStr = String.valueOf(actual);
            String pattern = String.valueOf(lit);
            boolean matches = compiledPattern(pattern, 0).matcher(actualStr).matches();
            if (System.getProperty("jfr.shell.query.debug") != null) {
              System.err.println(
                  "[DEBUG]   REGEX: \""
                      + actualStr
                      + "\" matches \""
                      + pattern
                      + "\" = "
                      + matches);
            }
            yield matches;
          }
          case PLUS, MINUS, MULT, DIV ->
              throw new IllegalArgumentException(
                  "Arithmetic operators not supported in comparisons");
        };

    if (System.getProperty("jfr.shell.query.debug") != null) {
      System.err.println("[DEBUG]   result: " + result);
    }

    return result;
  }

  static boolean matchesPredicate(Map<String, Object> row, JfrPath.Predicate predicate) {
    if (predicate instanceof JfrPath.FieldPredicate fp) {
      // For pipeline filter, field path is typically a single column name
      Object val = Values.get(row, fp.fieldPath.toArray());
      return matchOp(val, fp.op, fp.literal);
    } else if (predicate instanceof JfrPath.ExprPredicate ep) {
      return matchBoolExpr(row, ep.expr);
    }
    return false;
  }

  static boolean matchBoolExpr(Map<String, Object> row, JfrPath.BoolExpr expr) {
    if (expr instanceof JfrPath.CompExpr ce) {
      Object val;
      if (ce.lhs instanceof JfrPath.PathRef pr) {
        val = Values.get(row, pr.path.toArray());
      } else if (ce.lhs instanceof JfrPath.FuncValueExpr fve) {
        // Evaluate function on row - simplified: just use the first path arg
        val = null;
        for (JfrPath.Arg arg : fve.args) {
          if (arg instanceof JfrPath.PathArg pa) {
            val = Values.get(row, pa.path.toArray());
            break;
          }
        }
      } else {
        val = null;
      }
      return matchOp(val, ce.op, ce.literal);
    } else if (expr instanceof JfrPath.LogicalExpr le) {
      boolean left = matchBoolExpr(row, le.left);
      boolean right = matchBoolExpr(row, le.right);
      return le.op == JfrPath.LogicalExpr.Lop.AND ? left && right : left || right;
    } else if (expr instanceof JfrPath.NotExpr ne) {
      return !matchBoolExpr(row, ne.inner);
    }
    return false;
  }

  /**
   * Per-event predicate evaluation for live streaming ({@code tail}): evaluates the query's event
   * type membership and its bracket predicates against ONE event map — the same predicate semantics
   * {@code consume} applies per event over a file. Pipeline operators are NOT evaluated here; see
   * {@code RowOps.applyEventOps} for the per-event subset.
   */
  static boolean matchesEvent(
      Query query,
      String typeName,
      Map<String, Object> event,
      JfrPath.MatchMode defaultListMatchMode) {
    if (query.root != Root.EVENTS) {
      throw new UnsupportedOperationException("per-event matching only supports 'events/' queries");
    }
    if (!matchesType(query, typeName)) return false;
    return matchesAll(event, query.predicates, defaultListMatchMode);
  }

  /**
   * Shared event-type membership rule for per-event matching: empty (or absent) eventTypes match
   * all types; a multi-type query matches when the type name is listed; a single-type query matches
   * on exact equality. Uses {@code List.contains} over the immutable query list — no per-event
   * allocation on the live tail hot path.
   */
  static boolean matchesType(Query query, String typeName) {
    if (query.eventTypes == null || query.eventTypes.isEmpty()) return true;
    // Immutable query lists reject contains(null); a null type name can only match the
    // match-all (empty list) case above.
    if (typeName == null) return false;
    if (query.isMultiType) return query.eventTypes.contains(typeName);
    return query.eventTypes.get(0).equals(typeName);
  }

  static boolean matchOp(Object val, JfrPath.Op op, Object literal) {
    if (literal == null) {
      // Null-literal predicates are presence checks: 'field == null' matches a missing/null
      // field, 'field != null' matches a present one. Other operators against null are
      // meaningless and match nothing.
      return switch (op) {
        case EQ -> val == null;
        case NE -> val != null;
        default -> false;
      };
    }
    if (val == null) return false;
    int cmp = Aggregations.compareValues(val, literal);
    return switch (op) {
      case EQ -> cmp == 0;
      case NE -> cmp != 0;
      case GT -> cmp > 0;
      case GE -> cmp >= 0;
      case LT -> cmp < 0;
      case LE -> cmp <= 0;
      case REGEX ->
          compiledPattern(String.valueOf(literal), 0).matcher(String.valueOf(val)).matches();
      default -> false;
    };
  }
}

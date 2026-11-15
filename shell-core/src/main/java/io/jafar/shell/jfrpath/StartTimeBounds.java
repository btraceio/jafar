package io.jafar.shell.jfrpath;

import io.jafar.parser.TimeRange;
import java.util.List;
import java.util.Locale;

/**
 * The range of {@code startTime} values a query's filter can accept, for the parser to skip chunks
 * by ({@link io.jafar.parser.api.UntypedJafarParser#handle(io.jafar.parser.TypeFilter, TimeRange,
 * io.jafar.parser.api.UntypedJafarParser.EventHandler)}).
 *
 * <p>The range is only ever wider than the filter, never narrower: the filter is still evaluated on
 * every event that is read, so a range that is too wide costs time and one that is too narrow loses
 * rows. Whatever this class does not recognise therefore contributes no bound:
 *
 * <ul>
 *   <li>only the event's own {@code startTime} field counts, compared with a literal;
 *   <li>{@code a and b} narrows to both, {@code a or b} widens to cover both, {@code not} is
 *       ignored;
 *   <li>an argument that cannot be read as a time yields no bound, and the filter reports the
 *       problem as it always did, when it meets an event.
 * </ul>
 *
 * <p>Times are converted by the same {@link TimeFormats} methods {@link MatchEngine} uses.
 */
final class StartTimeBounds {
  private static final List<String> START_TIME = List.of("startTime");

  private StartTimeBounds() {}

  /** The start times that can satisfy all of {@code predicates}, which are combined with AND. */
  static TimeRange of(List<JfrPath.Predicate> predicates) {
    TimeRange range = TimeRange.all();
    for (JfrPath.Predicate predicate : predicates) {
      range = range.intersect(of(predicate));
    }
    return range;
  }

  private static TimeRange of(JfrPath.Predicate predicate) {
    try {
      if (predicate instanceof JfrPath.FieldPredicate fp) {
        return START_TIME.equals(fp.fieldPath) ? comparison(fp.op, fp.literal) : TimeRange.all();
      }
      if (predicate instanceof JfrPath.ExprPredicate ep) {
        return of(ep.expr);
      }
    } catch (RuntimeException unreadable) {
      // An argument the filter cannot read either; it says so when it evaluates an event.
    }
    return TimeRange.all();
  }

  private static TimeRange of(JfrPath.BoolExpr expr) {
    if (expr instanceof JfrPath.CompExpr ce) {
      return ce.lhs instanceof JfrPath.PathRef ref && START_TIME.equals(ref.path)
          ? comparison(ce.op, ce.literal)
          : TimeRange.all();
    }
    if (expr instanceof JfrPath.LogicalExpr le) {
      TimeRange left = ofGuarded(le.left);
      TimeRange right = ofGuarded(le.right);
      return le.op == JfrPath.LogicalExpr.Lop.AND ? left.intersect(right) : cover(left, right);
    }
    if (expr instanceof JfrPath.FuncBoolExpr fb) {
      return function(fb);
    }
    return TimeRange.all(); // NotExpr and anything added later
  }

  /** One side of {@code and}/{@code or}: unreadable on its own, without spoiling the other side. */
  private static TimeRange ofGuarded(JfrPath.BoolExpr expr) {
    try {
      return of(expr);
    } catch (RuntimeException unreadable) {
      return TimeRange.all();
    }
  }

  private static TimeRange cover(TimeRange a, TimeRange b) {
    return TimeRange.between(
        Math.min(a.fromEpochNanos(), b.fromEpochNanos()),
        Math.max(a.toEpochNanos(), b.toEpochNanos()));
  }

  /**
   * {@code startTime <op> literal}. The filter compares numbers as doubles, which near today's
   * epoch nanoseconds are 256 ns apart, so the bound is rounded outwards; a strict comparison
   * becomes an inclusive bound, which is wider.
   */
  private static TimeRange comparison(JfrPath.Op op, Object literal) {
    if (!(literal instanceof Number n)) {
      return TimeRange.all();
    }
    long floor = floor(n);
    long ceil = ceil(n);
    return switch (op) {
      case GT, GE -> TimeRange.since(floor);
      case LT, LE -> TimeRange.until(ceil);
      case EQ -> TimeRange.between(floor, ceil);
      default -> TimeRange.all();
    };
  }

  private static TimeRange function(JfrPath.FuncBoolExpr fb) {
    List<JfrPath.Arg> args = fb.args;
    if (args.isEmpty()
        || !(args.get(0) instanceof JfrPath.PathArg field)
        || !START_TIME.equals(field.path)) {
      return TimeRange.all();
    }
    for (int i = 1; i < args.size(); i++) {
      if (!(args.get(i) instanceof JfrPath.LiteralArg)) {
        return TimeRange.all(); // compared with another field of the event: no fixed bound
      }
    }
    switch (fb.name.toLowerCase(Locale.ROOT)) {
      case "after":
        return args.size() == 2
            ? TimeRange.since(TimeFormats.timeArgument(literal(args, 1)))
            : all();
      case "before":
        return args.size() == 2
            ? TimeRange.until(TimeFormats.timeArgument(literal(args, 1)))
            : all();
      case "on":
        if (args.size() != 2) {
          return all();
        }
        long[] day = TimeFormats.dayBounds(String.valueOf(literal(args, 1)));
        return TimeRange.between(day[0], day[1]);
      case "between":
        if (args.size() != 3) {
          return all();
        }
        Object lo = literal(args, 1);
        Object hi = literal(args, 2);
        if (lo instanceof String && hi instanceof String) {
          return TimeRange.between(
              TimeFormats.parseEpochNanos((String) lo), TimeFormats.parseEpochNanos((String) hi));
        }
        if (lo instanceof Number l && hi instanceof Number h) {
          return TimeRange.between(floor(l), ceil(h));
        }
        return all(); // mixed: the filter coerces both to doubles in its own way
      default:
        return all();
    }
  }

  private static TimeRange all() {
    return TimeRange.all();
  }

  private static Object literal(List<JfrPath.Arg> args, int index) {
    return ((JfrPath.LiteralArg) args.get(index)).value;
  }

  private static long floor(Number n) {
    return isIntegral(n) ? n.longValue() : (long) Math.floor(n.doubleValue());
  }

  private static long ceil(Number n) {
    return isIntegral(n) ? n.longValue() : (long) Math.ceil(n.doubleValue());
  }

  private static boolean isIntegral(Number n) {
    return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
  }
}

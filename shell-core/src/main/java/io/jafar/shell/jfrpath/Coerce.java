package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

/** Numeric and boolean coercion shared by expression evaluation, matching and decorators. */
final class Coerce {
  static double toNumber(Object obj) {
    if (obj == null) return 0.0;
    if (obj instanceof Number n) return n.doubleValue();
    if (obj instanceof String s) {
      try {
        return Double.parseDouble(s);
      } catch (NumberFormatException e) {
        return 0.0;
      }
    }
    return 0.0;
  }

  static boolean toBoolean(Object obj) {
    if (obj == null) return false;
    if (obj instanceof Boolean b) return b;
    if (obj instanceof Number n) return n.doubleValue() != 0;
    if (obj instanceof String s) return !s.isEmpty();
    return true;
  }

  static double toDouble(Object o) {
    if (o instanceof Number) return ((Number) o).doubleValue();
    try {
      return Double.parseDouble(String.valueOf(o));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("expected a number, got: " + o, e);
    }
  }

  static long toLong(Object obj) {
    if (obj instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(String.valueOf(obj));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("expected a number, got: " + obj, e);
    }
  }

  // --- consolidated comparison/coercion helpers (verbatim moves, distinct semantics) ---

  /**
   * Strict numeric comparison, moved verbatim from {@code MatchEngine.compareNum}: parses
   * non-Numbers and throws a raw {@link NumberFormatException} on unparseable input. MUST NOT be
   * unified with the lenient {@link #toNumber} arithmetic fallback.
   */
  static int compareNumeric(Object a, Object b) {
    double da =
        (a instanceof Number) ? ((Number) a).doubleValue() : Double.parseDouble(String.valueOf(a));
    double db =
        (b instanceof Number) ? ((Number) b).doubleValue() : Double.parseDouble(String.valueOf(b));
    return Double.compare(da, db);
  }

  /**
   * EQ/NE coercion, moved verbatim from {@code MatchEngine.coerce}: parses the actual value to a
   * number when the literal is one, falling back to the raw value so {@code Objects.equals} can
   * decide.
   */
  static Object coerceForEquality(Object actual, Object lit) {
    if (lit instanceof Number) {
      if (actual instanceof Number) return actual;
      try {
        return Double.parseDouble(String.valueOf(actual));
      } catch (Exception ignore) {
      }
    }
    if (lit instanceof String) return String.valueOf(actual);
    return actual;
  }

  /**
   * Null-aware lenient ordering, moved verbatim from {@code Aggregations.compareValues}: null <
   * non-null, Number×Number compares numerically, everything else falls back to string comparison —
   * never throws.
   */
  static int compareValues(Object a, Object b) {
    if (a == null && b == null) return 0;
    if (a == null) return -1;
    if (b == null) return 1;
    if (a instanceof Number na && b instanceof Number nb) {
      return Double.compare(na.doubleValue(), nb.doubleValue());
    }
    return String.valueOf(a).compareTo(String.valueOf(b));
  }
}

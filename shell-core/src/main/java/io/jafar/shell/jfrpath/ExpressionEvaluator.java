package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

/** Evaluates select() computed expressions: binary ops, functions and string templates. */
final class ExpressionEvaluator {
  // Expression evaluator for computed fields in select()

  /**
   * Renders an expression back to query syntax, for error messages (e.g. groupBy's unmatched-key
   * complaint) — the one place a key expression must be nameable.
   */
  static String describe(JfrPath.Expr expr) {
    if (expr instanceof JfrPath.Literal lit) {
      return lit.value instanceof String s ? "\"" + s + "\"" : String.valueOf(lit.value);
    }
    if (expr instanceof JfrPath.FieldRef fr) {
      return String.join("/", fr.fieldPath);
    }
    if (expr instanceof JfrPath.BinExpr bin) {
      return describe(bin.left) + " " + opSymbol(bin.op) + " " + describe(bin.right);
    }
    if (expr instanceof JfrPath.FuncExpr func) {
      StringBuilder sb = new StringBuilder(func.funcName).append('(');
      for (int i = 0; i < func.args.size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(describe(func.args.get(i)));
      }
      return sb.append(')').toString();
    }
    if (expr instanceof JfrPath.StringTemplate template) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < template.expressions.size(); i++) {
        sb.append(template.parts.get(i))
            .append("${")
            .append(describe(template.expressions.get(i)))
            .append('}');
      }
      return sb.append(template.parts.get(template.parts.size() - 1)).toString();
    }
    return String.valueOf(expr);
  }

  private static String opSymbol(JfrPath.Op op) {
    return switch (op) {
      case PLUS -> "+";
      case MINUS -> "-";
      case MULT -> "*";
      case DIV -> "/";
      default -> op.name().toLowerCase(java.util.Locale.ROOT);
    };
  }

  static Object evaluateExpression(JfrPath.Expr expr, Map<String, Object> row) {
    if (expr instanceof JfrPath.Literal lit) {
      return lit.value;
    }

    if (expr instanceof JfrPath.FieldRef fieldRef) {
      return PathValues.resolveSingle(row, fieldRef.fieldPath);
    }

    if (expr instanceof JfrPath.BinExpr binExpr) {
      Object left = evaluateExpression(binExpr.left, row);
      Object right = evaluateExpression(binExpr.right, row);
      return evaluateBinaryOp(binExpr.op, left, right);
    }

    if (expr instanceof JfrPath.FuncExpr funcExpr) {
      return evaluateFunction(funcExpr.funcName, funcExpr.args, row);
    }

    if (expr instanceof JfrPath.StringTemplate template) {
      return evaluateStringTemplate(template, row);
    }

    throw new IllegalArgumentException("Unknown expression type: " + expr.getClass());
  }

  static String evaluateStringTemplate(JfrPath.StringTemplate template, Map<String, Object> row) {
    StringBuilder result = new StringBuilder();

    // Invariant: parts.size() == expressions.size() + 1
    // Interleave parts and evaluated expressions
    for (int i = 0; i < template.expressions.size(); i++) {
      result.append(template.parts.get(i));
      Object value = evaluateExpression(template.expressions.get(i), row);
      result.append(value == null ? "" : String.valueOf(value));
    }
    // Append final part
    result.append(template.parts.get(template.parts.size() - 1));

    return result.toString();
  }

  static Object evaluateBinaryOp(JfrPath.Op op, Object left, Object right) {
    switch (op) {
      case PLUS:
        // String concatenation or numeric addition
        if (left instanceof String || right instanceof String) {
          return String.valueOf(left) + String.valueOf(right);
        }
        return addNumbers(left, right);

      case MINUS:
        return subtractNumbers(left, right);

      case MULT:
        return multiplyNumbers(left, right);

      case DIV:
        return divideNumbers(left, right);

      default:
        throw new IllegalArgumentException("Unsupported binary operator in expression: " + op);
    }
  }

  static double addNumbers(Object a, Object b) {
    return Coerce.toNumber(a) + Coerce.toNumber(b);
  }

  static double subtractNumbers(Object a, Object b) {
    return Coerce.toNumber(a) - Coerce.toNumber(b);
  }

  static double multiplyNumbers(Object a, Object b) {
    return Coerce.toNumber(a) * Coerce.toNumber(b);
  }

  static double divideNumbers(Object a, Object b) {
    double divisor = Coerce.toNumber(b);
    if (divisor == 0) {
      return Double.NaN;
    }
    return Coerce.toNumber(a) / divisor;
  }

  static Object evaluateFunction(
      String funcName, List<JfrPath.Expr> args, Map<String, Object> row) {
    switch (funcName.toLowerCase()) {
      case "if":
        return evalIf(args, row);
      case "substring":
        return evalSubstring(args, row);
      case "upper":
        return evalUpper(args, row);
      case "lower":
        return evalLower(args, row);
      case "length":
        return evalLength(args, row);
      case "coalesce":
        return evalCoalesce(args, row);
      case "asdatetime":
        return evalAsDateTime(args, row);
      case "truncate":
        return evalTruncate(args, row);
      case "formatduration":
        return evalFormatDuration(args, row);
      default:
        throw new IllegalArgumentException("Unknown function: " + funcName);
    }
  }

  static Object evalIf(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() != 3) {
      throw new IllegalArgumentException(
          "if() requires 3 arguments: condition, trueValue, falseValue");
    }

    Object condition = evaluateExpression(args.get(0), row);
    boolean isTrue = Coerce.toBoolean(condition);

    return evaluateExpression(isTrue ? args.get(1) : args.get(2), row);
  }

  static Object evalSubstring(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() < 2 || args.size() > 3) {
      throw new IllegalArgumentException(
          "substring() requires 2-3 arguments: string, start, [length]");
    }

    String str = String.valueOf(evaluateExpression(args.get(0), row));
    Object startVal = evaluateExpression(args.get(1), row);
    if (!(startVal instanceof Number)) {
      throw new IllegalArgumentException("substring() start must be numeric, got: " + startVal);
    }
    int start = ((Number) startVal).intValue();
    if (start < 0 || start > str.length()) {
      throw new IllegalArgumentException(
          "substring() start must be within 0.." + str.length() + ", got: " + start);
    }

    if (args.size() == 3) {
      Object lengthVal = evaluateExpression(args.get(2), row);
      if (!(lengthVal instanceof Number)) {
        throw new IllegalArgumentException("substring() length must be numeric, got: " + lengthVal);
      }
      int length = ((Number) lengthVal).intValue();
      if (length < 0) {
        throw new IllegalArgumentException(
            "substring() length must not be negative, got: " + length);
      }
      // Over-long length silently clamps at end-of-string (established behaviour)
      return str.substring(start, Math.min(start + length, str.length()));
    } else {
      return str.substring(start);
    }
  }

  static Object evalUpper(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() != 1) {
      throw new IllegalArgumentException("upper() requires 1 argument");
    }
    return String.valueOf(evaluateExpression(args.get(0), row)).toUpperCase(java.util.Locale.ROOT);
  }

  static Object evalLower(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() != 1) {
      throw new IllegalArgumentException("lower() requires 1 argument");
    }
    return String.valueOf(evaluateExpression(args.get(0), row)).toLowerCase(java.util.Locale.ROOT);
  }

  static Object evalLength(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() != 1) {
      throw new IllegalArgumentException("length() requires 1 argument");
    }
    return String.valueOf(evaluateExpression(args.get(0), row)).length();
  }

  static Object evalCoalesce(List<JfrPath.Expr> args, Map<String, Object> row) {
    for (JfrPath.Expr arg : args) {
      Object value = evaluateExpression(arg, row);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  static Object evalAsDateTime(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.isEmpty() || args.size() > 2) {
      throw new IllegalArgumentException(
          "asDateTime() requires 1-2 arguments: epochNanos[, format]");
    }
    Object val = evaluateExpression(args.get(0), row);
    if (!(val instanceof Number n)) return null;
    String format = null;
    if (args.size() == 2) {
      Object fmt = evaluateExpression(args.get(1), row);
      format = fmt == null ? null : String.valueOf(fmt);
    }
    Instant instant = Instant.ofEpochSecond(0, n.longValue());
    DateTimeFormatter formatter;
    try {
      formatter = TimeFormats.getFormatter(format);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "asDateTime(): invalid format pattern '" + format + "': " + e.getMessage(), e);
    }
    return formatter.format(instant.atZone(TimeFormats.SYSTEM_ZONE));
  }

  static Object evalTruncate(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() != 2)
      throw new IllegalArgumentException("truncate() requires 2 arguments: epochNanos, unit");
    Object val = evaluateExpression(args.get(0), row);
    if (!(val instanceof Number n)) return null;
    String unit =
        String.valueOf(evaluateExpression(args.get(1), row)).toLowerCase(java.util.Locale.ROOT);
    ZonedDateTime zdt = Instant.ofEpochSecond(0, n.longValue()).atZone(TimeFormats.SYSTEM_ZONE);
    ZonedDateTime truncated =
        switch (unit) {
          case "second" -> zdt.truncatedTo(ChronoUnit.SECONDS);
          case "minute" -> zdt.truncatedTo(ChronoUnit.MINUTES);
          case "hour" -> zdt.truncatedTo(ChronoUnit.HOURS);
          case "day" -> zdt.truncatedTo(ChronoUnit.DAYS);
          case "week" ->
              zdt.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                  .truncatedTo(ChronoUnit.DAYS);
          case "month" -> zdt.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
          default -> throw new IllegalArgumentException("Unknown truncate unit: " + unit);
        };
    Instant result = truncated.toInstant();
    return result.getEpochSecond() * 1_000_000_000L + result.getNano();
  }

  static Object evalFormatDuration(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() != 1)
      throw new IllegalArgumentException("formatDuration() requires 1 argument: nanoseconds");
    Object val = evaluateExpression(args.get(0), row);
    if (!(val instanceof Number n)) return null;
    return TimeFormats.formatDuration(n.longValue());
  }
}

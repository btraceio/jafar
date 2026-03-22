package io.jafar.shell.core.expr;

import java.util.Map;

/** Binary arithmetic expression: left op right. */
public record BinaryExpr(ValueExpr left, ValueExpr.ArithOp op, ValueExpr right)
    implements ValueExpr {

  @Override
  public double evaluate(Map<String, Object> row) {
    double l = left.evaluate(row);
    double r = right.evaluate(row);
    return switch (op) {
      case ADD -> l + r;
      case SUB -> l - r;
      case MUL -> l * r;
      case DIV -> r == 0 ? Double.NaN : l / r;
    };
  }
}

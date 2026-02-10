package io.jafar.shell.core.expr;

import java.util.Map;

/** Binary arithmetic expression combining two sub-expressions. */
public record BinaryExpr(ValueExpr left, ValueExpr.ArithOp op, ValueExpr right)
    implements ValueExpr {

  @Override
  public double evaluate(Map<String, Object> row) {
    double l = left.evaluate(row);
    double r = right.evaluate(row);

    if (Double.isNaN(l) || Double.isNaN(r)) {
      return Double.NaN;
    }

    return switch (op) {
      case ADD -> l + r;
      case SUB -> l - r;
      case MUL -> l * r;
      case DIV -> r == 0 ? Double.NaN : l / r;
    };
  }

  @Override
  public String toString() {
    return "(" + left + " " + op.symbol() + " " + right + ")";
  }
}

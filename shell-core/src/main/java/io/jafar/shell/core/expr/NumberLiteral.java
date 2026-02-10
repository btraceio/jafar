package io.jafar.shell.core.expr;

import java.util.Map;

/** Numeric literal value. */
public record NumberLiteral(double value) implements ValueExpr {

  @Override
  public double evaluate(Map<String, Object> row) {
    return value;
  }

  @Override
  public String toString() {
    if (value == (long) value) {
      return String.valueOf((long) value);
    }
    return String.valueOf(value);
  }
}

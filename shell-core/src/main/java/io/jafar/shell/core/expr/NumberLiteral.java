package io.jafar.shell.core.expr;

import java.util.Map;

/** Numeric literal value. */
public record NumberLiteral(double value) implements ValueExpr {

  @Override
  public double evaluate(Map<String, Object> row) {
    return value;
  }
}

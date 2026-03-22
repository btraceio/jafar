package io.jafar.shell.core.expr;

import java.util.Map;

/** Reference to a named field. */
public record FieldRef(String field) implements ValueExpr {

  @Override
  public double evaluate(Map<String, Object> row) {
    Object val = row.get(field);
    if (val instanceof Number num) {
      return num.doubleValue();
    }
    return Double.NaN;
  }
}

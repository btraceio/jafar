package io.jafar.shell.core.expr;

import java.util.Map;

/** Reference to a field value in a row. */
public record FieldRef(String field) implements ValueExpr {

  @Override
  public double evaluate(Map<String, Object> row) {
    Object value = row.get(field);
    if (value instanceof Number num) {
      return num.doubleValue();
    }
    return Double.NaN;
  }

  @Override
  public String toString() {
    return field;
  }
}

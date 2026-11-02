package io.jafar.shell.core.scripting;

import java.util.List;
import java.util.Map;

/**
 * Reads a query-valued variable's result the way {@code ${var}}, {@code ${var.size}} and {@code
 * ${var[N].field}} expect, whatever module produced it.
 *
 * <p>The rules are those {@code jfr-shell}'s {@code LazyQueryValue.size()} and {@code extract()}
 * have always applied to a JfrPath result; they only look at the value, so they hold for any {@code
 * QueryEvaluator}'s rows.
 */
public final class LazyRows {
  private LazyRows() {}

  /** Row count: the list's size, or 1 for a non-list result. */
  public static int size(Object result) {
    return result instanceof List<?> list ? list.size() : 1;
  }

  /**
   * One value from the result.
   *
   * @param result what the lazy value's {@code get()} returned
   * @param index row index (0-based)
   * @param field field name, or {@code null} for the row's first field
   * @return the value, or {@code null} when the row or field does not exist
   */
  public static Object extract(Object result, int index, String field) {
    if (!(result instanceof List<?> list)) {
      return index == 0 ? result : null;
    }
    if (index >= list.size()) {
      return null;
    }
    Object row = list.get(index);
    if (!(row instanceof Map<?, ?> map)) {
      return row;
    }
    if (field == null) {
      return map.isEmpty() ? null : map.values().iterator().next();
    }
    return map.get(field);
  }
}

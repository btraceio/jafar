package io.jafar.shell.core;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Utility for sorting grouped query results. */
public final class RowSorter {

  private RowSorter() {}

  /**
   * Sorts groupBy results by key or value.
   *
   * @param rows the result rows to sort in place
   * @param sortBy "key" or "value"
   * @param keyField the field name used as the group key
   * @param valueField the field name used as the aggregated value
   * @param ascending true for ascending order, false for descending
   */
  @SuppressWarnings("unchecked")
  public static void sortGroupByResults(
      List<Map<String, Object>> rows,
      String sortBy,
      String keyField,
      String valueField,
      boolean ascending) {
    if (rows == null || rows.size() <= 1 || sortBy == null) {
      return;
    }

    String field = "key".equalsIgnoreCase(sortBy) ? keyField : valueField;

    Comparator<Map<String, Object>> cmp =
        (a, b) -> {
          Object va = a.get(field);
          Object vb = b.get(field);
          if (va == null && vb == null) return 0;
          if (va == null) return -1;
          if (vb == null) return 1;
          if (va instanceof Comparable ca && vb instanceof Comparable cb) {
            return ca.compareTo(cb);
          }
          return String.valueOf(va).compareTo(String.valueOf(vb));
        };

    if (!ascending) {
      cmp = cmp.reversed();
    }

    rows.sort(cmp);
  }
}

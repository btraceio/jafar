package io.jafar.shell.core;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Utility for sorting rows (List of Map) by field values.
 * Shared between JFR and heap dump evaluators.
 */
public final class RowSorter {

  private RowSorter() {}

  /**
   * Sort rows in place by a specific field.
   *
   * @param rows the rows to sort
   * @param field the field name to sort by
   * @param ascending true for ascending order, false for descending
   */
  public static void sortByField(List<Map<String, Object>> rows, String field, boolean ascending) {
    if (rows == null || rows.isEmpty()) return;

    Comparator<Map<String, Object>> comparator = (a, b) -> compareValues(a.get(field), b.get(field));
    if (!ascending) {
      comparator = comparator.reversed();
    }
    rows.sort(comparator);
  }

  /**
   * Sort groupBy results by key or aggregated value.
   *
   * @param results the grouped results to sort in place
   * @param sortBy "key" to sort by group key, "value" or aggregation name to sort by value
   * @param valueField the field name containing the aggregated value (used when sortBy is "value")
   * @param ascending true for ascending order, false for descending
   */
  public static void sortGroupByResults(
      List<Map<String, Object>> results,
      String sortBy,
      String valueField,
      boolean ascending) {
    sortGroupByResults(results, sortBy, "key", valueField, ascending);
  }

  /**
   * Sort groupBy results by key or aggregated value.
   *
   * @param results the grouped results to sort in place
   * @param sortBy "key" to sort by group key, "value" to sort by aggregated value
   * @param keyField the field name containing the group key (default "key")
   * @param valueField the field name containing the aggregated value
   * @param ascending true for ascending order, false for descending
   */
  public static void sortGroupByResults(
      List<Map<String, Object>> results,
      String sortBy,
      String keyField,
      String valueField,
      boolean ascending) {
    if (results == null || results.isEmpty() || sortBy == null) return;

    Comparator<Map<String, Object>> comparator;
    if ("key".equalsIgnoreCase(sortBy)) {
      comparator = (a, b) -> compareValues(a.get(keyField), b.get(keyField));
    } else {
      // Sort by the aggregated value field
      comparator = (a, b) -> compareValues(a.get(valueField), b.get(valueField));
    }

    if (!ascending) {
      comparator = comparator.reversed();
    }
    results.sort(comparator);
  }

  /**
   * Compare two values for sorting. Handles nulls, numbers, and comparable types.
   *
   * @param a first value
   * @param b second value
   * @return negative if a < b, positive if a > b, zero if equal
   */
  @SuppressWarnings("unchecked")
  public static int compareValues(Object a, Object b) {
    if (a == null && b == null) return 0;
    if (a == null) return -1;
    if (b == null) return 1;

    // Handle numbers specially for cross-type comparison
    if (a instanceof Number na && b instanceof Number nb) {
      return Double.compare(na.doubleValue(), nb.doubleValue());
    }

    // Try Comparable
    if (a instanceof Comparable && a.getClass().isInstance(b)) {
      return ((Comparable<Object>) a).compareTo(b);
    }

    // Fall back to string comparison
    return a.toString().compareTo(b.toString());
  }
}

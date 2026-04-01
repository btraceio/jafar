package io.jafar.shell.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Utility for formatting query results as ASCII tables. */
public final class TableFormatter {

  private TableFormatter() {}

  /**
   * Formats a list of result rows as an ASCII table.
   *
   * @param rows the result rows (each row is a {@code Map<String,Object>})
   * @param maxRows maximum number of rows to display
   * @return formatted table string
   */
  @SuppressWarnings("unchecked")
  public static String formatTable(List<?> rows, int maxRows) {
    if (rows == null || rows.isEmpty()) {
      return "(no results)\n";
    }

    // Handle scalar results
    if (!(rows.get(0) instanceof Map)) {
      StringBuilder sb = new StringBuilder();
      int count = 0;
      for (Object row : rows) {
        if (count >= maxRows) {
          sb.append("... (").append(rows.size() - maxRows).append(" more)\n");
          break;
        }
        sb.append(row).append('\n');
        count++;
      }
      return sb.toString();
    }

    List<Map<String, Object>> mapRows = (List<Map<String, Object>>) rows;

    // Collect columns preserving insertion order
    Set<String> colSet = new LinkedHashSet<>();
    for (Map<String, Object> row : mapRows) {
      colSet.addAll(row.keySet());
    }
    List<String> columns = new ArrayList<>(colSet);

    // Compute column widths
    int[] widths = new int[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      widths[i] = columns.get(i).length();
    }
    int displayRows = Math.min(mapRows.size(), maxRows);
    for (int r = 0; r < displayRows; r++) {
      Map<String, Object> row = mapRows.get(r);
      for (int c = 0; c < columns.size(); c++) {
        Object val = row.get(columns.get(c));
        int len = val == null ? 4 : String.valueOf(val).length();
        if (len > widths[c]) widths[c] = len;
      }
    }

    // Cap column width
    int maxColWidth = 60;
    for (int i = 0; i < widths.length; i++) {
      if (widths[i] > maxColWidth) widths[i] = maxColWidth;
    }

    StringBuilder sb = new StringBuilder();

    // Header
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) sb.append(" | ");
      sb.append(pad(columns.get(i), widths[i]));
    }
    sb.append('\n');

    // Separator
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) sb.append("-+-");
      sb.append("-".repeat(widths[i]));
    }
    sb.append('\n');

    // Rows
    for (int r = 0; r < displayRows; r++) {
      Map<String, Object> row = mapRows.get(r);
      for (int c = 0; c < columns.size(); c++) {
        if (c > 0) sb.append(" | ");
        Object val = row.get(columns.get(c));
        String str = val == null ? "null" : String.valueOf(val);
        if (str.length() > maxColWidth) {
          str = str.substring(0, maxColWidth - 3) + "...";
        }
        sb.append(pad(str, widths[c]));
      }
      sb.append('\n');
    }

    if (mapRows.size() > maxRows) {
      sb.append("... (").append(mapRows.size() - maxRows).append(" more rows)\n");
    }

    sb.append("(").append(mapRows.size()).append(" rows)\n");
    return sb.toString();
  }

  private static String pad(String s, int width) {
    if (s.length() >= width) return s;
    return s + " ".repeat(width - s.length());
  }
}

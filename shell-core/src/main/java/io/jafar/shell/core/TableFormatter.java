package io.jafar.shell.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats query results as simple column-aligned output (like Unix ps/ls). Provides better
 * readability than JSON and is scriptable (no box-drawing characters).
 */
public final class TableFormatter {

  private static final int MAX_CELL_WIDTH = 40;
  private static final int MAX_ROWS_DEFAULT = 100;
  private static final int MIN_COL_SPACING = 2;

  /**
   * Formats a list of maps as simple column-aligned output (Unix style).
   *
   * @param results list of row maps
   * @param maxRows maximum rows to display (remaining count shown)
   * @return formatted table string
   */
  public static String formatTable(List<?> results, int maxRows) {
    if (results.isEmpty()) {
      return "(no results)\n";
    }

    // Extract rows that are maps
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object obj : results) {
      if (obj instanceof Map<?, ?> map) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          row.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        rows.add(row);
      } else {
        // Not a map - fall back to simple list
        return formatSimpleList(results, maxRows);
      }
    }

    if (rows.isEmpty()) {
      return formatSimpleList(results, maxRows);
    }

    // Collect all columns from all rows
    List<String> allColumns = new ArrayList<>(rows.get(0).keySet());

    // Filter out columns that are empty or null in ALL rows
    List<String> columns = new ArrayList<>();
    for (String col : allColumns) {
      boolean hasValue = false;
      for (Map<String, Object> row : rows) {
        Object value = row.get(col);
        if (value != null && !isEmptyValue(value)) {
          hasValue = true;
          break;
        }
      }
      if (hasValue) {
        columns.add(col);
      }
    }

    if (columns.isEmpty()) {
      return "(no displayable columns)\n";
    }

    // Calculate column widths
    Map<String, Integer> widths = new LinkedHashMap<>();
    for (String col : columns) {
      widths.put(col, Math.min(col.length(), MAX_CELL_WIDTH));
    }

    // Update widths based on content
    int rowsToScan = Math.min(rows.size(), maxRows);
    for (int i = 0; i < rowsToScan; i++) {
      Map<String, Object> row = rows.get(i);
      for (String col : columns) {
        Object value = row.get(col);
        String strValue = formatValue(col, value);
        int currentWidth = widths.get(col);
        widths.put(col, Math.min(Math.max(currentWidth, strValue.length()), MAX_CELL_WIDTH));
      }
    }

    StringBuilder sb = new StringBuilder();

    // Header row
    for (int i = 0; i < columns.size(); i++) {
      String col = columns.get(i);
      sb.append(padRight(col, widths.get(col)));
      if (i < columns.size() - 1) {
        sb.append("  ");
      }
    }
    sb.append("\n");

    // Data rows
    int displayCount = Math.min(rows.size(), maxRows);
    for (int i = 0; i < displayCount; i++) {
      Map<String, Object> row = rows.get(i);
      for (int j = 0; j < columns.size(); j++) {
        String col = columns.get(j);
        Object value = row.get(col);
        String strValue = formatValue(col, value);
        sb.append(padRight(truncate(strValue, widths.get(col)), widths.get(col)));
        if (j < columns.size() - 1) {
          sb.append("  ");
        }
      }
      sb.append("\n");
    }

    // Summary
    if (rows.size() > maxRows) {
      sb.append(
          String.format(
              "\n(Showing first %d of %d rows)\n",
              maxRows, rows.size()));
      sb.append("Tip: Use '| top(N)' to limit, or redirect output to see all:\n");
      sb.append("  show ... > output.txt\n");
      sb.append("  show ... | less\n");
    }

    return sb.toString();
  }

  /**
   * Checks if a value is considered empty (null, empty string, etc.).
   *
   * @param value the value to check
   * @return true if empty
   */
  private static boolean isEmptyValue(Object value) {
    if (value == null) {
      return true;
    }
    if (value instanceof String str) {
      return str.isEmpty();
    }
    return false;
  }

  /**
   * Formats a list of non-map objects as a simple numbered list.
   *
   * @param results list of objects
   * @param maxRows maximum rows to display
   * @return formatted string
   */
  private static String formatSimpleList(List<?> results, int maxRows) {
    StringBuilder sb = new StringBuilder();
    int displayCount = Math.min(results.size(), maxRows);

    for (int i = 0; i < displayCount; i++) {
      sb.append(formatValue(results.get(i)));
      sb.append("\n");
    }

    if (results.size() > maxRows) {
      sb.append(
          String.format("\n(%d more rows, showing %d of %d)\n", results.size() - maxRows, maxRows, results.size()));
    }

    return sb.toString();
  }

  /**
   * Formats a value for display in a table cell, with context from column name.
   *
   * @param columnName the column name (used to detect memory values)
   * @param value the value to format
   * @return formatted string
   */
  private static String formatValue(String columnName, Object value) {
    // Check if this is a memory-related column
    if (isMemoryColumn(columnName) && value instanceof Number num) {
      return formatMemorySize(num.longValue());
    }
    return formatValue(value, 0);
  }

  /**
   * Formats a value for display in a table cell. Recursively unwraps single-entry maps to show
   * leaf values.
   *
   * @param value the value to format
   * @return formatted string
   */
  private static String formatValue(Object value) {
    return formatValue(value, 0);
  }

  /**
   * Internal formatter with depth tracking to prevent infinite recursion.
   *
   * @param value the value to format
   * @param depth current recursion depth
   * @return formatted string
   */
  private static String formatValue(Object value, int depth) {
    if (value == null) {
      return "";
    }
    if (value instanceof String str) {
      return str;
    }
    if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
      return value.toString();
    }

    // Prevent infinite recursion
    if (depth > 10) {
      return value.toString();
    }

    if (value instanceof Map<?, ?> map) {
      // Unwrap single-entry maps recursively
      if (map.size() == 1) {
        Object singleValue = map.values().iterator().next();
        return formatValue(singleValue, depth + 1);
      }

      // Show compact map representation for multi-entry maps
      if (map.isEmpty()) {
        return "{}";
      }

      // Show first few entries
      StringBuilder sb = new StringBuilder("{");
      int count = 0;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (count > 0) sb.append(", ");
        sb.append(entry.getKey()).append(":").append(formatValue(entry.getValue(), depth + 1));
        count++;
        if (count >= 2) {
          if (map.size() > 2) {
            sb.append(", +" + (map.size() - 2) + " more");
          }
          break;
        }
      }
      sb.append("}");
      return sb.toString();
    }

    if (value instanceof List<?> list) {
      // Unwrap single-element lists recursively
      if (list.size() == 1) {
        return formatValue(list.get(0), depth + 1);
      }

      if (list.isEmpty()) {
        return "[]";
      }

      // Show first few items
      StringBuilder sb = new StringBuilder("[");
      int count = 0;
      for (Object item : list) {
        if (count > 0) sb.append(", ");
        sb.append(formatValue(item, depth + 1));
        count++;
        if (count >= 2) {
          if (list.size() > 2) {
            sb.append(", +" + (list.size() - 2) + " more");
          }
          break;
        }
      }
      sb.append("]");
      return sb.toString();
    }

    // Default toString
    String str = value.toString();
    return str;
  }

  /**
   * Checks if a column name indicates memory values.
   *
   * @param columnName the column name to check
   * @return true if this column likely contains memory sizes
   */
  private static boolean isMemoryColumn(String columnName) {
    if (columnName == null) {
      return false;
    }
    String lower = columnName.toLowerCase();
    return lower.endsWith("size")
        || lower.contains("shallow")
        || lower.contains("retained")
        || lower.contains("memory")
        || lower.equals("size")
        || lower.equals("bytes");
  }

  /**
   * Formats a memory size in bytes to human-readable format.
   *
   * @param bytes the size in bytes
   * @return formatted string (e.g., "1.5 KB", "128.7 MB", "2.3 GB")
   */
  private static String formatMemorySize(long bytes) {
    if (bytes < 0) {
      return String.valueOf(bytes); // Negative values (e.g., -1 for uncomputed) shown as-is
    }

    if (bytes < 1024) {
      return bytes + " B";
    } else if (bytes < 1024 * 1024) {
      return String.format("%.1f KB", bytes / 1024.0);
    } else if (bytes < 1024 * 1024 * 1024) {
      return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    } else {
      return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
  }

  /**
   * Pads a string to the right with spaces.
   *
   * @param str the string to pad
   * @param width the target width
   * @return padded string
   */
  private static String padRight(String str, int width) {
    if (str.length() >= width) {
      return str;
    }
    return str + " ".repeat(width - str.length());
  }

  /**
   * Truncates a string to a maximum width, adding ellipsis if needed.
   *
   * @param str the string to truncate
   * @param maxWidth maximum width
   * @return truncated string
   */
  private static String truncate(String str, int maxWidth) {
    if (str.length() <= maxWidth) {
      return str;
    }
    return str.substring(0, maxWidth - 3) + "...";
  }

  /** Formats results with default max rows. */
  public static String formatTable(List<?> results) {
    return formatTable(results, MAX_ROWS_DEFAULT);
  }

  private TableFormatter() {}
}

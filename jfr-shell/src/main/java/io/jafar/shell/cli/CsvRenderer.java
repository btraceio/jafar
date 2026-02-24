package io.jafar.shell.cli;

import java.util.*;

/** CSV renderer for List&lt;Map&lt;String,Object&gt;&gt;. RFC 4180 compliant. */
public final class CsvRenderer {
  private CsvRenderer() {}

  public static void render(List<Map<String, Object>> rows, CommandDispatcher.IO io) {
    PagedPrinter pager = PagedPrinter.forIO(io);
    if (rows == null || rows.isEmpty()) {
      pager.println("(no rows)");
      return;
    }

    // Compute columns as union of keys, excluding complex-valued columns
    Set<String> cols = new LinkedHashSet<>();
    for (Map<String, Object> row : rows) {
      cols.addAll(row.keySet());
    }
    cols.removeIf(
        col ->
            rows.stream()
                .map(r -> r.get(col))
                .anyMatch(v -> v instanceof Map<?, ?> m && m.size() > 1));
    List<String> headers = new ArrayList<>(cols);

    // Print header row
    pager.println(toCsvLine(headers));

    // Print data rows
    for (Map<String, Object> row : rows) {
      List<String> values = new ArrayList<>(headers.size());
      for (String h : headers) {
        values.add(toCsvCell(row.get(h)));
      }
      pager.println(toCsvLine(values));
    }
  }

  public static void renderValues(List<?> values, CommandDispatcher.IO io) {
    PagedPrinter pager = PagedPrinter.forIO(io);
    if (values == null || values.isEmpty()) {
      pager.println("(no values)");
      return;
    }
    pager.println("value");
    for (Object v : values) {
      pager.println(escapeCsv(toCsvCell(v)));
    }
  }

  private static String toCsvLine(List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(escapeCsv(values.get(i)));
    }
    return sb.toString();
  }

  private static String toCsvCell(Object v) {
    if (v == null) return "";
    if (v instanceof long[] la) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < la.length; i++) {
        if (i > 0) sb.append(';');
        sb.append(la[i]);
      }
      return sb.toString();
    }
    if (v instanceof Map<?, ?>) return v.toString();
    if (v instanceof Collection<?> coll) {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (Object item : coll) {
        if (!first) sb.append(" | ");
        first = false;
        sb.append(String.valueOf(item));
      }
      return sb.toString();
    }
    if (v.getClass().isArray()) {
      if (v instanceof Object[] oa) return Arrays.deepToString(oa);
      // Primitive arrays: int[], double[], etc.
      int len = java.lang.reflect.Array.getLength(v);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < len; i++) {
        if (i > 0) sb.append(';');
        sb.append(java.lang.reflect.Array.get(v, i));
      }
      return sb.toString();
    }
    return String.valueOf(v);
  }

  private static String escapeCsv(String s) {
    if (s == null) return "";
    if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }
}

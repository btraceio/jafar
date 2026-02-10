package io.jafar.shell.core.render;

import io.jafar.shell.core.OutputWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Simple table renderer for List<Map<String,Object>>. */
public final class TableRenderer {
  private TableRenderer() {}

  /** Renders a list of maps as a table. */
  public static void render(List<Map<String, Object>> rows, OutputWriter out) {
    PagedPrinter pager = PagedPrinter.forOutput(out);
    if (rows == null || rows.isEmpty()) {
      pager.println("(no rows)");
      return;
    }
    // Compute columns as union of keys from the first N rows (larger sample for CP entries)
    Set<String> cols = new LinkedHashSet<>();
    int sample = Math.min(rows.size(), 200);
    for (int i = 0; i < sample; i++) cols.addAll(rows.get(i).keySet());
    // Heuristic: if very few columns detected and there are many rows, expand sample once
    if (cols.size() <= 2 && rows.size() > sample) {
      int sample2 = Math.min(rows.size(), 1000);
      for (int i = 0; i < sample2; i++) cols.addAll(rows.get(i).keySet());
    }
    List<String> headers = new ArrayList<>(cols);
    // Determine which columns should never truncate (special-case: "fields")
    boolean[] noTruncate = new boolean[headers.size()];
    for (int c = 0; c < headers.size(); c++) noTruncate[c] = "fields".equals(headers.get(c));

    // Prepare cell strings with potential multiline for special columns
    List<List<String[]>> prepared = new ArrayList<>(rows.size());
    // Compute widths
    int[] widths = new int[headers.size()];
    for (int c = 0; c < headers.size(); c++) widths[c] = headers.get(c).length();
    for (Map<String, Object> row : rows) {
      List<String[]> rowCells = new ArrayList<>(headers.size());
      for (int c = 0; c < headers.size(); c++) {
        String h = headers.get(c);
        Object val = row.get(h);
        String cell;
        if (noTruncate[c] && val instanceof List<?> list) {
          // Render list elements on separate lines for readability
          StringBuilder sb = new StringBuilder();
          for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(String.valueOf(list.get(i)));
          }
          cell = sb.toString();
        } else {
          cell = toCell(val);
        }
        String[] lines = cell.split("\\n", -1);
        rowCells.add(lines);
        for (String ln : lines) {
          int cap = noTruncate[c] ? ln.length() : Math.min(40, ln.length());
          widths[c] = Math.max(widths[c], cap);
        }
      }
      prepared.add(rowCells);
    }
    // Print header
    printSingleLine(headers, widths, pager);
    // Separator
    StringBuilder sep = new StringBuilder();
    for (int w : widths) {
      sep.append("+").append("-".repeat(w + 2));
    }
    sep.append("+");
    pager.println(sep.toString());
    // Rows with multiline support
    for (List<String[]> rowCells : prepared) {
      int maxLines = 1;
      for (String[] cellLines : rowCells) maxLines = Math.max(maxLines, cellLines.length);
      for (int line = 0; line < maxLines; line++) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < headers.size(); c++) {
          String[] cellLines = rowCells.get(c);
          String piece = line < cellLines.length ? cellLines[line] : "";
          String v = noTruncate[c] ? piece : truncate(piece, widths[c]);
          sb.append("| ").append(pad(v, widths[c])).append(" ");
        }
        sb.append("|");
        pager.println(sb.toString());
      }
    }
  }

  /** Renders a list of scalar values as a simple table. */
  public static void renderValues(List<?> values, OutputWriter out) {
    PagedPrinter pager = PagedPrinter.forOutput(out);
    if (values == null || values.isEmpty()) {
      pager.println("(no values)");
      return;
    }
    // Determine width
    int w = "value".length();
    for (Object v : values) w = Math.max(w, Math.min(80, toCell(v).length()));
    // Header
    printSingleLine(List.of("value"), new int[] {w}, pager);
    String sep = "+" + "-".repeat(w + 2) + "+";
    pager.println(sep);
    for (Object v : values) {
      printSingleLine(List.of(toCell(v)), new int[] {w}, pager);
    }
  }

  private static void printSingleLine(List<String> cols, int[] widths, PagedPrinter pager) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cols.size(); i++) {
      String v = truncate(cols.get(i), widths[i]);
      sb.append("| ").append(pad(v, widths[i])).append(" ");
    }
    sb.append("|");
    pager.println(sb.toString());
  }

  private static String toCell(Object v) {
    if (v == null) return "";
    if (v instanceof Map<?, ?> m) {
      // Unwrap simple type wrappers (single-entry maps like {string=value})
      if (m.size() == 1) {
        Object unwrapped = m.values().iterator().next();
        return toCell(unwrapped);
      }
      return m.toString();
    }
    if (v.getClass().isArray()) return Arrays.deepToString((Object[]) v);
    return String.valueOf(v);
  }

  private static String pad(String s, int w) {
    if (s.length() >= w) return s;
    return s + " ".repeat(w - s.length());
  }

  private static String truncate(String s, int w) {
    if (s.length() <= w) return s;
    if (w <= 1) return s.substring(0, w);
    return s.substring(0, w - 1) + "\u2026"; // ellipsis character
  }
}

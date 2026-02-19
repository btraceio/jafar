package io.jafar.shell.cli;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Table renderer using TamboUI widgets for styled inline output. Renders data into a TamboUI Table
 * widget, converts to ANSI strings, and outputs via the IO interface.
 */
public final class TuiTableRenderer {
  private static final int DEFAULT_WIDTH = 120;
  private static final int MAX_CELL_WIDTH = 40;
  private static final Style HEADER_STYLE = Style.EMPTY.bold().fg(Color.CYAN);

  private TuiTableRenderer() {}

  public static void render(List<Map<String, Object>> rows, CommandDispatcher.IO io) {
    PagedPrinter pager = PagedPrinter.forIO(io);
    if (rows == null || rows.isEmpty()) {
      pager.println("(no rows)");
      return;
    }

    // Compute columns as union of keys from sampled rows
    Set<String> cols = new LinkedHashSet<>();
    int sample = Math.min(rows.size(), 200);
    for (int i = 0; i < sample; i++) cols.addAll(rows.get(i).keySet());
    if (cols.size() <= 2 && rows.size() > sample) {
      int sample2 = Math.min(rows.size(), 1000);
      for (int i = 0; i < sample2; i++) cols.addAll(rows.get(i).keySet());
    }
    List<String> headers = new ArrayList<>(cols);

    // Build header row
    Cell[] headerCells = new Cell[headers.size()];
    for (int i = 0; i < headers.size(); i++) {
      headerCells[i] = Cell.from(headers.get(i)).style(HEADER_STYLE);
    }
    Row headerRow = Row.from(headerCells);

    // Build data rows
    List<Row> dataRows = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      Cell[] cells = new Cell[headers.size()];
      for (int c = 0; c < headers.size(); c++) {
        cells[c] = Cell.from(toCell(row.get(headers.get(c))));
      }
      dataRows.add(Row.from(cells));
    }

    // Column width constraints: proportional based on header length, capped
    Constraint[] widths = computeWidths(headers, rows);

    Table table =
        Table.builder()
            .header(headerRow)
            .rows(dataRows)
            .widths(widths)
            .columnSpacing(1)
            .highlightSymbol("")
            .build();

    renderAndPrint(table, rows.size(), pager);
  }

  public static void renderValues(List<?> values, CommandDispatcher.IO io) {
    PagedPrinter pager = PagedPrinter.forIO(io);
    if (values == null || values.isEmpty()) {
      pager.println("(no values)");
      return;
    }

    Row headerRow = Row.from(Cell.from("value").style(HEADER_STYLE));
    List<Row> dataRows = new ArrayList<>(values.size());
    for (Object v : values) {
      dataRows.add(Row.from(Cell.from(toCell(v))));
    }

    Table table =
        Table.builder()
            .header(headerRow)
            .rows(dataRows)
            .widths(Constraint.fill())
            .columnSpacing(1)
            .highlightSymbol("")
            .build();

    renderAndPrint(table, values.size(), pager);
  }

  private static void renderAndPrint(Table table, int dataRowCount, PagedPrinter pager) {
    int width = terminalWidth();
    // Height: header + separator + data rows + some margin
    int height = dataRowCount + 3;
    Buffer buffer = Buffer.empty(Rect.of(width, height));
    Frame frame = Frame.forTesting(buffer);
    frame.renderStatefulWidget(table, buffer.area(), new TableState());

    String output = buffer.toAnsiStringTrimmed();
    for (String line : output.split("\r?\n", -1)) {
      if (!line.isBlank()) {
        pager.println(line);
      }
    }
  }

  private static Constraint[] computeWidths(List<String> headers, List<Map<String, Object>> rows) {
    int colCount = headers.size();
    // Compute max content width per column (capped)
    int[] maxWidths = new int[colCount];
    for (int c = 0; c < colCount; c++) {
      maxWidths[c] = headers.get(c).length();
    }
    int sampleSize = Math.min(rows.size(), 100);
    for (int i = 0; i < sampleSize; i++) {
      Map<String, Object> row = rows.get(i);
      for (int c = 0; c < colCount; c++) {
        String cell = toCell(row.get(headers.get(c)));
        maxWidths[c] = Math.max(maxWidths[c], Math.min(MAX_CELL_WIDTH, cell.length()));
      }
    }

    // Use min(maxWidth, MAX_CELL_WIDTH) as length constraints, last column fills
    Constraint[] constraints = new Constraint[colCount];
    for (int c = 0; c < colCount; c++) {
      if (c == colCount - 1) {
        constraints[c] = Constraint.fill();
      } else {
        constraints[c] = Constraint.length(maxWidths[c] + 2);
      }
    }
    return constraints;
  }

  private static String toCell(Object v) {
    if (v == null) return "";
    if (v instanceof Map<?, ?> m) {
      if (m.size() == 1) {
        Object unwrapped = m.values().iterator().next();
        return toCell(unwrapped);
      }
      return m.toString();
    }
    if (v instanceof Collection<?> coll) {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (Object item : coll) {
        if (!first) sb.append(", ");
        first = false;
        sb.append(String.valueOf(item));
      }
      return sb.toString();
    }
    if (v.getClass().isArray()) return Arrays.deepToString((Object[]) v);
    return String.valueOf(v);
  }

  private static int terminalWidth() {
    // Try COLUMNS env var first
    String cols = System.getenv("COLUMNS");
    if (cols != null) {
      try {
        return Math.max(40, Integer.parseInt(cols.trim()));
      } catch (NumberFormatException ignore) {
      }
    }
    // Try system property
    String prop = System.getProperty("jfr.shell.width");
    if (prop != null) {
      try {
        return Math.max(40, Integer.parseInt(prop.trim()));
      } catch (NumberFormatException ignore) {
      }
    }
    return DEFAULT_WIDTH;
  }
}

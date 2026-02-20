package io.jafar.shell.cli;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import java.lang.reflect.Array;
import java.util.ArrayList;
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

  private static final ThreadLocal<List<Map<String, Object>>> LAST_TABLE_DATA = new ThreadLocal<>();
  private static final ThreadLocal<List<String>> LAST_TABLE_HEADERS = new ThreadLocal<>();
  private static final ThreadLocal<List<Map<String, Object>>> LAST_METADATA_CLASSES =
      new ThreadLocal<>();

  private TuiTableRenderer() {}

  public static List<Map<String, Object>> getLastTableData() {
    return LAST_TABLE_DATA.get();
  }

  public static List<String> getLastTableHeaders() {
    return LAST_TABLE_HEADERS.get();
  }

  public static List<Map<String, Object>> getLastMetadataClasses() {
    return LAST_METADATA_CLASSES.get();
  }

  public static void setLastMetadataClasses(List<Map<String, Object>> classes) {
    LAST_METADATA_CLASSES.set(classes);
  }

  public static void clearLastData() {
    LAST_TABLE_DATA.remove();
    LAST_TABLE_HEADERS.remove();
    LAST_METADATA_CLASSES.remove();
  }

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

    // Store structured data for detail pane consumption
    LAST_TABLE_HEADERS.set(headers);
    LAST_TABLE_DATA.set(rows);

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

    // Column width constraints based on content length
    int[] maxWidths = computeMaxWidths(headers, rows);
    Constraint[] widths = computeWidths(maxWidths);

    // Buffer width: sum of column widths + column spacing + borders
    int totalWidth = 2; // left + right border
    for (int w : maxWidths) {
      totalWidth += w + 2 + 1; // +2 padding +1 column spacing
    }
    int bufferWidth = Math.max(terminalWidth(), totalWidth);

    Table table =
        Table.builder()
            .header(headerRow)
            .rows(dataRows)
            .widths(widths)
            .columnSpacing(1)
            .highlightSymbol("")
            .block(Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).build())
            .build();

    renderAndPrint(table, rows.size(), pager, bufferWidth);
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
            .block(Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).build())
            .build();

    renderAndPrint(table, values.size(), pager);
  }

  private static void renderAndPrint(Table table, int dataRowCount, PagedPrinter pager) {
    renderAndPrint(table, dataRowCount, pager, terminalWidth());
  }

  private static void renderAndPrint(Table table, int dataRowCount, PagedPrinter pager, int width) {
    // Height: header + data rows + top/bottom borders + margin
    int height = dataRowCount + 5;
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

  private static int[] computeMaxWidths(List<String> headers, List<Map<String, Object>> rows) {
    int colCount = headers.size();
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
    return maxWidths;
  }

  private static Constraint[] computeWidths(int[] maxWidths) {
    Constraint[] constraints = new Constraint[maxWidths.length];
    int last = maxWidths.length - 1;
    for (int c = 0; c < last; c++) {
      constraints[c] = Constraint.length(maxWidths[c] + 2);
    }
    if (last >= 0) {
      constraints[last] = Constraint.fill();
    }
    return constraints;
  }

  public static String toCell(Object v) {
    if (v == null) return "";
    if (v instanceof Map<?, ?> m) {
      if (m.size() == 1) {
        return toCell(m.values().iterator().next());
      }
      if (m.containsKey("frames")) {
        String summary = extractTopFrameSummary(m);
        int count = arrayLength(m.get("frames"));
        String countStr = count >= 0 ? " <" + count + " frames>" : "";
        return (summary != null ? summary : "<stackTrace>") + countStr;
      }
      // Thread-like object — extract thread name
      Object threadName = m.get("osName");
      if (threadName == null) threadName = m.get("javaName");
      if (threadName != null) return unwrap(threadName).toString();
      return "<" + m.size() + " fields>";
    }
    if (v instanceof Collection<?> coll) {
      return "<" + coll.size() + " items>";
    }
    if (v.getClass().isArray()) {
      return "<" + Array.getLength(v) + " items>";
    }
    return String.valueOf(v);
  }

  /** Navigate nested single-entry map wrappers and ComplexType constant pool references. */
  public static Object unwrap(Object v) {
    if (v instanceof ComplexType ct) v = ct.getValue();
    while (v instanceof Map<?, ?> m && m.size() == 1) {
      v = m.values().iterator().next();
      if (v instanceof ComplexType ct) v = ct.getValue();
    }
    return v;
  }

  /** Extract "ClassName.method:line" from a single frame Map. */
  public static String extractFrameString(Object frameObj) {
    frameObj = resolveComplex(frameObj);
    if (!(frameObj instanceof Map<?, ?> frame)) return null;
    Object methodObj = resolveComplex(unwrap(frame.get("method")));
    if (!(methodObj instanceof Map<?, ?> method)) return null;

    String className = "";
    Object typeObj = resolveComplex(unwrap(method.get("type")));
    if (typeObj instanceof Map<?, ?> typeMap) {
      Object nameObj = unwrap(typeMap.get("name"));
      if (nameObj != null) className = nameObj.toString().replace('/', '.');
    }

    String methodName = "";
    Object nameObj = unwrap(method.get("name"));
    if (nameObj != null) methodName = nameObj.toString();

    Object line = frame.get("lineNumber");
    String lineStr = (line != null && !"-1".equals(line.toString())) ? ":" + line : "";

    String qualName = className.isEmpty() ? methodName : className + "." + methodName;
    return qualName.isEmpty() ? null : qualName + lineStr;
  }

  private static String extractTopFrameSummary(Map<?, ?> stackMap) {
    Object framesObj = stackMap.get("frames");
    if (framesObj instanceof ArrayType at) framesObj = at.getArray();
    Object firstFrame = null;
    if (framesObj != null && framesObj.getClass().isArray() && Array.getLength(framesObj) > 0) {
      firstFrame = Array.get(framesObj, 0);
    } else if (framesObj instanceof List<?> list && !list.isEmpty()) {
      firstFrame = list.get(0);
    }
    if (firstFrame == null) return null;
    firstFrame = resolveComplex(firstFrame);
    return extractFrameString(firstFrame);
  }

  /** Unwrap ComplexType constant pool references to their Map representation. */
  public static Object resolveComplex(Object v) {
    if (v instanceof ComplexType ct) return ct.getValue();
    return v;
  }

  public static int arrayLength(Object v) {
    if (v == null) return -1;
    if (v instanceof ArrayType at) return arrayLength(at.getArray());
    if (v.getClass().isArray()) return Array.getLength(v);
    if (v instanceof Collection<?> c) return c.size();
    return -1;
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

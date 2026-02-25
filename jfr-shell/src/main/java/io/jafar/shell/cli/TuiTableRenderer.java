package io.jafar.shell.cli;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
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
  public static final int MAX_CELL_WIDTH = 40;
  private static final Style HEADER_STYLE = Style.EMPTY.bold().fg(Color.CYAN);

  private static final ThreadLocal<List<Map<String, Object>>> LAST_TABLE_DATA = new ThreadLocal<>();
  private static final ThreadLocal<List<String>> LAST_TABLE_HEADERS = new ThreadLocal<>();
  private static final ThreadLocal<List<Map<String, Object>>> LAST_METADATA_CLASSES =
      new ThreadLocal<>();
  private static final ThreadLocal<Integer> LAST_PREAMBLE_LINES = new ThreadLocal<>();

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

  /** Number of lines printed before the first data row (legend + header + separator). */
  public static int getLastPreambleLines() {
    Integer v = LAST_PREAMBLE_LINES.get();
    return v != null ? v : 1;
  }

  public static void clearLastData() {
    LAST_TABLE_DATA.remove();
    LAST_TABLE_HEADERS.remove();
    LAST_METADATA_CLASSES.remove();
    LAST_PREAMBLE_LINES.remove();
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
    // Remove complex-valued columns (Maps with >1 entry) — shown in detail pane only
    cols.removeIf(
        col -> {
          for (int i = 0; i < sample; i++) {
            Object v = rows.get(i).get(col);
            if (v instanceof Map<?, ?> m && m.size() > 1) return true;
          }
          return false;
        });
    List<String> headers = new ArrayList<>(cols);

    // Store structured data for detail pane consumption
    LAST_TABLE_HEADERS.set(headers);
    LAST_TABLE_DATA.set(rows);

    // Print legend for stackprofile output
    int preambleLines = 0;
    if (headers.contains("timeBuckets") && headers.contains("method")) {
      pager.println(
          "\u25c6 hotspot (>1% self)  \u25c6\u25c6 steady hotspot (N+1 candidate)"
              + "  \u2502  \u001b[32m\u2581\u2582\u001b[33m\u2583\u2584\u2585"
              + "\u001b[31m\u2586\u2587\u2588\u001b[0m activity"
              + "  \u001b[35m\u2584\u2584\u2584\u001b[0m N+1");
      preambleLines++;
    }

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
      // Detect steady hotspot: marker column contains ◆◆
      Object markerVal = row.get(" ");
      boolean steady = markerVal instanceof String s && s.contains("\u25c6\u25c6");
      for (int c = 0; c < headers.size(); c++) {
        String cellText = toCell(row.get(headers.get(c)));
        if (isSparklineColumn(headers.get(c), row.get(headers.get(c)))) {
          cells[c] = Cell.from(colorSparkline(cellText, steady));
        } else {
          cells[c] = Cell.from(cellText);
        }
      }
      dataRows.add(Row.from(cells));
    }

    // Column width constraints based on content length
    int[] maxWidths = computeMaxWidths(headers, rows);
    Constraint[] widths = computeWidths(headers, maxWidths);

    // Buffer width: sum of column widths + column spacing
    int totalWidth = 0;
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
            .build();

    preambleLines += renderAndPrint(table, rows.size(), pager, bufferWidth);
    LAST_PREAMBLE_LINES.set(preambleLines);
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

  private static int renderAndPrint(Table table, int dataRowCount, PagedPrinter pager) {
    return renderAndPrint(table, dataRowCount, pager, terminalWidth());
  }

  /**
   * Renders table to buffer and prints non-blank lines. Returns the number of lines printed before
   * data rows (header + separator).
   */
  private static int renderAndPrint(Table table, int dataRowCount, PagedPrinter pager, int width) {
    // Height: header (1 row) + data rows — exact fit avoids trailing buffer artifacts
    int height = dataRowCount + 1;
    Buffer buffer = Buffer.empty(Rect.of(width, height));
    Frame frame = Frame.forTesting(buffer);
    frame.renderStatefulWidget(table, buffer.area(), new TableState());

    String output = buffer.toAnsiStringTrimmed();
    String[] lines = output.split("\r?\n", -1);
    int totalPrinted = 0;
    for (String line : lines) {
      if (!line.isBlank()) {
        pager.println(line);
        totalPrinted++;
      }
    }
    // Preamble = total printed lines minus the data rows actually printed
    return Math.max(0, totalPrinted - dataRowCount);
  }

  public static int[] computeMaxWidths(List<String> headers, List<Map<String, Object>> rows) {
    int colCount = headers.size();
    int lastCol = colCount - 1;
    int[] maxWidths = new int[colCount];
    for (int c = 0; c < colCount; c++) {
      maxWidths[c] = headers.get(c).length();
    }
    int sampleSize = Math.min(rows.size(), 100);
    for (int i = 0; i < sampleSize; i++) {
      Map<String, Object> row = rows.get(i);
      for (int c = 0; c < colCount; c++) {
        String cell = toCell(row.get(headers.get(c)));
        // Don't cap the last column (fill) — it needs full width for horizontal scrolling
        int cap = (c == lastCol) ? cell.length() : Math.min(MAX_CELL_WIDTH, cell.length());
        maxWidths[c] = Math.max(maxWidths[c], cap);
      }
    }
    return maxWidths;
  }

  private static Constraint[] computeWidths(List<String> headers, int[] maxWidths) {
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

  /** Map long[] values to sparkline characters proportional to max value. */
  public static String sparkline(long[] values) {
    if (values == null || values.length == 0) return "";
    char[] blocks = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
    long max = 0;
    for (long v : values) {
      if (v > max) max = v;
    }
    if (max == 0) return String.valueOf(blocks[0]).repeat(values.length);
    StringBuilder sb = new StringBuilder(values.length);
    for (long v : values) {
      int idx = (int) (v * (blocks.length - 1) / max);
      sb.append(blocks[idx]);
    }
    return sb.toString();
  }

  /** Check if a column value looks like a sparkline string (all block-element characters). */
  private static boolean isSparklineColumn(String header, Object value) {
    if (!(value instanceof String s) || s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch < '\u2581' || ch > '\u2588') return false;
    }
    return true;
  }

  /** Color a sparkline string. Steady hotspots use magenta; others use heat-map colors. */
  private static Line colorSparkline(String sparkline, boolean steady) {
    if (sparkline == null || sparkline.isEmpty()) return Line.from(Span.raw(""));
    Style green = Style.create().fg(Color.GREEN);
    Style yellow = Style.create().fg(Color.YELLOW);
    Style red = Style.create().fg(Color.RED);
    Style magenta = Style.create().fg(Color.MAGENTA);
    List<Span> spans = new ArrayList<>();
    Style current = null;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < sparkline.length(); i++) {
      char ch = sparkline.charAt(i);
      Style color = steady ? magenta : sparkColor(ch, green, yellow, red);
      if (color != current && sb.length() > 0) {
        spans.add(Span.styled(sb.toString(), current));
        sb.setLength(0);
      }
      current = color;
      sb.append(ch);
    }
    if (sb.length() > 0) {
      spans.add(Span.styled(sb.toString(), current));
    }
    return Line.from(spans.toArray(new Span[0]));
  }

  private static Style sparkColor(char ch, Style green, Style yellow, Style red) {
    return switch (ch) {
      case '\u2581', '\u2582' -> green;
      case '\u2583', '\u2584', '\u2585' -> yellow;
      case '\u2586', '\u2587', '\u2588' -> red;
      default -> green;
    };
  }

  private static boolean isSparkChar(char ch) {
    return ch >= '\u2581' && ch <= '\u2588';
  }

  /**
   * Colorize sparkline block characters within a line string. If {@code steadyHotspot} is true,
   * sparkline chars are colored magenta (N+1 candidate). Otherwise uses heat-map colors: green
   * (low), yellow (mid), red (high). Non-sparkline segments remain unstyled.
   */
  public static Line colorizeLine(String line, boolean steadyHotspot) {
    if (line == null || line.isEmpty()) return Line.from(line != null ? line : "");
    Style green = Style.create().fg(Color.GREEN);
    Style yellow = Style.create().fg(Color.YELLOW);
    Style red = Style.create().fg(Color.RED);
    Style magenta = Style.create().fg(Color.MAGENTA);
    List<Span> spans = new ArrayList<>();
    StringBuilder plain = new StringBuilder();
    StringBuilder spark = new StringBuilder();
    Style currentSparkStyle = null;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (isSparkChar(ch)) {
        if (plain.length() > 0) {
          spans.add(Span.raw(plain.toString()));
          plain.setLength(0);
        }
        Style color = steadyHotspot ? magenta : sparkColor(ch, green, yellow, red);
        if (color != currentSparkStyle && spark.length() > 0) {
          spans.add(Span.styled(spark.toString(), currentSparkStyle));
          spark.setLength(0);
        }
        currentSparkStyle = color;
        spark.append(ch);
      } else {
        if (spark.length() > 0) {
          spans.add(Span.styled(spark.toString(), currentSparkStyle));
          spark.setLength(0);
          currentSparkStyle = null;
        }
        plain.append(ch);
      }
    }
    if (spark.length() > 0) {
      spans.add(Span.styled(spark.toString(), currentSparkStyle));
    }
    if (plain.length() > 0) {
      spans.add(Span.raw(plain.toString()));
    }
    return spans.isEmpty() ? Line.from("") : Line.from(spans.toArray(new Span[0]));
  }

  /**
   * Colorize a visible line, auto-detecting steady hotspot by presence of \u25c6\u25c6 in the full
   * (unscrolled) line.
   */
  public static Line colorizeLine(String visible, String fullLine) {
    boolean steady = fullLine != null && fullLine.contains("\u25c6\u25c6");
    return colorizeLine(visible, steady);
  }

  public static String toCell(Object v) {
    if (v == null) return "";
    if (v instanceof long[] la) return sparkline(la);
    if (v instanceof ComplexType ct) return toCell(ct.getValue());
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
    if (v instanceof ArrayType at) {
      int len = arrayLength(at);
      if (looksLikeFrames(at.getArray())) return "<" + len + " frames>";
      return "<" + len + " items>";
    }
    if (v instanceof Collection<?> coll) {
      return "<" + coll.size() + " items>";
    }
    if (v.getClass().isArray()) {
      if (looksLikeFrames(v)) return "<" + Array.getLength(v) + " frames>";
      return "<" + Array.getLength(v) + " items>";
    }
    return String.valueOf(v);
  }

  /** Unwrap ComplexType wrappers and single-entry Map wrappers to reach the underlying value. */
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

  /** Check if the first element of an array looks like a stack frame (has a "method" key). */
  private static boolean looksLikeFrames(Object arr) {
    Object first = null;
    if (arr != null && arr.getClass().isArray() && Array.getLength(arr) > 0) {
      first = Array.get(arr, 0);
    } else if (arr instanceof List<?> list && !list.isEmpty()) {
      first = list.get(0);
    }
    if (first == null) return false;
    first = resolveComplex(first);
    return first instanceof Map<?, ?> m && m.containsKey("method");
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

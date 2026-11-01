package io.jafar.shell.cli;

import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shapes JfrPath results for CSV and JSON output so that a constant-pool string prints as its
 * value: {@code G1New} rather than {@code {name=G1New}}.
 *
 * <p>The untyped parser hands a constant such as a {@code jdk.types.GCName}, {@code
 * jdk.types.Symbol} or {@code jdk.types.GCCause} over as a single-entry {@code java.util.HashMap}
 * ({@code {name=G1New}}, {@code {string=java/lang/Object}}, {@code {cause=G1 Evacuation Pause}}),
 * or as a {@code ComplexType} that resolves to one. The map is an ordinary {@code HashMap}, so
 * nothing about its type tells it apart from a map that is data. What does is where it came from:
 *
 * <ul>
 *   <li>{@code events/...} and {@code constants/...} return values read from the recording, where a
 *       single-entry map is such a wrapper, and are unwrapped.
 *   <li>{@code metadata/...} and {@code chunks/...} return structure, where a single-entry map is
 *       data — {@code fieldsByName} of a type with one field, such as {@code jdk.types.Symbol}, is
 *       {@code {string={...}}} and unwrapping it would lose the field's name. They are left alone.
 * </ul>
 *
 * <p>Only cells are unwrapped, with {@link TuiTableRenderer#unwrap}: a row that is itself a
 * single-entry map keeps its column, and multi-entry values such as a thread are kept as they are,
 * nested wrappers included. jfr-shell's table renderers collapse single-entry cells on their own
 * ({@code TableRenderer.toCell}); this is the same rule for the other formats, and jafar-shell uses
 * it for every format.
 *
 * <p>See {@code doc/agents/DataShapes.md}, "A string constant is not a string".
 */
public final class ConstantValues {
  private ConstantValues() {}

  /** Whether results of this query hold recording values, whose constants are wrapped. */
  public static boolean holdsRecordingValues(JfrPath.Query query) {
    return query != null
        && (query.root == JfrPath.Root.EVENTS || query.root == JfrPath.Root.CONSTANTS);
  }

  /**
   * Whether results of this JfrPath query text hold recording values; {@code false} for text that
   * does not parse, so such a result is printed exactly as it came.
   */
  public static boolean holdsRecordingValues(String query) {
    if (query == null) {
      return false;
    }
    try {
      return holdsRecordingValues(JfrPathParser.parse(query));
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The rows with each cell unwrapped; the input is not modified. */
  public static List<Map<String, Object>> unwrapCells(List<? extends Map<String, ?>> rows) {
    List<Map<String, Object>> shaped = new ArrayList<>(rows.size());
    for (Map<String, ?> row : rows) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<String, ?> cell : row.entrySet()) {
        copy.put(cell.getKey(), TuiTableRenderer.unwrap(cell.getValue()));
      }
      shaped.add(copy);
    }
    return shaped;
  }

  /** The values, as from {@code events/<type>/<field>}, each unwrapped. */
  public static List<Object> unwrapEach(List<?> values) {
    List<Object> shaped = new ArrayList<>(values.size());
    for (Object value : values) {
      shaped.add(TuiTableRenderer.unwrap(value));
    }
    return shaped;
  }
}

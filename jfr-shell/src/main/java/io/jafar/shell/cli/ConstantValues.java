package io.jafar.shell.cli;

import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

  /**
   * The rows as a read-only view in which each cell is unwrapped when it is read.
   *
   * <p>Nothing is copied: a row of the view is a small wrapper made when {@code get(i)} is called,
   * reading through to the source row, and a cell is unwrapped when it is read — an unwrapped value
   * is one the source already holds, so reading a cell allocates nothing for the value itself. A
   * table that prints 50 rows of a million therefore unwraps 50 rows' cells, not a million. The
   * eager copy this replaces allocated about 470 bytes per 8-column row, for every row, before
   * truncation (copy alone; with the table, 524 against the view's 96 and the table's own 32).
   *
   * <p>The view never modifies the source, and every mutator throws {@link
   * UnsupportedOperationException}, so it is safe over rows other code holds, such as a query
   * variable's cached result. It is not a snapshot: it reflects the source at the time of each
   * read, so the source must not be modified while the view is in use. Sources here are {@code
   * ArrayList}s, their sublists, or immutable lists; over a {@code LinkedList}, {@code get(i)}
   * would be linear.
   */
  public static List<Map<String, Object>> unwrapCells(List<? extends Map<String, ?>> rows) {
    return new UnwrappedRows(rows);
  }

  /**
   * The values, as from {@code events/<type>/<field>}, as a read-only view in which each value is
   * unwrapped when it is read; the same terms as {@link #unwrapCells}.
   */
  public static List<Object> unwrapEach(List<?> values) {
    return new UnwrappedValues(values);
  }

  private static final class UnwrappedRows extends AbstractList<Map<String, Object>> {
    private final List<? extends Map<String, ?>> rows;

    UnwrappedRows(List<? extends Map<String, ?>> rows) {
      this.rows = rows;
    }

    @Override
    public Map<String, Object> get(int index) {
      return new UnwrappedRow(rows.get(index));
    }

    @Override
    public int size() {
      return rows.size();
    }
  }

  private static final class UnwrappedValues extends AbstractList<Object> {
    private final List<?> values;

    UnwrappedValues(List<?> values) {
      this.values = values;
    }

    @Override
    public Object get(int index) {
      return TuiTableRenderer.unwrap(values.get(index));
    }

    @Override
    public int size() {
      return values.size();
    }
  }

  /** One row: keys and order from the source row, each value unwrapped as it is read. */
  private static final class UnwrappedRow extends AbstractMap<String, Object> {
    private final Map<String, ?> row;

    UnwrappedRow(Map<String, ?> row) {
      this.row = row;
    }

    @Override
    public Object get(Object key) {
      return TuiTableRenderer.unwrap(row.get(key));
    }

    @Override
    public boolean containsKey(Object key) {
      return row.containsKey(key);
    }

    @Override
    public int size() {
      return row.size();
    }

    @Override
    public boolean isEmpty() {
      return row.isEmpty();
    }

    @Override
    public Set<String> keySet() {
      return Collections.unmodifiableSet(row.keySet());
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
      return new AbstractSet<>() {
        @Override
        public Iterator<Map.Entry<String, Object>> iterator() {
          Iterator<? extends Map.Entry<String, ?>> cells = row.entrySet().iterator();
          return new Iterator<>() {
            @Override
            public boolean hasNext() {
              return cells.hasNext();
            }

            @Override
            public Map.Entry<String, Object> next() {
              Map.Entry<String, ?> cell = cells.next();
              return new SimpleImmutableEntry<>(
                  cell.getKey(), TuiTableRenderer.unwrap(cell.getValue()));
            }
          };
        }

        @Override
        public int size() {
          return row.size();
        }
      };
    }
  }
}

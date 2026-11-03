package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The unwrapping views behind CSV/JSON output in jfr-shell and every format in jafar-shell. */
class ConstantValuesTest {

  private static Map<String, Object> gcRow(long id) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("gcId", id);
    row.put("name", new HashMap<>(Map.of("name", "G1New")));
    row.put("eventThread", new HashMap<>(Map.of("osName", "VM Thread", "javaThreadId", 0L)));
    return row;
  }

  @Test
  void cellsReadUnwrappedInSourceOrder() {
    Map<String, Object> source = gcRow(1);
    Map<String, Object> row = ConstantValues.unwrapCells(List.of(source)).get(0);

    assertEquals("G1New", row.get("name"));
    assertSame(source.get("eventThread"), row.get("eventThread"), "multi-entry kept as is");
    assertEquals(List.of("gcId", "name", "eventThread"), new ArrayList<>(row.keySet()));
    assertEquals(
        Map.of("gcId", 1L, "name", "G1New", "eventThread", source.get("eventThread")), row);
    assertEquals("", row.getOrDefault("missing", ""));
  }

  @Test
  void valuesReadUnwrapped() {
    List<Object> values =
        ConstantValues.unwrapEach(List.of(new HashMap<>(Map.of("cause", "System.gc()")), 7L));
    assertEquals(List.of("System.gc()", 7L), values);
  }

  /** Rows other code holds — a query variable's cache — must come out of display untouched. */
  @Test
  void viewIsReadOnlyAndLeavesTheSourceAlone() {
    Map<String, Object> source = gcRow(1);
    Object wrapped = source.get("name");
    List<Map<String, Object>> view = ConstantValues.unwrapCells(List.of(source));
    Map<String, Object> row = view.get(0);

    assertThrows(UnsupportedOperationException.class, () -> row.put("name", "x"));
    assertThrows(UnsupportedOperationException.class, () -> row.remove("name"));
    assertThrows(UnsupportedOperationException.class, () -> row.keySet().remove("name"));
    assertThrows(UnsupportedOperationException.class, row::clear);
    assertThrows(UnsupportedOperationException.class, () -> view.set(0, Map.of()));
    assertThrows(UnsupportedOperationException.class, () -> view.add(Map.of()));
    assertSame(wrapped, source.get("name"));
    assertEquals(3, source.size());
  }

  /**
   * Making the view copies nothing. The eager copy it replaces allocated about 470 bytes a row up
   * front — 47 MB here — whatever the caller then printed.
   */
  @Test
  void makingTheViewDoesNotCopyTheRows() {
    var threads = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(
        threads instanceof com.sun.management.ThreadMXBean t
            && t.isThreadAllocatedMemorySupported()
            && t.isThreadAllocatedMemoryEnabled(),
        "per-thread allocation counter not available");
    var counter = (com.sun.management.ThreadMXBean) threads;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 100_000; i++) {
      rows.add(gcRow(i));
    }
    long thread = Thread.currentThread().threadId();

    long before = counter.getThreadAllocatedBytes(thread);
    List<Map<String, Object>> view = ConstantValues.unwrapCells(rows);
    long allocated = counter.getThreadAllocatedBytes(thread) - before;

    assertEquals(rows.size(), view.size());
    assertTrue(allocated < 1_000_000, "allocated " + allocated + " bytes for 100000 rows");
  }
}

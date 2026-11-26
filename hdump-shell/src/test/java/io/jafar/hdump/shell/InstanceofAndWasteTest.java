package io.jafar.hdump.shell;

import static io.jafar.hdump.shell.MinimalHprofBuilder.TYPE_INT;
import static io.jafar.hdump.shell.MinimalHprofBuilder.TYPE_OBJECT;
import static io.jafar.hdump.shell.MinimalHprofBuilder.fieldData;
import static org.junit.jupiter.api.Assertions.*;

import io.jafar.hdump.shell.MinimalHprofBuilder.Field;
import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #225: {@code instanceof} on a type it cannot match, and {@code waste()} on collection
 * subclasses.
 *
 * <p>Heap: {@code Object <- AbstractMap <- HashMap(table, size) <- com.example.MyMap}, a field-less
 * {@code java.util.Map} with no instances (what an interface looks like in HPROF), and {@code
 * com.example.Plain}. One HashMap (capacity 16, size 3), one MyMap (capacity 64, size 1), one
 * Plain.
 */
class InstanceofAndWasteTest {

  @TempDir static Path tempDir;

  private static HeapSession session;

  @BeforeAll
  static void setup() throws IOException {
    Path hprof =
        new MinimalHprofBuilder()
            .addClass(1, "java/lang/Object")
            .addClass(2, "java/util/AbstractMap", 1)
            .addClass(
                3,
                "java/util/HashMap",
                2,
                new Field("table", TYPE_OBJECT),
                new Field("size", TYPE_INT))
            .addClass(4, "com/example/MyMap", 3)
            .addClass(5, "java/util/Map", 1)
            .addClass(6, "[Ljava/util/HashMap$Node;", 1)
            .addClass(7, "com/example/Plain", 1)
            .addObjectArray(200, 6, new long[16])
            .addObjectArray(201, 6, new long[64])
            .addInstance(100, 3, fieldData().ref(200).i32(3).bytes())
            .addInstance(101, 4, fieldData().ref(201).i32(1).bytes())
            .addInstance(102, 7)
            .addGcRoot(100)
            .write(tempDir);
    session = HeapSession.open(hprof);
  }

  @AfterAll
  static void teardown() throws Exception {
    if (session != null) {
      session.close();
    }
  }

  private static List<Map<String, Object>> run(String q) {
    return HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse(q));
  }

  @Test
  void instanceofAbstractClassMatchesSubclasses() {
    assertEquals(
        2L,
        ((Number) run("objects/instanceof/java.util.AbstractMap | count").get(0).get("count"))
            .longValue());
  }

  @Test
  void instanceofInterfaceFailsAndExplainsWhy() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> run("objects/instanceof/java.util.Map | count"));
    assertTrue(e.getMessage().startsWith("instanceof/java.util.Map:"), e.getMessage());
    assertTrue(e.getMessage().contains("interface"), e.getMessage());
    assertTrue(e.getMessage().contains("java.util.AbstractMap"), e.getMessage());
  }

  @Test
  void instanceofUnknownClassFails() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> run("objects/instanceof/com.example.Missing | count"));
    assertTrue(e.getMessage().contains("no class named 'com.example.Missing'"), e.getMessage());
  }

  @Test
  void instanceofClassWithInstancesAndNoSubclassesStillWorks() {
    // Plain has no fields and no subclasses, but it has an instance: not an interface
    assertEquals(
        1L,
        ((Number) run("objects/instanceof/com.example.Plain | count").get(0).get("count"))
            .longValue());
  }

  @Test
  void wasteAnalyzesHashMapSubclassThroughInheritedFields() {
    Map<String, Object> myMap =
        run("objects/instanceof/java.util.HashMap | waste()").stream()
            .filter(r -> "com.example.MyMap".equals(r.get("className")))
            .findFirst()
            .orElseThrow();
    // Was null in every waste column: only the exact class names were recognised
    assertEquals(64, myMap.get("capacity"));
    assertEquals(1, myMap.get("size"));
    assertEquals(63L * 8, ((Number) myMap.get("wastedBytes")).longValue());
  }

  @Test
  void wasteRowsKeepFieldAliases() {
    // waste() copied rows into a plain map, so "class" (alias of className) read null after it
    Map<String, Object> row = run("objects/com.example.MyMap | waste()").get(0);
    assertEquals("com.example.MyMap", row.get("class"));
    List<Map<String, Object>> grouped =
        run("objects/instanceof/java.util.HashMap | waste() | groupBy(class, agg=count)");
    assertEquals(2, grouped.size());
  }

  @Test
  void topByWastedBytesPutsRowsWithoutAValueLast() {
    List<Map<String, Object>> top = run("objects | waste() | top(2, wastedBytes)");
    // Was led by rows with wastedBytes = null (Plain, the arrays)
    assertEquals("com.example.MyMap", top.get(0).get("className"));
    assertEquals("java.util.HashMap", top.get(1).get("className"));
  }

  @Test
  void sortByDescendingPutsRowsWithoutAValueLast() {
    List<Map<String, Object>> rows = run("objects | waste() | sortBy(wastedBytes desc)");
    assertNotNull(rows.get(0).get("wastedBytes"));
    assertNull(rows.get(rows.size() - 1).get("wastedBytes"));
  }

  @Test
  void reportWasteSuggestionRuns() throws IOException {
    HeapReportGenerator.Finding waste =
        HeapReportGenerator.generate(session, null).stream()
            .filter(f -> "waste".equals(f.category()))
            .findFirst()
            .orElseThrow();
    // Was objects/instanceof/java.util.Map | ..., which now fails (and matched nothing before)
    assertDoesNotThrow(() -> run(waste.query()));
  }
}

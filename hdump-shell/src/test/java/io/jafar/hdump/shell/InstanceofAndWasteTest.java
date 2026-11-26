package io.jafar.hdump.shell;

import static io.jafar.hdump.shell.MinimalHprofBuilder.TYPE_BOOLEAN;
import static io.jafar.hdump.shell.MinimalHprofBuilder.TYPE_INT;
import static io.jafar.hdump.shell.MinimalHprofBuilder.TYPE_OBJECT;
import static io.jafar.hdump.shell.MinimalHprofBuilder.fieldData;
import static org.junit.jupiter.api.Assertions.*;

import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.shell.MinimalHprofBuilder.Field;
import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #225: {@code instanceof} on a type it cannot match, and {@code waste()} on collection
 * subclasses.
 *
 * <p>Heap: {@code Object <- AbstractMap <- HashMap(table, size) <- com.example.MyMap}, a field-less
 * {@code java.util.Map} with no instances (what an interface looks like in HPROF), and {@code
 * com.example.Plain}. One HashMap (capacity 16, size 3), one MyMap (capacity 64, size 1), one
 * Plain. {@code com.example.Unused} (an int field) and {@code com.example.Utility} (no fields) are
 * loaded but never instantiated. {@code com.example.Lru} extends an access-order {@code
 * LinkedHashMap} (capacity 16, size 2), the usual LRU cache idiom.
 */
class InstanceofAndWasteTest {

  @TempDir static Path tempDir;

  private static HeapSession session;

  @BeforeAll
  static void setup() throws IOException {
    session = HeapSession.open(writeHeap(tempDir));
  }

  static Path writeHeap(Path dir) throws IOException {
    return new MinimalHprofBuilder()
        .addClass(1, "java/lang/Object")
        .addClass(2, "java/util/AbstractMap", 1)
        .addClass(
            3, "java/util/HashMap", 2, new Field("table", TYPE_OBJECT), new Field("size", TYPE_INT))
        .addClass(4, "com/example/MyMap", 3)
        .addClass(5, "java/util/Map", 1)
        .addClass(6, "[Ljava/util/HashMap$Node;", 1)
        .addClass(7, "com/example/Plain", 1)
        .addClass(8, "com/example/Unused", 1, new Field("x", TYPE_INT))
        .addClass(9, "com/example/Utility", 1)
        .addClass(
            10,
            "java/util/LinkedHashMap",
            3,
            new Field("head", TYPE_OBJECT),
            new Field("tail", TYPE_OBJECT),
            new Field("accessOrder", TYPE_BOOLEAN))
        .addClass(11, "com/example/Lru", 10)
        .addObjectArray(202, 6, new long[16])
        .addInstance(103, 11, fieldData().ref(0).ref(0).bool(true).ref(202).i32(2).bytes())
        .addObjectArray(200, 6, new long[16])
        .addObjectArray(201, 6, new long[64])
        .addInstance(100, 3, fieldData().ref(200).i32(3).bytes())
        .addInstance(101, 4, fieldData().ref(201).i32(1).bytes())
        .addInstance(102, 7)
        .addGcRoot(100)
        .write(dir);
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
        3L,
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
  void instanceofGlobMatchingOnlyInterfaceLikeClassesFails() {
    // java/util/Ma* matches only java.util.Map here; the check used to skip globs (count 0)
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> run("objects/instanceof/java.util.Ma* | count"));
    assertTrue(e.getMessage().contains("every class matching"), e.getMessage());
  }

  @Test
  void instanceofNeverInstantiatedClassWithFieldsAnswersZero() {
    assertEquals(
        0L,
        ((Number) run("objects/instanceof/com.example.Unused | count").get(0).get("count"))
            .longValue());
  }

  @Test
  void instanceofFieldlessNeverInstantiatedClassFailsNamingTheZeroQuery() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> run("objects/instanceof/com.example.Utility | count"));
    assertTrue(e.getMessage().contains("query objects/com.example.Utility"), e.getMessage());
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
    assertEquals(3, grouped.size());
  }

  @Test
  void cacheStatsReportsLruModeForALinkedHashMapSubclass() {
    Map<String, Object> lru = run("objects/com.example.Lru | cacheStats()").get(0);
    // Was false twice over: only the exact class name LinkedHashMap was checked for accessOrder,
    // and accessOrder (a Boolean from the reader) was tested as a Number
    assertEquals(true, lru.get("isLruMode"));
    assertEquals(2, lru.get("entryCount"));
    assertEquals(16, lru.get("maxSize"));
  }

  @Test
  void enrichedRowsKeepFieldAliasesForSelect() {
    // cacheStats() copied rows into a plain map, so select(class, ...) read class = null
    Map<String, Object> row =
        run("objects/com.example.Lru | cacheStats() | select(class, isLruMode)").get(0);
    assertEquals("com.example.Lru", row.get("class"));
  }

  @Test
  void topByWastedBytesPutsRowsWithoutAValueLast() {
    List<Map<String, Object>> top = run("objects | waste() | top(2, wastedBytes)");
    // Was led by rows with wastedBytes = null (Plain, the arrays). MyMap wastes 63 * 8, Lru 14 * 8
    assertEquals("com.example.MyMap", top.get(0).get("className"));
    assertEquals("com.example.Lru", top.get(1).get("className"));
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
    // Without its 1MB filter the suggestion must reach the subclass waste() now analyzes
    String unfiltered = waste.query().substring(0, waste.query().indexOf("| filter")).trim();
    assertTrue(
        run(unfiltered).stream().anyMatch(r -> "com.example.MyMap".equals(r.get("className"))),
        unfiltered);
  }

  /**
   * The same checks on the two-pass parser (INDEXED, the AUTO choice above 2 GB), which reported 0
   * instances for every class, so a class with instances but no fields read as an interface.
   */
  @Nested
  class IndexedMode {

    @TempDir Path indexedDir;

    private List<Map<String, Object>> runIndexed(HeapSession s, String q) {
      return HdumpPathEvaluator.evaluate(s, HdumpPathParser.parse(q));
    }

    private void assertCountsAndChecks(HeapSession s) {
      assertEquals(1, s.getHeapDump().getClassByName("com/example/Plain").get().getInstanceCount());
      assertEquals(1, s.getHeapDump().getClassByName("java/util/HashMap").get().getInstanceCount());
      assertEquals(0, s.getHeapDump().getClassByName("java/util/Map").get().getInstanceCount());
      assertEquals(
          1L,
          ((Number)
                  runIndexed(s, "objects/instanceof/com.example.Plain | count").get(0).get("count"))
              .longValue());
      assertThrows(
          IllegalArgumentException.class,
          () -> runIndexed(s, "objects/instanceof/java.util.Map | count"));
    }

    @Test
    void instanceCountsAndInstanceofChecks() throws IOException {
      Path hprof = writeHeap(indexedDir);
      try (HeapSession s = HeapSession.open(hprof, ParserOptions.INDEXED)) {
        assertCountsAndChecks(s);
      }
      // Second open reuses the persisted indexes (the fast path)
      try (HeapSession s = HeapSession.open(hprof, ParserOptions.INDEXED)) {
        assertCountsAndChecks(s);
      }
    }

    @Test
    void instanceCountsWithoutTheClassInstancesIndex() throws IOException {
      Path hprof = writeHeap(indexedDir);
      try (HeapSession s = HeapSession.open(hprof, ParserOptions.INDEXED)) {
        assertNotNull(s);
      }
      // An index directory from before the class-instances index existed
      Path idx = hprof.resolveSibling(hprof.getFileName() + ".idx");
      Files.delete(idx.resolve("classinstances-offset.idx"));
      Files.delete(idx.resolve("classinstances-data.idx"));
      try (HeapSession s = HeapSession.open(hprof, ParserOptions.INDEXED)) {
        assertCountsAndChecks(s);
      }
      // The counts above came from the object-index fallback, not from a rebuilt index
      assertFalse(Files.exists(idx.resolve("classinstances-offset.idx")));
    }
  }
}

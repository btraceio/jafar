package io.jafar.hdump.shell;

import static org.junit.jupiter.api.Assertions.*;

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
 * Evaluator tests that run against a synthetic in-memory HPROF file, so they work in CI without any
 * external heap dump file.
 *
 * <p>The heap contains:
 *
 * <ul>
 *   <li>2 classes: {@code com/example/Foo} (id=100) and {@code com/example/Bar} (id=101)
 *   <li>5 instances: 3 of Foo (ids 1000-1002) and 2 of Bar (ids 1003-1004)
 *   <li>1 GC root: ROOT_UNKNOWN pointing to Foo instance 1000
 * </ul>
 */
class SyntheticHeapEvaluatorTest {

  @TempDir static Path tempDir;

  private static HeapSession session;

  @BeforeAll
  static void setup() throws IOException {
    Path hprof =
        new MinimalHprofBuilder()
            .addClass(100, "com/example/Foo")
            .addClass(101, "com/example/Bar")
            .addInstance(1000, 100)
            .addInstance(1001, 100)
            .addInstance(1002, 100)
            .addInstance(1003, 101)
            .addInstance(1004, 101)
            .addGcRoot(1000)
            .write(tempDir);

    session = HeapSession.open(hprof);
  }

  @AfterAll
  static void teardown() throws Exception {
    if (session != null) {
      session.close();
    }
  }

  @Test
  void objectsCount() {
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("objects | count()"));
    assertEquals(1, result.size());
    assertEquals(5L, ((Number) result.get(0).get("count")).longValue());
  }

  @Test
  void objectsHead() {
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("objects | head(3)"));
    assertEquals(3, result.size());
    // Every row must have id, class, shallow
    for (Map<String, Object> row : result) {
      assertTrue(row.containsKey("id"), "row missing 'id'");
      assertTrue(row.containsKey("class"), "row missing 'class'");
      assertTrue(row.containsKey("shallow"), "row missing 'shallow'");
    }
  }

  @Test
  void objectsFilterByClass() {
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(
            session, HdumpPathParser.parse("objects/com.example.Foo | count()"));
    assertEquals(1, result.size());
    assertEquals(3L, ((Number) result.get(0).get("count")).longValue());
  }

  @Test
  void objectsGroupByClass() {
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(
            session,
            HdumpPathParser.parse("objects | groupBy(class, agg=count) | sortBy(count desc)"));
    assertEquals(2, result.size());
    // Foo has 3 instances — should sort first
    assertEquals("com.example.Foo", result.get(0).get("class"));
    assertEquals(3L, ((Number) result.get(0).get("count")).longValue());
    assertEquals("com.example.Bar", result.get(1).get("class"));
    assertEquals(2L, ((Number) result.get(1).get("count")).longValue());
  }

  @Test
  void classesQuery() {
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("classes | count()"));
    assertEquals(1, result.size());
    // At least 2 classes (Foo + Bar); parser may inject synthetic classes for primitive arrays
    assertTrue(((Number) result.get(0).get("count")).longValue() >= 2);
  }

  @Test
  void selectSpecificFields() {
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(
            session, HdumpPathParser.parse("objects/com.example.Bar | select(id, class)"));
    assertEquals(2, result.size());
    for (Map<String, Object> row : result) {
      assertTrue(row.containsKey("id"));
      assertTrue(row.containsKey("class"));
      assertFalse(row.containsKey("shallow"), "shallow must not appear in select(id, class)");
      assertEquals("com.example.Bar", row.get("class"));
    }
  }

  @Test
  void gcRootsQuery() {
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("gcroots | count()"));
    assertEquals(1, result.size());
    assertTrue(((Number) result.get(0).get("count")).longValue() >= 1);
  }

  @Test
  void objectsCountDoesNotTriggerRetainedSizeComputation() {
    // count() does not reference retained size — heap should NOT have dominators after this query
    HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("objects | count()"));
    assertFalse(
        session.getHeapDump().hasDominators(),
        "count() must not trigger retained-size computation");
  }

  @Test
  void threadOwnerOpParsesAndRunsWithoutError() {
    // threadOwner() requires a dominator tree; on a small heap this triggers computation.
    // Verify it produces rows with ownerThread and ownership columns.
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(
            session, HdumpPathParser.parse("objects | threadOwner() | head(5)"));
    assertFalse(result.isEmpty());
    for (Map<String, Object> row : result) {
      assertTrue(row.containsKey("ownerThread"), "threadOwner() must add ownerThread column");
      assertTrue(row.containsKey("ownership"), "threadOwner() must add ownership column");
      String ownership = (String) row.get("ownership");
      assertTrue(
          "exclusive".equals(ownership) || "shared".equals(ownership),
          "ownership must be 'exclusive' or 'shared', got: " + ownership);
    }
  }
}

package io.jafar.hdump.shell;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.core.expr.BinaryExpr;
import io.jafar.shell.core.expr.FieldRef;
import io.jafar.shell.core.expr.NumberLiteral;
import io.jafar.shell.core.expr.ValueExpr;
import io.jafar.hdump.shell.hdumppath.HdumpPath;
import io.jafar.hdump.shell.hdumppath.HdumpPath.Query;
import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** Tests for HdumpPath query language. */
class HdumpPathTest {

  private static final Path TEST_HPROF =
      Path.of(System.getProperty("user.home"), "Downloads", "test.hprof");

  private static HeapSession session;

  static boolean testFileExists() {
    return Files.exists(TEST_HPROF);
  }

  @BeforeAll
  static void setup() throws Exception {
    if (testFileExists()) {
      session = HeapSession.open(TEST_HPROF);
    }
  }

  @AfterAll
  static void teardown() throws Exception {
    if (session != null) {
      session.close();
    }
  }

  // === Parser Tests ===

  @Test
  void testParseSimpleObjectsQuery() {
    Query query = HdumpPathParser.parse("objects");
    assertEquals(HdumpPath.Root.OBJECTS, query.root());
    assertNull(query.typePattern());
    assertFalse(query.instanceof_());
    assertTrue(query.predicates().isEmpty());
    assertTrue(query.pipeline().isEmpty());
  }

  @Test
  void testParseObjectsWithType() {
    Query query = HdumpPathParser.parse("objects/java.lang.String");
    assertEquals(HdumpPath.Root.OBJECTS, query.root());
    assertEquals("java.lang.String", query.typePattern());
    assertFalse(query.instanceof_());
  }

  @Test
  void testParseObjectsWithInstanceof() {
    Query query = HdumpPathParser.parse("objects/instanceof/java.util.Map");
    assertEquals(HdumpPath.Root.OBJECTS, query.root());
    assertEquals("java.util.Map", query.typePattern());
    assertTrue(query.instanceof_());
  }

  @Test
  void testParseObjectsWithPredicate() {
    Query query = HdumpPathParser.parse("objects/java.lang.String[shallow > 100]");
    assertEquals("java.lang.String", query.typePattern());
    assertEquals(1, query.predicates().size());
  }

  @Test
  void testParseClassesQuery() {
    Query query = HdumpPathParser.parse("classes");
    assertEquals(HdumpPath.Root.CLASSES, query.root());
  }

  @Test
  void testParseGcRootsQuery() {
    Query query = HdumpPathParser.parse("gcroots");
    assertEquals(HdumpPath.Root.GCROOTS, query.root());
  }

  @Test
  void testParsePipelineOps() {
    Query query = HdumpPathParser.parse("objects | top(10, shallow) | select(class, shallow)");
    assertEquals(2, query.pipeline().size());
    assertInstanceOf(HdumpPath.TopOp.class, query.pipeline().get(0));
    assertInstanceOf(HdumpPath.SelectOp.class, query.pipeline().get(1));
  }

  @Test
  void testParseGroupBy() {
    Query query = HdumpPathParser.parse("objects | groupBy(class, agg=count)");
    assertEquals(1, query.pipeline().size());
    HdumpPath.GroupByOp groupBy = (HdumpPath.GroupByOp) query.pipeline().get(0);
    assertEquals(List.of("class"), groupBy.groupFields());
    assertEquals(HdumpPath.AggOp.COUNT, groupBy.aggregation());
  }

  @Test
  void testParseGroupByWithValueField() {
    Query query = HdumpPathParser.parse("classes | groupBy(name, agg=sum, value=instanceCount)");
    assertEquals(1, query.pipeline().size());
    HdumpPath.GroupByOp groupBy = (HdumpPath.GroupByOp) query.pipeline().get(0);
    assertEquals(List.of("name"), groupBy.groupFields());
    assertEquals(HdumpPath.AggOp.SUM, groupBy.aggregation());
    assertNotNull(groupBy.valueExpr());
    assertInstanceOf(FieldRef.class, groupBy.valueExpr());
    assertEquals("instanceCount", ((FieldRef) groupBy.valueExpr()).field());
  }

  @Test
  void testParseGroupByWithValueExpression() {
    Query query =
        HdumpPathParser.parse("classes | groupBy(name, agg=sum, value=instanceCount * instanceSize)");
    assertEquals(1, query.pipeline().size());
    HdumpPath.GroupByOp groupBy = (HdumpPath.GroupByOp) query.pipeline().get(0);
    assertEquals(HdumpPath.AggOp.SUM, groupBy.aggregation());
    assertNotNull(groupBy.valueExpr());
    assertInstanceOf(BinaryExpr.class, groupBy.valueExpr());
    BinaryExpr expr = (BinaryExpr) groupBy.valueExpr();
    assertEquals(ValueExpr.ArithOp.MUL, expr.op());
    assertInstanceOf(FieldRef.class, expr.left());
    assertInstanceOf(FieldRef.class, expr.right());
  }

  @Test
  void testParseGroupByWithComplexExpression() {
    Query query =
        HdumpPathParser.parse("classes | groupBy(name, agg=avg, value=(instanceCount + 1) * 2)");
    HdumpPath.GroupByOp groupBy = (HdumpPath.GroupByOp) query.pipeline().get(0);
    assertNotNull(groupBy.valueExpr());
    assertInstanceOf(BinaryExpr.class, groupBy.valueExpr());
  }

  @Test
  void testParseGroupByWithAggregateFunctionCall() {
    // Test the new sum(expr) syntax as alternative to agg=sum, value=expr
    Query query =
        HdumpPathParser.parse("classes | groupBy(name, sum(instanceSize * instanceCount))");
    assertEquals(1, query.pipeline().size());
    HdumpPath.GroupByOp groupBy = (HdumpPath.GroupByOp) query.pipeline().get(0);
    assertEquals(List.of("name"), groupBy.groupFields());
    assertEquals(HdumpPath.AggOp.SUM, groupBy.aggregation());
    assertNotNull(groupBy.valueExpr());
    assertInstanceOf(BinaryExpr.class, groupBy.valueExpr());
    BinaryExpr expr = (BinaryExpr) groupBy.valueExpr();
    assertEquals(ValueExpr.ArithOp.MUL, expr.op());
  }

  @Test
  void testParseGroupByWithDifferentAggregateFunctions() {
    // Test avg()
    Query avgQuery = HdumpPathParser.parse("classes | groupBy(name, avg(instanceSize))");
    HdumpPath.GroupByOp avgGroupBy = (HdumpPath.GroupByOp) avgQuery.pipeline().get(0);
    assertEquals(HdumpPath.AggOp.AVG, avgGroupBy.aggregation());
    assertInstanceOf(FieldRef.class, avgGroupBy.valueExpr());

    // Test min()
    Query minQuery = HdumpPathParser.parse("classes | groupBy(name, min(instanceSize))");
    HdumpPath.GroupByOp minGroupBy = (HdumpPath.GroupByOp) minQuery.pipeline().get(0);
    assertEquals(HdumpPath.AggOp.MIN, minGroupBy.aggregation());

    // Test max()
    Query maxQuery = HdumpPathParser.parse("classes | groupBy(name, max(instanceSize))");
    HdumpPath.GroupByOp maxGroupBy = (HdumpPath.GroupByOp) maxQuery.pipeline().get(0);
    assertEquals(HdumpPath.AggOp.MAX, maxGroupBy.aggregation());

    // Test count() with expression
    Query countQuery = HdumpPathParser.parse("classes | groupBy(name, count(instanceCount))");
    HdumpPath.GroupByOp countGroupBy = (HdumpPath.GroupByOp) countQuery.pipeline().get(0);
    assertEquals(HdumpPath.AggOp.COUNT, countGroupBy.aggregation());
  }

  @Test
  void testValueExprEvaluation() {
    Map<String, Object> row = Map.of("instanceCount", 10, "instanceSize", 24);

    // Simple field reference
    ValueExpr fieldRef = new FieldRef("instanceCount");
    assertEquals(10.0, fieldRef.evaluate(row));

    // Multiplication expression
    ValueExpr mulExpr =
        new BinaryExpr(new FieldRef("instanceCount"), ValueExpr.ArithOp.MUL, new FieldRef("instanceSize"));
    assertEquals(240.0, mulExpr.evaluate(row));

    // Complex expression: (instanceCount + 1) * 2
    ValueExpr complexExpr =
        new BinaryExpr(
            new BinaryExpr(
                new FieldRef("instanceCount"), ValueExpr.ArithOp.ADD, new NumberLiteral(1)),
            ValueExpr.ArithOp.MUL,
            new NumberLiteral(2));
    assertEquals(22.0, complexExpr.evaluate(row));
  }

  @Test
  void testParseSizeUnits() {
    Query query = HdumpPathParser.parse("objects[shallow > 1MB]");
    assertFalse(query.predicates().isEmpty());
  }

  @Test
  void testParseComplexPredicate() {
    Query query = HdumpPathParser.parse("objects[shallow > 100 and shallow < 1000]");
    assertEquals(1, query.predicates().size());
  }

  // === Evaluator Tests (require real heap dump) ===

  @Test
  @EnabledIf("testFileExists")
  void testEvaluateObjectsQuery() {
    Query query = HdumpPathParser.parse("objects | head(10)");
    List<Map<String, Object>> results = HdumpPathEvaluator.evaluate(session, query);
    assertEquals(10, results.size());
    assertTrue(results.get(0).containsKey("class"));
    assertTrue(results.get(0).containsKey("shallow"));
  }

  @Test
  @EnabledIf("testFileExists")
  void testEvaluateStringObjects() {
    Query query = HdumpPathParser.parse("objects/java.lang.String | head(5)");
    List<Map<String, Object>> results = HdumpPathEvaluator.evaluate(session, query);
    assertEquals(5, results.size());
    for (Map<String, Object> row : results) {
      assertEquals("java.lang.String", row.get("class"));
    }
  }

  @Test
  @EnabledIf("testFileExists")
  void testEvaluateTopByShallowSize() {
    Query query = HdumpPathParser.parse("objects/java.lang.String | top(10, shallow)");
    List<Map<String, Object>> results = HdumpPathEvaluator.evaluate(session, query);
    assertEquals(10, results.size());

    // Verify descending order
    int prevSize = Integer.MAX_VALUE;
    for (Map<String, Object> row : results) {
      int size = ((Number) row.get("shallow")).intValue();
      assertTrue(size <= prevSize, "Results should be in descending order by shallow size");
      prevSize = size;
    }
  }

  @Test
  @EnabledIf("testFileExists")
  void testEvaluateClassesQuery() {
    Query query = HdumpPathParser.parse("classes[instanceCount > 1000] | top(5, instanceCount)");
    List<Map<String, Object>> results = HdumpPathEvaluator.evaluate(session, query);
    assertFalse(results.isEmpty());

    for (Map<String, Object> row : results) {
      assertTrue(((Number) row.get("instanceCount")).intValue() > 1000);
    }
  }

  @Test
  @EnabledIf("testFileExists")
  void testEvaluateGroupByClass() {
    Query query = HdumpPathParser.parse("objects | groupBy(class, agg=count) | top(10, count)");
    List<Map<String, Object>> results = HdumpPathEvaluator.evaluate(session, query);
    assertFalse(results.isEmpty());

    for (Map<String, Object> row : results) {
      assertNotNull(row.get("class"));
      assertNotNull(row.get("count"));
    }

    System.out.println("\n=== Top 10 Classes by Object Count ===");
    for (Map<String, Object> row : results) {
      System.out.printf("  %,8d  %s%n", ((Number) row.get("count")).intValue(), row.get("class"));
    }
  }

  @Test
  @EnabledIf("testFileExists")
  void testEvaluateGcRoots() {
    Query query = HdumpPathParser.parse("gcroots | groupBy(type, agg=count)");
    List<Map<String, Object>> results = HdumpPathEvaluator.evaluate(session, query);
    assertFalse(results.isEmpty());

    System.out.println("\n=== GC Root Distribution ===");
    for (Map<String, Object> row : results) {
      System.out.printf("  %,8d  %s%n", ((Number) row.get("count")).intValue(), row.get("type"));
    }
  }

  @Test
  @EnabledIf("testFileExists")
  void testEvaluateStats() {
    Query query = HdumpPathParser.parse("objects/java.lang.String | stats(shallow)");
    List<Map<String, Object>> results = HdumpPathEvaluator.evaluate(session, query);
    assertEquals(1, results.size());

    Map<String, Object> stats = results.get(0);
    assertTrue(stats.containsKey("count"));
    assertTrue(stats.containsKey("sum"));
    assertTrue(stats.containsKey("min"));
    assertTrue(stats.containsKey("max"));
    assertTrue(stats.containsKey("avg"));

    System.out.println("\n=== String Size Statistics ===");
    System.out.printf("  Count: %,d%n", ((Number) stats.get("count")).longValue());
    System.out.printf("  Sum:   %,d bytes%n", ((Number) stats.get("sum")).longValue());
    System.out.printf("  Min:   %,d bytes%n", ((Number) stats.get("min")).longValue());
    System.out.printf("  Max:   %,d bytes%n", ((Number) stats.get("max")).longValue());
    System.out.printf("  Avg:   %.1f bytes%n", ((Number) stats.get("avg")).doubleValue());
  }

  @Test
  @EnabledIf("testFileExists")
  void testEvaluateSelect() {
    Query query =
        HdumpPathParser.parse("objects/java.lang.String | head(5) | select(id, shallow as size)");
    List<Map<String, Object>> results = HdumpPathEvaluator.evaluate(session, query);
    assertEquals(5, results.size());

    for (Map<String, Object> row : results) {
      assertTrue(row.containsKey("id"));
      assertTrue(row.containsKey("size"));
      assertFalse(row.containsKey("class")); // Not selected
    }
  }

  @Test
  @EnabledIf("testFileExists")
  void testEvaluateCount() {
    Query query = HdumpPathParser.parse("objects/java.lang.String | count");
    List<Map<String, Object>> results = HdumpPathEvaluator.evaluate(session, query);
    assertEquals(1, results.size());
    assertTrue(((Number) results.get(0).get("count")).intValue() > 0);

    System.out.println("\n=== String Count ===");
    System.out.printf(
        "  Total String objects: %,d%n", ((Number) results.get(0).get("count")).intValue());
  }

  @Test
  @EnabledIf("testFileExists")
  void testEvaluateWithFilter() {
    Query query = HdumpPathParser.parse("objects/java.lang.String[shallow > 100] | count");
    List<Map<String, Object>> results = HdumpPathEvaluator.evaluate(session, query);
    assertEquals(1, results.size());

    System.out.println("\n=== Large Strings (>100 bytes) ===");
    System.out.printf("  Count: %,d%n", ((Number) results.get(0).get("count")).intValue());
  }
}

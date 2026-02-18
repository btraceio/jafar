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
  void testParseGroupByWithSortOptions() {
    // Test sort=key
    Query sortKeyQuery = HdumpPathParser.parse("classes | groupBy(name, agg=count, sort=key)");
    HdumpPath.GroupByOp sortKeyOp = (HdumpPath.GroupByOp) sortKeyQuery.pipeline().get(0);
    assertEquals("key", sortKeyOp.sortBy());
    assertFalse(sortKeyOp.ascending()); // default is descending

    // Test sort=value with asc=true
    Query sortValueAscQuery =
        HdumpPathParser.parse("classes | groupBy(name, sum(instanceCount), sort=value, asc=true)");
    HdumpPath.GroupByOp sortValueAscOp = (HdumpPath.GroupByOp) sortValueAscQuery.pipeline().get(0);
    assertEquals("value", sortValueAscOp.sortBy());
    assertTrue(sortValueAscOp.ascending());

    // Test with function call syntax and sort
    Query funcSortQuery =
        HdumpPathParser.parse("classes | groupBy(name, sum(instanceSize * instanceCount), sort=value)");
    HdumpPath.GroupByOp funcSortOp = (HdumpPath.GroupByOp) funcSortQuery.pipeline().get(0);
    assertEquals(HdumpPath.AggOp.SUM, funcSortOp.aggregation());
    assertEquals("value", funcSortOp.sortBy());
    assertNotNull(funcSortOp.valueExpr());
  }

  @Test
  void testParseGroupByWithSortByAlias() {
    // sortBy= should work as alias for sort=
    Query sortByQuery = HdumpPathParser.parse("classes | groupBy(name, agg=count, sortBy=key)");
    HdumpPath.GroupByOp sortByOp = (HdumpPath.GroupByOp) sortByQuery.pipeline().get(0);
    assertEquals("key", sortByOp.sortBy());
    assertFalse(sortByOp.ascending());

    Query sortByValueQuery =
        HdumpPathParser.parse("classes | groupBy(name, agg=sum, sortBy=value, asc=true)");
    HdumpPath.GroupByOp sortByValueOp =
        (HdumpPath.GroupByOp) sortByValueQuery.pipeline().get(0);
    assertEquals("value", sortByValueOp.sortBy());
    assertTrue(sortByValueOp.ascending());
  }

  @Test
  void testParseTopWithNamedParams() {
    // by= named param style (JfrPath-compatible)
    Query byQuery = HdumpPathParser.parse("objects | top(10, by=shallow)");
    HdumpPath.TopOp byOp = (HdumpPath.TopOp) byQuery.pipeline().get(0);
    assertEquals(10, byOp.n());
    assertEquals("shallow", byOp.orderBy());
    assertTrue(byOp.descending()); // default

    // by= with asc=true
    Query ascQuery = HdumpPathParser.parse("objects | top(5, by=retained, asc=true)");
    HdumpPath.TopOp ascOp = (HdumpPath.TopOp) ascQuery.pipeline().get(0);
    assertEquals(5, ascOp.n());
    assertEquals("retained", ascOp.orderBy());
    assertFalse(ascOp.descending());
  }

  @Test
  void testParseTopPositionalStyle() {
    // Existing positional style still works
    Query q = HdumpPathParser.parse("objects | top(10, shallow, asc)");
    HdumpPath.TopOp op = (HdumpPath.TopOp) q.pipeline().get(0);
    assertEquals(10, op.n());
    assertEquals("shallow", op.orderBy());
    assertFalse(op.descending());
  }

  @Test
  void testParseSortByWithAscNamedParam() {
    // asc=true named param style (JfrPath-compatible)
    Query q = HdumpPathParser.parse("objects | sortBy(shallow, asc=true)");
    HdumpPath.SortByOp op = (HdumpPath.SortByOp) q.pipeline().get(0);
    assertEquals(1, op.fields().size());
    assertEquals("shallow", op.fields().get(0).field());
    assertFalse(op.fields().get(0).descending());
  }

  @Test
  void testSingleQuotesAreRawStrings() {
    // Single quotes should preserve backslashes literally (raw string)
    Query q = HdumpPathParser.parse("objects[className ~ '.*\\.HashMap']");
    HdumpPath.ExprPredicate pred = (HdumpPath.ExprPredicate) q.predicates().get(0);
    HdumpPath.CompExpr comp = (HdumpPath.CompExpr) pred.expr();
    assertEquals(".*\\.HashMap", comp.literal().toString());
  }

  @Test
  void testDoubleQuotesProcessEscapes() {
    // Double quotes should process escape sequences
    Query q = HdumpPathParser.parse("objects[className ~ \".*\\.HashMap\"]");
    HdumpPath.ExprPredicate pred = (HdumpPath.ExprPredicate) q.predicates().get(0);
    HdumpPath.CompExpr comp = (HdumpPath.CompExpr) pred.expr();
    assertEquals(".*.HashMap", comp.literal().toString());
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

  // === Array Type Parser Tests ===

  @Test
  void testParseJvmObjectArrayDescriptor() {
    Query query = HdumpPathParser.parse("objects/[Ljava.lang.Object;");
    assertEquals("[Ljava.lang.Object;", query.typePattern());
    assertFalse(query.instanceof_());
    assertTrue(query.predicates().isEmpty());
  }

  @Test
  void testParseJvmObjectArrayDescriptorWithPredicate() {
    Query query = HdumpPathParser.parse("objects/[Ljava.lang.Object;[shallow > 1MB]");
    assertEquals("[Ljava.lang.Object;", query.typePattern());
    assertEquals(1, query.predicates().size());
  }

  @Test
  void testParseJvmPrimitiveArrayDescriptors() {
    assertEquals("[I", HdumpPathParser.parse("objects/[I").typePattern());
    assertEquals("[J", HdumpPathParser.parse("objects/[J").typePattern());
    assertEquals("[B", HdumpPathParser.parse("objects/[B").typePattern());
    assertEquals("[Z", HdumpPathParser.parse("objects/[Z").typePattern());
    assertEquals("[C", HdumpPathParser.parse("objects/[C").typePattern());
    assertEquals("[F", HdumpPathParser.parse("objects/[F").typePattern());
    assertEquals("[D", HdumpPathParser.parse("objects/[D").typePattern());
    assertEquals("[S", HdumpPathParser.parse("objects/[S").typePattern());
  }

  @Test
  void testParseJvmMultidimensionalArrayDescriptor() {
    Query query = HdumpPathParser.parse("objects/[[I");
    assertEquals("[[I", query.typePattern());
    query = HdumpPathParser.parse("objects/[[Ljava.lang.String;");
    assertEquals("[[Ljava.lang.String;", query.typePattern());
  }

  @Test
  void testParseJavaArrayNotationObject() {
    Query query = HdumpPathParser.parse("objects/java.lang.Object[]");
    assertEquals("[Ljava.lang.Object;", query.typePattern());
  }

  @Test
  void testParseJavaArrayNotationPrimitive() {
    assertEquals("[I", HdumpPathParser.parse("objects/int[]").typePattern());
    assertEquals("[J", HdumpPathParser.parse("objects/long[]").typePattern());
    assertEquals("[B", HdumpPathParser.parse("objects/byte[]").typePattern());
    assertEquals("[Z", HdumpPathParser.parse("objects/boolean[]").typePattern());
  }

  @Test
  void testParseJavaArrayNotationMultidimensional() {
    Query query = HdumpPathParser.parse("objects/java.lang.String[][]");
    assertEquals("[[Ljava.lang.String;", query.typePattern());
    query = HdumpPathParser.parse("objects/int[][]");
    assertEquals("[[I", query.typePattern());
  }

  @Test
  void testParseJavaArrayNotationWithPredicate() {
    Query query = HdumpPathParser.parse("objects/java.lang.Object[][arrayLength > 100]");
    assertEquals("[Ljava.lang.Object;", query.typePattern());
    assertEquals(1, query.predicates().size());
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

  // === RetentionPaths parser tests ===

  @Test
  void testRetentionPathsNoArgs() {
    Query query = HdumpPathParser.parse("objects | retentionPaths()");
    assertEquals(1, query.pipeline().size());
    assertInstanceOf(HdumpPath.RetentionPathsOp.class, query.pipeline().get(0));
  }

  @Test
  void testRetentionPathsNoParens() {
    Query query = HdumpPathParser.parse("objects/java.lang.String | retentionPaths");
    assertEquals(1, query.pipeline().size());
    assertInstanceOf(HdumpPath.RetentionPathsOp.class, query.pipeline().get(0));
  }

  @Test
  void testRetentionPathsAlias() {
    Query query = HdumpPathParser.parse("objects | classPaths()");
    assertInstanceOf(HdumpPath.RetentionPathsOp.class, query.pipeline().get(0));
  }

  // === RetainedBreakdown parser tests ===

  @Test
  void testRetainedBreakdownNoArgs() {
    Query query = HdumpPathParser.parse("objects | retainedBreakdown()");
    assertEquals(1, query.pipeline().size());
    HdumpPath.RetainedBreakdownOp op = (HdumpPath.RetainedBreakdownOp) query.pipeline().get(0);
    assertEquals(4, op.maxDepth()); // default
  }

  @Test
  void testRetainedBreakdownNoParens() {
    Query query = HdumpPathParser.parse("objects | retainedBreakdown");
    assertInstanceOf(HdumpPath.RetainedBreakdownOp.class, query.pipeline().get(0));
  }

  @Test
  void testRetainedBreakdownWithDepth() {
    Query query = HdumpPathParser.parse("objects | retainedBreakdown(depth=6)");
    HdumpPath.RetainedBreakdownOp op = (HdumpPath.RetainedBreakdownOp) query.pipeline().get(0);
    assertEquals(6, op.maxDepth());
  }

  @Test
  void testRetainedBreakdownWithPositionalDepth() {
    Query query = HdumpPathParser.parse("objects | retainedBreakdown(3)");
    HdumpPath.RetainedBreakdownOp op = (HdumpPath.RetainedBreakdownOp) query.pipeline().get(0);
    assertEquals(3, op.maxDepth());
  }

  @Test
  void testRetainedBreakdownAlias() {
    Query query = HdumpPathParser.parse("objects | breakdown()");
    assertInstanceOf(HdumpPath.RetainedBreakdownOp.class, query.pipeline().get(0));
  }

  @Test
  void testRetainedBreakdownFromClassesQuery() {
    // Verify that the parser accepts retainedBreakdown after a classes/ root
    Query query = HdumpPathParser.parse("classes/java.util.HashMap | retainedBreakdown()");
    assertEquals(HdumpPath.Root.CLASSES, query.root());
    assertEquals("java.util.HashMap", query.typePattern());
    assertInstanceOf(HdumpPath.RetainedBreakdownOp.class, query.pipeline().get(0));
  }

  @Test
  void testRetainedBreakdownFromClassesQueryWithPipeline() {
    Query query = HdumpPathParser.parse(
        "classes | top(5, instanceCount) | retainedBreakdown(depth=3)");
    assertEquals(HdumpPath.Root.CLASSES, query.root());
    assertEquals(2, query.pipeline().size());
    assertInstanceOf(HdumpPath.TopOp.class, query.pipeline().get(0));
    HdumpPath.RetainedBreakdownOp op = (HdumpPath.RetainedBreakdownOp) query.pipeline().get(1);
    assertEquals(3, op.maxDepth());
  }

  // === Dominators parser tests ===

  @Test
  void testDominatorsNoArgs() {
    Query query = HdumpPathParser.parse("objects | dominators()");
    assertEquals(1, query.pipeline().size());
    HdumpPath.DominatorsOp op = (HdumpPath.DominatorsOp) query.pipeline().get(0);
    assertNull(op.mode());
    assertNull(op.groupBy());
    assertEquals(1024 * 1024L, op.minRetained()); // default 1MB
  }

  @Test
  void testDominatorsGroupByClass() {
    Query query = HdumpPathParser.parse("objects | dominators(groupBy=\"class\")");
    HdumpPath.DominatorsOp op = (HdumpPath.DominatorsOp) query.pipeline().get(0);
    assertNull(op.mode());
    assertEquals("class", op.groupBy());
    assertEquals(0L, op.minRetained()); // groupBy default is 0
  }

  @Test
  void testDominatorsGroupByPackage() {
    Query query = HdumpPathParser.parse("objects | dominators(groupBy=\"package\")");
    HdumpPath.DominatorsOp op = (HdumpPath.DominatorsOp) query.pipeline().get(0);
    assertNull(op.mode());
    assertEquals("package", op.groupBy());
    assertEquals(0L, op.minRetained());
  }

  @Test
  void testDominatorsGroupByWithMinRetained() {
    Query query = HdumpPathParser.parse("objects | dominators(groupBy=\"class\", minRetained=5MB)");
    HdumpPath.DominatorsOp op = (HdumpPath.DominatorsOp) query.pipeline().get(0);
    assertEquals("class", op.groupBy());
    assertEquals(5 * 1024 * 1024L, op.minRetained());
  }

  @Test
  void testDominatorsTreeMode() {
    Query query = HdumpPathParser.parse("objects | dominators(\"tree\")");
    HdumpPath.DominatorsOp op = (HdumpPath.DominatorsOp) query.pipeline().get(0);
    assertEquals("tree", op.mode());
    assertNull(op.groupBy());
    assertEquals(0L, op.minRetained());
  }

  @Test
  void testDominatorsObjectsModeWithMinRetained() {
    Query query = HdumpPathParser.parse("objects | dominators(\"objects\", minRetained=10MB)");
    HdumpPath.DominatorsOp op = (HdumpPath.DominatorsOp) query.pipeline().get(0);
    assertEquals("objects", op.mode());
    assertNull(op.groupBy());
    assertEquals(10 * 1024 * 1024L, op.minRetained());
  }

  @Test
  void testParseLenOp() {
    Query query = HdumpPathParser.parse("objects | len(className)");
    assertEquals(1, query.pipeline().size());
    assertInstanceOf(HdumpPath.LenOp.class, query.pipeline().get(0));
    assertEquals("className", ((HdumpPath.LenOp) query.pipeline().get(0)).field());
  }

  @Test
  void testParseLengthAlias() {
    Query query = HdumpPathParser.parse("objects | length(className)");
    assertInstanceOf(HdumpPath.LenOp.class, query.pipeline().get(0));
  }

  @Test
  void testParseUppercaseOp() {
    Query query = HdumpPathParser.parse("objects | uppercase(className)");
    assertInstanceOf(HdumpPath.UppercaseOp.class, query.pipeline().get(0));
    assertEquals("className", ((HdumpPath.UppercaseOp) query.pipeline().get(0)).field());
  }

  @Test
  void testParseLowercaseOp() {
    Query query = HdumpPathParser.parse("objects | lowercase(className)");
    assertInstanceOf(HdumpPath.LowercaseOp.class, query.pipeline().get(0));
  }

  @Test
  void testParseTrimOp() {
    Query query = HdumpPathParser.parse("objects | trim(stringValue)");
    assertInstanceOf(HdumpPath.TrimOp.class, query.pipeline().get(0));
  }

  @Test
  void testParseReplaceOp() {
    Query query = HdumpPathParser.parse("objects | replace(className, \"foo\", \"bar\")");
    assertInstanceOf(HdumpPath.ReplaceOp.class, query.pipeline().get(0));
    HdumpPath.ReplaceOp op = (HdumpPath.ReplaceOp) query.pipeline().get(0);
    assertEquals("className", op.field());
    assertEquals("foo", op.target());
    assertEquals("bar", op.replacement());
  }

  @Test
  void testParseAbsOp() {
    Query query = HdumpPathParser.parse("objects | abs(shallow)");
    assertInstanceOf(HdumpPath.AbsOp.class, query.pipeline().get(0));
  }

  @Test
  void testParseRoundOp() {
    Query query = HdumpPathParser.parse("objects | round(retained)");
    assertInstanceOf(HdumpPath.RoundOp.class, query.pipeline().get(0));
  }

  @Test
  void testParseFloorOp() {
    Query query = HdumpPathParser.parse("objects | floor(retained)");
    assertInstanceOf(HdumpPath.FloorOp.class, query.pipeline().get(0));
  }

  @Test
  void testParseCeilOp() {
    Query query = HdumpPathParser.parse("objects | ceil(retained)");
    assertInstanceOf(HdumpPath.CeilOp.class, query.pipeline().get(0));
  }
}

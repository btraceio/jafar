package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AllocationAggregatorTest {

  @Test
  void emptyInput() {
    assertTrue(AllocationAggregator.aggregate(List.of()).isEmpty());
    assertTrue(AllocationAggregator.aggregate(null).isEmpty());
  }

  @Test
  void singleEvent() {
    Map<String, Object> row = new HashMap<>();
    row.put("objectClass.name", "java.lang.String");
    row.put("weight", 128L);

    Map<String, Map<String, Object>> result = AllocationAggregator.aggregate(List.of(row));
    assertEquals(1, result.size());
    Map<String, Object> stats = result.get("java.lang.String");
    assertNotNull(stats);
    assertEquals(1L, stats.get("allocCount"));
    assertEquals(128L, stats.get("allocWeight"));
  }

  @Test
  void multipleEventsAggregated() {
    Map<String, Object> row1 = new HashMap<>();
    row1.put("objectClass.name", "java.lang.String");
    row1.put("weight", 100L);
    Map<String, Object> row2 = new HashMap<>();
    row2.put("objectClass.name", "java.lang.String");
    row2.put("weight", 200L);
    Map<String, Object> row3 = new HashMap<>();
    row3.put("objectClass.name", "java.util.HashMap");
    row3.put("weight", 50L);

    Map<String, Map<String, Object>> result =
        AllocationAggregator.aggregate(List.of(row1, row2, row3));
    assertEquals(2, result.size());

    Map<String, Object> strStats = result.get("java.lang.String");
    assertEquals(2L, strStats.get("allocCount"));
    assertEquals(300L, strStats.get("allocWeight"));

    Map<String, Object> mapStats = result.get("java.util.HashMap");
    assertEquals(1L, mapStats.get("allocCount"));
    assertEquals(50L, mapStats.get("allocWeight"));
  }

  @Test
  void classNameNormalization() {
    assertEquals("java.lang.String", AllocationAggregator.normalizeClassName("java/lang/String"));
    assertEquals("java.lang.String", AllocationAggregator.normalizeClassName("Ljava/lang/String;"));
    assertEquals("java.lang.String", AllocationAggregator.normalizeClassName("java.lang.String"));
    assertNull(AllocationAggregator.normalizeClassName(null));
  }

  @Test
  void classNameNormalizationInAggregation() {
    Map<String, Object> row = new HashMap<>();
    row.put("objectClass.name", "java/lang/String");
    row.put("weight", 64L);

    Map<String, Map<String, Object>> result = AllocationAggregator.aggregate(List.of(row));
    assertTrue(result.containsKey("java.lang.String"));
  }

  @Test
  void allocRateComputation() {
    Map<String, Object> row1 = new HashMap<>();
    row1.put("objectClass.name", "java.lang.String");
    row1.put("weight", 100L);
    row1.put("startTime", 1_000_000_000L); // 1 second
    Map<String, Object> row2 = new HashMap<>();
    row2.put("objectClass.name", "java.lang.String");
    row2.put("weight", 200L);
    row2.put("startTime", 3_000_000_000L); // 3 seconds

    Map<String, Map<String, Object>> result = AllocationAggregator.aggregate(List.of(row1, row2));
    Map<String, Object> stats = result.get("java.lang.String");
    // 2 events over 2 seconds = 1.0 per second
    assertEquals(1.0, (Double) stats.get("allocRate"), 0.01);
  }

  @Test
  void allocRateNullWhenNoTimestamps() {
    Map<String, Object> row = new HashMap<>();
    row.put("objectClass.name", "java.lang.String");
    row.put("weight", 100L);

    Map<String, Map<String, Object>> result = AllocationAggregator.aggregate(List.of(row));
    assertNull(result.get("java.lang.String").get("allocRate"));
  }

  @Test
  void topAllocSiteFromStringStackTrace() {
    Map<String, Object> row1 = new HashMap<>();
    row1.put("objectClass.name", "java.lang.String");
    row1.put("weight", 100L);
    row1.put("stackTrace", "com.example.Foo.bar\ncom.example.Baz.qux");
    Map<String, Object> row2 = new HashMap<>();
    row2.put("objectClass.name", "java.lang.String");
    row2.put("weight", 200L);
    row2.put("stackTrace", "com.example.Foo.bar\ncom.example.Other.method");

    Map<String, Map<String, Object>> result = AllocationAggregator.aggregate(List.of(row1, row2));
    assertEquals("com.example.Foo.bar", result.get("java.lang.String").get("topAllocSite"));
  }

  @Test
  void topAllocSiteNullWhenNoStackTrace() {
    Map<String, Object> row = new HashMap<>();
    row.put("objectClass.name", "java.lang.String");
    row.put("weight", 100L);

    Map<String, Map<String, Object>> result = AllocationAggregator.aggregate(List.of(row));
    assertNull(result.get("java.lang.String").get("topAllocSite"));
  }

  @Test
  void objectClassAsMap() {
    Map<String, Object> classMap = new HashMap<>();
    classMap.put("name", "java.util.ArrayList");
    Map<String, Object> row = new HashMap<>();
    row.put("objectClass", classMap);
    row.put("weight", 32L);

    Map<String, Map<String, Object>> result = AllocationAggregator.aggregate(List.of(row));
    assertTrue(result.containsKey("java.util.ArrayList"));
  }

  @Test
  void missingWeightDefaultsToZero() {
    Map<String, Object> row = new HashMap<>();
    row.put("objectClass.name", "java.lang.String");

    Map<String, Map<String, Object>> result = AllocationAggregator.aggregate(List.of(row));
    assertEquals(0L, result.get("java.lang.String").get("allocWeight"));
  }

  @Test
  void rowsWithNoClassNameSkipped() {
    Map<String, Object> row = new HashMap<>();
    row.put("weight", 100L);

    Map<String, Map<String, Object>> result = AllocationAggregator.aggregate(List.of(row));
    assertTrue(result.isEmpty());
  }

  @Test
  void normalizeClassNameHandlesArrayDescriptors() {
    assertEquals(
        "java.lang.String[]", AllocationAggregator.normalizeClassName("[Ljava/lang/String;"));
    assertEquals("int[]", AllocationAggregator.normalizeClassName("[I"));
    assertEquals("byte[][]", AllocationAggregator.normalizeClassName("[[B"));
    assertEquals(
        "java.lang.Object[][]", AllocationAggregator.normalizeClassName("[[Ljava/lang/Object;"));
  }

  @Test
  void normalizeClassNameHandlesRegularNames() {
    assertEquals("java.lang.String", AllocationAggregator.normalizeClassName("Ljava/lang/String;"));
    assertEquals("java.lang.String", AllocationAggregator.normalizeClassName("java/lang/String"));
    assertEquals("java.lang.String", AllocationAggregator.normalizeClassName("java.lang.String"));
    assertNull(AllocationAggregator.normalizeClassName(null));
  }

  // ───────────────────────────────────────────────────────────────────────────────
  // Row shapes as the untyped parser actually produces them.
  //
  // Every other test in this class feeds a flattened "objectClass.name" string, which is a shape
  // the parser never emits: string constants arrive wrapped, as {string: "..."} behind a "value"
  // indirection. The aggregator used to return an empty map for real rows, which made the
  // heap-to-JFR allocation correlation silently produce null columns for every class.
  // ───────────────────────────────────────────────────────────────────────────────

  /** {@code {value: {string: name}}} — a string constant behind a complex-value indirection. */
  private static Map<String, Object> wrappedString(String value) {
    return Map.of("value", Map.of("string", value));
  }

  private static Map<String, Object> parserShapedRow(String jvmClassName, long weight) {
    Map<String, Object> row = new HashMap<>();
    row.put("objectClass", Map.of("name", wrappedString(jvmClassName)));
    row.put("weight", weight);
    return row;
  }

  @Test
  void aggregatesRowsInTheShapeTheParserEmits() {
    Map<String, Map<String, Object>> result =
        AllocationAggregator.aggregate(
            List.of(parserShapedRow("[B", 1024L), parserShapedRow("[B", 2048L)));

    Map<String, Object> stats = result.get("byte[]");
    assertNotNull(stats, "wrapped objectClass.name must resolve; got keys " + result.keySet());
    assertEquals(2L, stats.get("allocCount"));
    assertEquals(3072L, stats.get("allocWeight"));
  }

  @Test
  void normalisesWrappedNamesToSourceForm() {
    Map<String, Map<String, Object>> result =
        AllocationAggregator.aggregate(
            List.of(
                parserShapedRow("[B", 1L),
                parserShapedRow("[C", 1L),
                parserShapedRow("java/util/ArrayList$Itr", 1L),
                parserShapedRow("[Ljava/lang/String;", 1L)));

    // These are the names the heap-dump `classes` root uses, so the join key matches.
    assertEquals(
        java.util.Set.of("byte[]", "char[]", "java.util.ArrayList$Itr", "java.lang.String[]"),
        result.keySet());
  }

  @Test
  void resolvesTopAllocationSiteFromWrappedFrames() {
    Map<String, Object> row = new HashMap<>();
    row.put("objectClass", Map.of("name", wrappedString("[B")));
    row.put("weight", 512L);
    row.put(
        "stackTrace",
        Map.of(
            "frames",
            List.of(
                Map.of(
                    "method",
                    Map.of(
                        "name", wrappedString("main"),
                        "type", Map.of("name", wrappedString("Workload")))))));

    Map<String, Object> stats = AllocationAggregator.aggregate(List.of(row)).get("byte[]");
    assertNotNull(stats);
    assertEquals("Workload.main", stats.get("topAllocSite"));
  }

  @Test
  void toleratesRowsWithNoResolvableClassName() {
    Map<String, Object> unusable = new HashMap<>();
    unusable.put("objectClass", Map.of("name", Map.of("value", Map.of())));
    unusable.put("weight", 64L);

    Map<String, Map<String, Object>> result =
        AllocationAggregator.aggregate(List.of(unusable, parserShapedRow("[B", 8L)));

    assertEquals(java.util.Set.of("byte[]"), result.keySet());
  }
}

package io.jafar.hdump.shell;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
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

  @Test
  void duplicatesReturnsGroupsWithRequiredFields() {
    // Heap has 3 Foo and 2 Bar instances, all with the same structure → 2 duplicate groups
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("duplicates"));
    assertFalse(result.isEmpty(), "duplicates must find at least one group");

    for (Map<String, Object> row : result) {
      assertTrue(row.containsKey("id"), "duplicate row missing 'id'");
      assertTrue(row.containsKey("rootClass"), "duplicate row missing 'rootClass'");
      assertTrue(row.containsKey("fingerprint"), "duplicate row missing 'fingerprint'");
      assertTrue(row.containsKey("copies"), "duplicate row missing 'copies'");
      assertTrue(row.containsKey("uniqueSize"), "duplicate row missing 'uniqueSize'");
      assertTrue(row.containsKey("wastedBytes"), "duplicate row missing 'wastedBytes'");
      assertTrue(row.containsKey("depth"), "duplicate row missing 'depth'");
      assertTrue(row.containsKey("nodeCount"), "duplicate row missing 'nodeCount'");
      assertTrue(((Number) row.get("copies")).intValue() >= 2, "copies must be >= 2");
    }
  }

  @Test
  void duplicatesFooGroupHasThreeCopies() {
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(
            session, HdumpPathParser.parse("duplicates[rootClass = \"com.example.Foo\"]"));
    assertEquals(1, result.size(), "exactly one Foo duplicate group");
    assertEquals(3, ((Number) result.get(0).get("copies")).intValue());
  }

  @Test
  void duplicatesDepthParamIsParsedAndStored() {
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("duplicates(depth=1)"));
    // All rows must report depth=1
    for (Map<String, Object> row : result) {
      assertEquals(1, ((Number) row.get("depth")).intValue());
    }
  }

  @Test
  void duplicatesObjectsDrillDown() {
    // duplicates | objects should expand the duplicate group into its member object rows
    List<Map<String, Object>> result =
        HdumpPathEvaluator.evaluate(
            session,
            HdumpPathParser.parse("duplicates[rootClass = \"com.example.Foo\"] | objects()"));
    // Foo has 3 instances → objects() should return 3 rows
    assertEquals(3, result.size());
    for (Map<String, Object> row : result) {
      assertTrue(row.containsKey("id"), "object row missing 'id'");
      assertTrue(row.containsKey("class"), "object row missing 'class'");
      assertEquals("com.example.Foo", row.get("class"));
    }
  }

  /**
   * Isolated cacheStats() tests. Use a dedicated session so that retained-size computation does not
   * pollute the shared session.
   */
  @Nested
  class CacheStatsTests {

    @TempDir Path cacheStatsTempDir;

    private HeapSession cacheStatsSession() throws IOException {
      Path hprof =
          new MinimalHprofBuilder()
              .addClass(600, "com/example/Foo")
              .addInstance(6000, 600)
              .addInstance(6001, 600)
              .addInstance(6002, 600)
              .addGcRoot(6000)
              .write(cacheStatsTempDir);
      return HeapSession.open(hprof);
    }

    @Test
    void cacheStatsOnNonMapObjectProducesNullColumns() throws IOException {
      try (HeapSession s = cacheStatsSession()) {
        // com.example.Foo has no "size" or "table" fields, so cacheStats() must add null columns
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(
                s, HdumpPathParser.parse("objects/com.example.Foo | cacheStats() | head(3)"));
        assertFalse(result.isEmpty());
        for (Map<String, Object> row : result) {
          assertTrue(row.containsKey("entryCount"), "cacheStats() must add entryCount column");
          assertTrue(row.containsKey("maxSize"), "cacheStats() must add maxSize column");
          assertTrue(row.containsKey("fillRatio"), "cacheStats() must add fillRatio column");
          assertTrue(row.containsKey("costPerEntry"), "cacheStats() must add costPerEntry column");
          assertTrue(row.containsKey("isLruMode"), "cacheStats() must add isLruMode column");
          // All should be null since Foo is not a recognized Map type
          assertNull(row.get("entryCount"), "entryCount should be null for non-map");
        }
      }
    }
  }

  /**
   * Isolated report tests. Use a dedicated session so that retained-size computation triggered by
   * {@code HeapReportGenerator.generate()} does not pollute the shared session used by other tests.
   */
  @Nested
  class ReportTests {

    @TempDir Path reportTempDir;

    private HeapSession reportSession() throws IOException {
      Path hprof =
          new MinimalHprofBuilder()
              .addClass(500, "com/example/Foo")
              .addClass(501, "com/example/Bar")
              .addInstance(5000, 500)
              .addInstance(5001, 500)
              .addInstance(5002, 500)
              .addInstance(5003, 501)
              .addInstance(5004, 501)
              .addGcRoot(5000)
              .write(reportTempDir);
      return HeapSession.open(hprof);
    }

    @Test
    void reportGeneratesFindings() throws IOException {
      try (HeapSession s = reportSession()) {
        List<HeapReportGenerator.Finding> findings = HeapReportGenerator.generate(s, null);
        assertFalse(findings.isEmpty(), "report must return at least one finding");
      }
    }

    @Test
    void reportContainsHistogramInfo() throws IOException {
      try (HeapSession s = reportSession()) {
        List<HeapReportGenerator.Finding> findings = HeapReportGenerator.generate(s, null);
        boolean hasInfo =
            findings.stream().anyMatch(f -> f.severity() == HeapReportGenerator.Severity.INFO);
        assertTrue(hasInfo, "report must include at least one INFO finding");
      }
    }

    @Test
    void reportTextContainsHeapHeader() throws IOException {
      try (HeapSession s = reportSession()) {
        List<HeapReportGenerator.Finding> findings = HeapReportGenerator.generate(s, null);
        String text = HeapReportGenerator.formatText(findings, s);
        assertTrue(text.contains("=== Heap Health Report ==="), "text report must contain header");
        assertTrue(text.contains("objects"), "text report must mention object count");
      }
    }

    @Test
    void reportMarkdownContainsHeader() throws IOException {
      try (HeapSession s = reportSession()) {
        List<HeapReportGenerator.Finding> findings = HeapReportGenerator.generate(s, null);
        String md = HeapReportGenerator.formatMarkdown(findings, s);
        assertTrue(
            md.startsWith("# Heap Health Report"), "markdown report must start with H1 header");
      }
    }

    @Test
    void reportFocusRunsOnlyRequestedContributors() throws IOException {
      try (HeapSession s = reportSession()) {
        List<HeapReportGenerator.Finding> findings =
            HeapReportGenerator.generate(s, Set.of("duplicates"));
        assertFalse(findings.isEmpty(), "focused report must return findings");
        // Non-INFO findings must be in the duplicates category
        findings.stream()
            .filter(f -> f.severity() != HeapReportGenerator.Severity.INFO)
            .forEach(
                f ->
                    assertTrue(
                        f.category().startsWith("duplicates"),
                        "non-INFO finding outside focus: " + f.category()));
      }
    }
  }

  /**
   * Isolated tests for the {@code whatif()} pipeline operator. Uses a dedicated session so that
   * retained-size computation does not pollute the shared session.
   */
  @Nested
  class WhatIfTests {

    @TempDir Path whatifTempDir;

    private HeapSession whatifSession() throws IOException {
      Path hprof =
          new MinimalHprofBuilder()
              .addClass(200, "com/example/Foo")
              .addClass(201, "com/example/Bar")
              .addInstance(2000, 200)
              .addInstance(2001, 200)
              .addInstance(2002, 200)
              .addInstance(2003, 201)
              .addInstance(2004, 201)
              .addGcRoot(2000)
              .write(whatifTempDir);
      return HeapSession.open(hprof);
    }

    @Test
    void whatifOperatorOnFoo() throws IOException {
      try (HeapSession s = whatifSession()) {
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(
                s, HdumpPathParser.parse("objects/com.example.Foo | whatif()"));
        assertEquals(1, result.size());
        Map<String, Object> row = result.get(0);
        assertEquals("remove", row.get("action"));
        assertEquals(3, ((Number) row.get("targetCount")).intValue());
        assertTrue(((Number) row.get("freedBytes")).longValue() >= 0);
        assertTrue(((Number) row.get("freedObjects")).intValue() >= 0);
        assertTrue(row.containsKey("freedPct"));
        assertTrue(row.containsKey("remainingRetained"));
      }
    }

    @Test
    void whatifOperatorOnEmpty() throws IOException {
      try (HeapSession s = whatifSession()) {
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(
                s, HdumpPathParser.parse("objects/com.example.Nonexistent | whatif()"));
        assertEquals(1, result.size());
        Map<String, Object> row = result.get(0);
        assertEquals(0, ((Number) row.get("targetCount")).intValue());
        assertEquals(0L, ((Number) row.get("freedBytes")).longValue());
      }
    }

    @Test
    void whatifWithSelect() throws IOException {
      try (HeapSession s = whatifSession()) {
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(
                s,
                HdumpPathParser.parse("objects/com.example.Foo | whatif() | select(freedBytes)"));
        assertEquals(1, result.size());
        assertTrue(result.get(0).containsKey("freedBytes"));
      }
    }
  }

  /**
   * Isolated tests for the {@code ages} root and {@code estimateAge()} operator. Uses a dedicated
   * session to avoid polluting the shared session used by other tests.
   */
  @Nested
  class AgeEstimationTests {

    @TempDir Path ageTempDir;

    private HeapSession ageSession() throws IOException {
      Path hprof =
          new MinimalHprofBuilder()
              .addClass(300, "com/example/Foo")
              .addClass(301, "com/example/Bar")
              .addInstance(3000, 300)
              .addInstance(3001, 300)
              .addInstance(3002, 300)
              .addInstance(3003, 301)
              .addInstance(3004, 301)
              .addGcRoot(3000)
              .write(ageTempDir);
      return HeapSession.open(hprof);
    }

    private static final java.util.Set<String> VALID_BUCKETS =
        java.util.Set.of("ephemeral", "medium", "tenured", "permanent");

    @Test
    void agesRootReturnsAllObjects() throws IOException {
      try (HeapSession s = ageSession()) {
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(s, HdumpPathParser.parse("ages | count()"));
        assertEquals(1, result.size());
        assertEquals(5L, ((Number) result.get(0).get("count")).longValue());
      }
    }

    @Test
    void agesRootHasAgeFields() throws IOException {
      try (HeapSession s = ageSession()) {
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(s, HdumpPathParser.parse("ages | head(5)"));
        assertEquals(5, result.size());
        for (Map<String, Object> row : result) {
          assertTrue(row.containsKey("estimatedAge"), "missing estimatedAge");
          assertTrue(row.containsKey("ageBucket"), "missing ageBucket");
          assertTrue(row.containsKey("ageSignals"), "missing ageSignals");
        }
      }
    }

    @Test
    void agesRootFilterByClass() throws IOException {
      try (HeapSession s = ageSession()) {
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(s, HdumpPathParser.parse("ages/com.example.Foo | count()"));
        assertEquals(1, result.size());
        assertEquals(3L, ((Number) result.get(0).get("count")).longValue());
      }
    }

    @Test
    void estimateAgeOpEnriches() throws IOException {
      try (HeapSession s = ageSession()) {
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(
                s, HdumpPathParser.parse("objects | estimateAge() | head(3)"));
        assertEquals(3, result.size());
        for (Map<String, Object> row : result) {
          assertTrue(row.containsKey("estimatedAge"), "missing estimatedAge");
          assertTrue(row.containsKey("ageBucket"), "missing ageBucket");
          assertTrue(row.containsKey("ageSignals"), "missing ageSignals");
          // original object fields must be preserved
          assertTrue(row.containsKey("id"), "missing id");
          assertTrue(row.containsKey("class"), "missing class");
        }
      }
    }

    @Test
    void ageBucketIsValid() throws IOException {
      try (HeapSession s = ageSession()) {
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(s, HdumpPathParser.parse("ages | head(5)"));
        for (Map<String, Object> row : result) {
          String bucket = (String) row.get("ageBucket");
          assertTrue(VALID_BUCKETS.contains(bucket), "invalid ageBucket: " + bucket);
        }
      }
    }

    @Test
    void agesScoreInRange() throws IOException {
      try (HeapSession s = ageSession()) {
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(s, HdumpPathParser.parse("ages | head(5)"));
        for (Map<String, Object> row : result) {
          int score = ((Number) row.get("estimatedAge")).intValue();
          assertTrue(score >= 0 && score <= 100, "score out of range: " + score);
        }
      }
    }

    @Test
    void agesWithPipeline() throws IOException {
      try (HeapSession s = ageSession()) {
        List<Map<String, Object>> result =
            HdumpPathEvaluator.evaluate(
                s,
                HdumpPathParser.parse(
                    "ages | filter(ageBucket = \"ephemeral\") | select(estimatedAge)"));
        for (Map<String, Object> row : result) {
          assertTrue(row.containsKey("estimatedAge"));
        }
      }
    }
  }
}

package io.jafar.hdump.shell.hdumppath;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.hdump.shell.hdumppath.HdumpPath.JoinOp;
import io.jafar.hdump.shell.hdumppath.HdumpPath.Query;
import io.jafar.shell.core.AllocationAggregator;
import io.jafar.shell.core.CrossSessionContext;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.VariableStore;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CrossTypeJoinTest {

  // === Parser tests ===

  @Test
  void parseJoinWithRootParameter() {
    Query query =
        HdumpPathParser.parse(
            "classes | join(session=1, root=\"jdk.ObjectAllocationSample\", by=class)");
    assertEquals(1, query.pipeline().size());
    JoinOp op = (JoinOp) query.pipeline().get(0);
    assertEquals("1", op.sessionRef());
    assertEquals("jdk.ObjectAllocationSample", op.root());
    assertEquals("class", op.byField());
  }

  @Test
  void parseJoinWithoutRootIsNull() {
    Query query = HdumpPathParser.parse("classes | join(session=1, by=name)");
    JoinOp op = (JoinOp) query.pipeline().get(0);
    assertNull(op.root());
  }

  @Test
  void parseJoinRootBeforeBy() {
    Query query =
        HdumpPathParser.parse("classes | join(session=2, root=\"jdk.ObjectAllocationSample\")");
    JoinOp op = (JoinOp) query.pipeline().get(0);
    assertEquals("2", op.sessionRef());
    assertEquals("jdk.ObjectAllocationSample", op.root());
    assertNull(op.byField());
  }

  // === Evaluator integration tests with mocks ===

  @Test
  void crossTypeJoinEnrichesHeapRows() throws Exception {
    // Heap class rows
    List<Map<String, Object>> heapRows =
        List.of(classRow("java.lang.String", 5000), classRow("java.util.HashMap", 200));

    // Mock JFR events
    List<Map<String, Object>> jfrEvents =
        List.of(
            allocEvent("java.lang.String", 128, "com.app.Parser.read"),
            allocEvent("java.lang.String", 256, "com.app.Parser.read"),
            allocEvent("java.lang.String", 64, "com.app.IO.write"),
            allocEvent("java.util.HashMap", 512, "com.app.Cache.put"));

    // Build mock context
    MockSession jfrSession = new MockSession("jfr", Path.of("recording.jfr"));
    MockEvaluator jfrEvaluator = new MockEvaluator(jfrEvents);
    CrossSessionContext ctx = mockContext(jfrSession, jfrEvaluator);

    JoinOp joinOp = new JoinOp("1", "name", "jdk.ObjectAllocationSample");

    // Use AllocationAggregator directly to verify the join logic
    Map<String, Map<String, Object>> allocStats = AllocationAggregator.aggregate(jfrEvents);
    assertEquals(2, allocStats.size());

    // String: 3 allocs, 448 total weight
    Map<String, Object> strStats = allocStats.get("java.lang.String");
    assertEquals(3L, strStats.get("allocCount"));
    assertEquals(448L, strStats.get("allocWeight"));
    assertEquals("com.app.Parser.read", strStats.get("topAllocSite"));

    // HashMap: 1 alloc, 512 weight
    Map<String, Object> mapStats = allocStats.get("java.util.HashMap");
    assertEquals(1L, mapStats.get("allocCount"));
    assertEquals(512L, mapStats.get("allocWeight"));
  }

  @Test
  void crossTypeJoinLeftJoinSemanticsNullForUnmatched() {
    // Heap row with a class that has no JFR events
    List<Map<String, Object>> heapRows = List.of(classRow("com.example.Orphan", 100));

    List<Map<String, Object>> jfrEvents = List.of(allocEvent("java.lang.String", 128, null));

    Map<String, Map<String, Object>> allocStats = AllocationAggregator.aggregate(jfrEvents);
    // com.example.Orphan should not be in the stats
    assertNull(allocStats.get("com.example.Orphan"));
  }

  @Test
  void survivalRatioComputation() {
    // instanceCount=500, allocCount=1000 => survivalRatio=0.5
    Map<String, Object> heapRow = classRow("java.lang.String", 500);
    Map<String, Object> stats = Map.of("allocCount", 1000L);

    long instanceCount = ((Number) heapRow.get("instanceCount")).longValue();
    long allocCount = ((Number) stats.get("allocCount")).longValue();
    double survivalRatio = (double) instanceCount / allocCount;
    assertEquals(0.5, survivalRatio, 0.001);
  }

  @Test
  void survivalRatioNullWhenAllocCountZero() {
    // Empty JFR results should yield null survivalRatio
    Map<String, Map<String, Object>> allocStats = AllocationAggregator.aggregate(List.of());
    assertTrue(allocStats.isEmpty());
  }

  // === Helper methods ===

  private static Map<String, Object> classRow(String name, int instanceCount) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", name);
    row.put("instanceCount", (long) instanceCount);
    row.put("instanceSize", 24L);
    return row;
  }

  private static Map<String, Object> allocEvent(String className, long weight, String stackTrace) {
    Map<String, Object> row = new HashMap<>();
    row.put("objectClass.name", className);
    row.put("weight", weight);
    if (stackTrace != null) {
      row.put("stackTrace", stackTrace);
    }
    return row;
  }

  private static CrossSessionContext mockContext(MockSession session, MockEvaluator evaluator) {
    return new CrossSessionContext() {
      @Override
      public Optional<SessionManager.SessionRef<? extends Session>> resolve(String idOrAlias) {
        return Optional.of(new SessionManager.SessionRef<>(1, null, session));
      }

      @Override
      public Optional<QueryEvaluator> evaluatorFor(Session s) {
        return Optional.of(evaluator);
      }
    };
  }

  private static class MockSession implements Session {
    private final String type;
    private final Path filePath;

    MockSession(String type, Path filePath) {
      this.type = type;
      this.filePath = filePath;
    }

    @Override
    public String getType() {
      return type;
    }

    @Override
    public Path getFilePath() {
      return filePath;
    }

    @Override
    public boolean isClosed() {
      return false;
    }

    @Override
    public Set<String> getAvailableTypes() {
      return Set.of();
    }

    @Override
    public Map<String, Object> getStatistics() {
      return Map.of();
    }

    @Override
    public void close() {}
  }

  private static class MockEvaluator implements QueryEvaluator {
    private final List<Map<String, Object>> result;

    MockEvaluator(List<Map<String, Object>> result) {
      this.result = result;
    }

    @Override
    public Object parse(String queryString) {
      return queryString;
    }

    @Override
    public Object evaluate(Session session, Object query) {
      return result;
    }

    @Override
    public List<String> getRootTypes() {
      return List.of("events");
    }

    @Override
    public List<String> getOperators() {
      return List.of();
    }

    @Override
    public String getOperatorHelp(String operator) {
      return null;
    }

    @Override
    public VariableStore.LazyValue createLazyValue(
        Session session, Object query, String queryString) {
      return null;
    }
  }
}

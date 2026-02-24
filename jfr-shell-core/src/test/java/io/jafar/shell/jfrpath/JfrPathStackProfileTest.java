package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JfrPathStackProfileTest {

  /**
   * Build a synthetic JFR-style event with a stackTrace containing the given method frames. Frames
   * are stored in JFR order: index 0 = top of stack (most recent/leaf).
   */
  private static Map<String, Object> sampleEvent(long startTime, String... methods) {
    Object[] frames = new Object[methods.length];
    for (int i = 0; i < methods.length; i++) {
      String[] parts = methods[i].split("\\.");
      String className = parts.length > 1 ? parts[0] : "";
      String methodName = parts.length > 1 ? parts[1] : parts[0];
      Map<String, Object> method = Map.of("type", Map.of("name", className), "name", methodName);
      frames[i] = Map.of("method", method);
    }
    Map<String, Object> stackTrace = Map.of("frames", frames);
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("startTime", startTime);
    event.put("stackTrace", stackTrace);
    return event;
  }

  private JFRSession mockSession() {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/dummy.jfr"));
    return session;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> profile(Map<String, Object> row) {
    return (Map<String, Object>) row.get("profile");
  }

  @Test
  void outputColumnsPresent() throws Exception {
    JFRSession session = mockSession();
    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) ->
                consumer.accept(
                    new JfrPathEvaluator.Event(
                        "jdk.ExecutionSample", sampleEvent(100, "A.leaf", "B.caller")));

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | stackprofile()");
    List<Map<String, Object>> rows = eval.evaluate(session, q);

    assertFalse(rows.isEmpty());
    Map<String, Object> first = rows.get(0);
    // Table columns
    assertTrue(first.containsKey("timeBuckets"));
    assertInstanceOf(String.class, first.get("timeBuckets"));
    assertTrue(first.containsKey(" "));
    assertInstanceOf(String.class, first.get(" "));
    assertTrue(first.containsKey("method"));
    assertInstanceOf(String.class, first.get("method"));
    // Detail pane profile map
    assertTrue(first.containsKey("profile"));
    Map<String, Object> p = profile(first);
    assertTrue(p.containsKey("method"));
    assertInstanceOf(String.class, p.get("method"));
    assertTrue(p.containsKey("self"));
    assertTrue(p.containsKey("total"));
    assertTrue(p.containsKey("totalPct"));
  }

  @Test
  void topDownOrdering() throws Exception {
    // In JFR, frames[0] = leaf. top-down reverses so entry point comes first.
    // Event: leaf=A.leaf, caller=B.caller
    // top-down tree: B.caller -> A.leaf
    JFRSession session = mockSession();
    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) ->
                consumer.accept(
                    new JfrPathEvaluator.Event(
                        "jdk.ExecutionSample", sampleEvent(100, "A.leaf", "B.caller")));

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | stackprofile(direction=top-down)");
    List<Map<String, Object>> rows = eval.evaluate(session, q);

    assertEquals(2, rows.size());
    // First row: root of tree (B.caller, the entry point) — no self samples, no marker
    assertEquals("B.caller", rows.get(0).get("method"));
    assertEquals("B.caller", profile(rows.get(0)).get("method"));
    // Second row: child (A.leaf) — has self samples, gets hotspot marker
    assertTrue(rows.get(1).get("method").toString().contains("A.leaf"));
    assertEquals("A.leaf", profile(rows.get(1)).get("method"));
  }

  @Test
  void bottomUpOrdering() throws Exception {
    // bottom-up keeps JFR order: leaf first
    // Event: leaf=A.leaf, caller=B.caller
    // bottom-up tree: A.leaf -> B.caller
    JFRSession session = mockSession();
    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) ->
                consumer.accept(
                    new JfrPathEvaluator.Event(
                        "jdk.ExecutionSample", sampleEvent(100, "A.leaf", "B.caller")));

    var eval = new JfrPathEvaluator(src);
    var q = JfrPathParser.parse("events/jdk.ExecutionSample | stackprofile(direction=bottom-up)");
    List<Map<String, Object>> rows = eval.evaluate(session, q);

    assertEquals(2, rows.size());
    // First row: leaf (A.leaf, hot method) — has self samples, gets hotspot marker
    assertTrue(rows.get(0).get("method").toString().contains("A.leaf"));
    // Second row: caller (B.caller) — leaf in bottom-up tree, has self samples
    assertTrue(rows.get(1).get("method").toString().contains("B.caller"));
  }

  @Test
  void minPctPruning() throws Exception {
    // Create 10 events on path A->B and 1 event on path A->C
    // With minPct=15, C should be pruned (1/11 = 9.1%)
    JFRSession session = mockSession();
    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              for (int i = 0; i < 10; i++) {
                consumer.accept(
                    new JfrPathEvaluator.Event(
                        "jdk.ExecutionSample", sampleEvent(100 + i, "B.hot", "A.entry")));
              }
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample", sampleEvent(200, "C.cold", "A.entry")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | stackprofile(direction=top-down, minPct=15)");
    List<Map<String, Object>> rows = eval.evaluate(session, q);

    // A.entry should be present (100%), B.hot should be present (~90.9%)
    // C.cold should be pruned (~9.1% < 15%)
    boolean hasCold = rows.stream().anyMatch(r -> r.get("method").toString().contains("C.cold"));
    assertFalse(hasCold, "C.cold should be pruned by minPct=15");

    boolean hasHot = rows.stream().anyMatch(r -> r.get("method").toString().contains("B.hot"));
    assertTrue(hasHot, "B.hot should be present");
  }

  @Test
  void timeBucketDistribution() throws Exception {
    // Create events at different times spread across 5 buckets
    JFRSession session = mockSession();
    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              // 5 events at timestamps 0, 25, 50, 75, 100
              for (int i = 0; i <= 4; i++) {
                consumer.accept(
                    new JfrPathEvaluator.Event(
                        "jdk.ExecutionSample", sampleEvent(i * 25L, "A.method", "B.entry")));
              }
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | stackprofile(direction=top-down, buckets=5, minPct=0)");
    List<Map<String, Object>> rows = eval.evaluate(session, q);

    assertFalse(rows.isEmpty());
    // Table-level timeBuckets should be a sparkline string
    String sparkline = (String) rows.get(0).get("timeBuckets");
    assertNotNull(sparkline);
    // Sparkline is stretched to fill SPARKLINE_WIDTH (30 chars)
    assertEquals(30, sparkline.length());
    // Profile should not contain timeBuckets
    assertFalse(profile(rows.get(0)).containsKey("timeBuckets"));
  }

  @Test
  void selfCountsCorrect() throws Exception {
    // Event 1: A.leaf -> B.mid -> C.entry (top-down: C.entry -> B.mid -> A.leaf)
    // Event 2: A.leaf -> B.mid -> C.entry
    // A.leaf should have self=2, B.mid self=0, C.entry self=0
    JFRSession session = mockSession();
    var src =
        (JfrPathEvaluator.EventSource)
            (recording, consumer) -> {
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample", sampleEvent(100, "A.leaf", "B.mid", "C.entry")));
              consumer.accept(
                  new JfrPathEvaluator.Event(
                      "jdk.ExecutionSample", sampleEvent(200, "A.leaf", "B.mid", "C.entry")));
            };

    var eval = new JfrPathEvaluator(src);
    var q =
        JfrPathParser.parse(
            "events/jdk.ExecutionSample | stackprofile(direction=top-down, minPct=0)");
    List<Map<String, Object>> rows = eval.evaluate(session, q);

    // top-down: C.entry (depth=0) -> B.mid (depth=1) -> A.leaf (depth=2)
    assertEquals(3, rows.size());
    // C.entry: total=2, self=0
    assertEquals(2L, profile(rows.get(0)).get("total"));
    assertEquals(0L, profile(rows.get(0)).get("self"));
    // B.mid: total=2, self=0
    assertEquals(2L, profile(rows.get(1)).get("total"));
    assertEquals(0L, profile(rows.get(1)).get("self"));
    // A.leaf: total=2, self=2
    assertEquals(2L, profile(rows.get(2)).get("total"));
    assertEquals(2L, profile(rows.get(2)).get("self"));
  }
}

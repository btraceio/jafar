package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.mcp.query.DefaultQueryParser;
import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPath;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code jfr_compare} must not call a frame a regression on counts sampling noise can produce. The
 * samples are scripted so each case states its counts exactly.
 */
class JfrCompareThinDataTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JafarMcpServer server;

  @AfterEach
  void tearDown() throws Exception {
    Method close = JafarMcpServer.class.getDeclaredMethod("handleJfrClose", Map.class);
    close.setAccessible(true);
    close.invoke(server, Map.of("closeAll", true));
  }

  @Test
  void oneSampleFrameInATinyRecordingIsNotARegression() throws Exception {
    // The shape of the original report: 7 and 3 samples, a frame going from 0 to 1.
    JsonNode result =
        compare(Map.of("base.Busy.run", 7L), Map.of("base.Busy.run", 2L, "new.Frame.run", 1L));

    assertEquals(0, result.get("findingCounts").get("WARNING").asInt(), result.toString());
    assertFalse(result.get("sufficientSamples").asBoolean());
    assertNoFrameFinding(result);
    assertHasFinding(result, "regression:insufficient-samples");
    assertFalse(hasFinding(result, "regression:none"), "too thin is not a clean bill of health");
    assertTrue(
        result.get("comparability").toString().contains("Too few samples"),
        result.get("comparability").toString());

    JsonNode frame = frame(result, "new.Frame.run");
    assertEquals("inconclusive", frame.get("direction").asString());
    assertFalse(frame.get("significant").asBoolean());
  }

  @Test
  void aLargeMoveInATinyRecordingIsStillNotARegression() throws Exception {
    // Passes a two-proportion test on paper, but that test means nothing on ten samples.
    JsonNode result =
        compare(Map.of("base.Busy.run", 10L), Map.of("base.Busy.run", 3L, "new.Frame.run", 7L));

    assertFalse(result.get("sufficientSamples").asBoolean());
    assertNoFrameFinding(result);
  }

  @Test
  void aFewSamplesOfAFrameInAnAdequateRecordingAreNotARegression() throws Exception {
    // 2 points clears the 1-point noise floor, yet 0 to 2 of 100 is what chance looks like.
    JsonNode result =
        compare(Map.of("base.Busy.run", 100L), Map.of("base.Busy.run", 98L, "new.Frame.run", 2L));

    assertTrue(result.get("sufficientSamples").asBoolean());
    assertNoFrameFinding(result);
    assertHasFinding(result, "regression:none");
    assertEquals("inconclusive", frame(result, "new.Frame.run").get("direction").asString());
  }

  @Test
  void aRealShiftIsStillReported() throws Exception {
    JsonNode result =
        compare(
            Map.of("base.Busy.run", 1000L), Map.of("base.Busy.run", 800L, "new.Frame.run", 200L));

    assertTrue(result.get("sufficientSamples").asBoolean());
    assertEquals(1, result.get("findingCounts").get("WARNING").asInt(), result.toString());
    assertHasFinding(result, "regression:frame-new-frame-run");

    JsonNode frame = frame(result, "new.Frame.run");
    assertEquals("regression", frame.get("direction").asString());
    assertTrue(frame.get("significant").asBoolean());
    assertEquals("improvement", frame(result, "base.Busy.run").get("direction").asString());
  }

  @Test
  void aRateJumpMeasuredOverAThinSampleSpanIsNotARegression() throws Exception {
    // Rates use the span of the samples as their denominator, so two samples can invent any rate.
    JsonNode result =
        compare(
            Map.of("base.Busy.run", 2L),
            Map.of("base.Busy.run", 2L),
            Map.of("jdk.ExecutionSample", 2L, "jdk.GarbageCollection", 2L),
            Map.of("jdk.ExecutionSample", 2L, "jdk.GarbageCollection", 200L));

    assertFalse(result.get("sufficientSamples").asBoolean());
    assertEquals(0, result.get("findingCounts").get("WARNING").asInt(), result.toString());
  }

  private JsonNode compare(Map<String, Long> baseline, Map<String, Long> candidate)
      throws Exception {
    return compare(baseline, candidate, Map.of(), Map.of());
  }

  private JsonNode compare(
      Map<String, Long> baseline,
      Map<String, Long> candidate,
      Map<String, Long> baselineCounts,
      Map<String, Long> candidateCounts)
      throws Exception {
    Path base = SimpleJfrFileBuilder.createExecutionSampleFile(1);
    Path cand = SimpleJfrFileBuilder.createExecutionSampleFile(1);

    QueryEvaluator evaluator =
        new QueryEvaluator() {
          private boolean isBaseline(JFRSession session) {
            return session.getRecordingPath().equals(base);
          }

          @Override
          public List<Map<String, Object>> evaluate(JFRSession session, JfrPath.Query query) {
            return samples(isBaseline(session) ? baseline : candidate);
          }

          @Override
          public void consume(
              JFRSession session, JfrPath.Query query, Consumer<Map<String, Object>> consumer) {
            samples(isBaseline(session) ? baseline : candidate).forEach(consumer);
          }

          @Override
          public Map<String, Long> countAllEventTypes(JFRSession session) {
            return isBaseline(session) ? baselineCounts : candidateCounts;
          }
        };

    server =
        new JafarMcpServer(
            new SessionRegistry(),
            new HeapSessionRegistry(),
            new PprofSessionRegistry(),
            new OtlpSessionRegistry(),
            evaluator,
            new DefaultQueryParser());

    String baseId = invoke("handleJfrOpen", Map.of("path", base.toString())).get("id").asString();
    String candId = invoke("handleJfrOpen", Map.of("path", cand.toString())).get("id").asString();
    return invoke(
        "handleJfrCompare",
        Map.of(
            "baselineSessionId", baseId,
            "candidateSessionId", candId,
            "eventType", "jdk.ExecutionSample"));
  }

  /** One sample per count, one second apart, each with {@code method} as its only frame. */
  private static List<Map<String, Object>> samples(Map<String, Long> leafCounts) {
    List<Map<String, Object>> events = new ArrayList<>();
    long startTime = 0;
    for (Map.Entry<String, Long> leaf : leafCounts.entrySet()) {
      int dot = leaf.getKey().lastIndexOf('.');
      Map<String, Object> method =
          Map.of(
              "type", Map.of("name", leaf.getKey().substring(0, dot)),
              "name", leaf.getKey().substring(dot + 1));
      Map<String, Object> stackTrace = Map.of("frames", List.of(Map.of("method", method)));
      for (long i = 0; i < leaf.getValue(); i++) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("startTime", startTime);
        event.put("stackTrace", stackTrace);
        events.add(event);
        startTime += 1_000_000_000L;
      }
    }
    return events;
  }

  private JsonNode invoke(String handler, Map<String, Object> args) throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod(handler, Map.class);
    method.setAccessible(true);
    CallToolResult result = (CallToolResult) method.invoke(server, args);
    assertFalse(result.isError(), handler + " failed: " + result.content());
    return MAPPER.readTree(((TextContent) result.content().get(0)).text());
  }

  private static JsonNode frame(JsonNode result, String method) {
    for (JsonNode frame : result.get("frames")) {
      if (frame.get("method").asString().equals(method)) {
        return frame;
      }
    }
    throw new AssertionError("No frame " + method + " in " + result.get("frames"));
  }

  private static boolean hasFinding(JsonNode result, String id) {
    for (JsonNode finding : result.get("findings")) {
      if (finding.get("id").asString().equals(id)) {
        return true;
      }
    }
    return false;
  }

  private static void assertHasFinding(JsonNode result, String id) {
    assertTrue(hasFinding(result, id), "Expected " + id + " in " + result.get("findings"));
  }

  private static void assertNoFrameFinding(JsonNode result) {
    for (JsonNode finding : result.get("findings")) {
      assertFalse(finding.get("id").asString().startsWith("regression:frame-"), finding.toString());
    }
  }
}

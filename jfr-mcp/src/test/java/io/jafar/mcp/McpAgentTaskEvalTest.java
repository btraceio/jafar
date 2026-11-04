package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * Dogfood evals: representative agent tasks, scripted end to end through the real stdio server,
 * asserted on the payload (R9), not on the exit status.
 *
 * <p>The guidance-contract tests pin what the surfaces <em>say</em> (examples parse, tools
 * referenced exist); these evals pin that an agent following them still gets the right
 * <em>answer</em>: a hot-method ranking that actually ranks, two tools agreeing on the same
 * question, and a documented example query that runs against a real recording. Run them whenever
 * guidance, prompts or tool descriptions change — they are part of the smoke set every PR runs
 * ({@code ./gradlew :jfr-mcp:endToEndTest --tests '*McpAgentTaskEvalTest*'}).
 */
@Tag("e2e")
class McpAgentTaskEvalTest {

  @Test
  @Timeout(120)
  void evalWhichMethodConsumedTheMostCpu() throws Exception {
    try (McpStdioProcessHarness mcp = new McpStdioProcessHarness("eval-hot")) {
      Path fixture = McpEndToEndTest.findTestJfr();
      JsonNode open = mcp.call("jfr_open", Map.of("path", fixture.toString(), "alias", "eval-hot"));
      assertTrue(mcp.contentJson(open).path("id").asInt() > 0, () -> "open=" + open);

      // Task: "which method consumed the most CPU?" — through the tool an agent is told to use.
      JsonNode hot = mcp.call("jfr_hotmethods", Map.of("sessionId", "eval-hot"));
      JsonNode hotPayload = mcp.contentJson(hot);
      String eventType = hotPayload.path("eventType").asString();
      int totalSamples = hotPayload.path("totalSamples").asInt();
      assertFalse(eventType.isEmpty(), () -> "hotmethods=" + hot);
      assertTrue(totalSamples > 0, () -> "hotmethods=" + hot);

      JsonNode methods = hotPayload.path("methods");
      assertTrue(methods.isArray() && methods.size() > 0, () -> "hotmethods=" + hot);
      String topMethod = methods.get(0).path("method").asString();
      // A native leaf frame (e.g. "libmPow" in this recording) has no class.method dot —
      // the first run of this eval assumed it must, and the real recording corrected it.
      assertFalse(topMethod.isEmpty(), () -> "hotmethods=" + hot);
      // The ranking must actually rank: non-increasing sample counts.
      for (int i = 1; i < methods.size(); i++) {
        assertTrue(
            methods.get(i - 1).path("samples").asLong() >= methods.get(i).path("samples").asLong(),
            () -> "hotmethods ranking is not sorted: " + hot);
      }

      // Cross-check with an independent surface: the same events through jfr_query.
      // Two tools answering the same question must agree (R9: read the payload, both).
      JsonNode count =
          mcp.call(
              "jfr_query",
              Map.of("sessionId", "eval-hot", "query", "events/" + eventType + " | count()"));
      JsonNode countPayload = mcp.contentJson(count);
      assertEquals(
          totalSamples,
          countPayload.at("/results/0/count").asInt(),
          () ->
              "hotmethods' totalSamples must equal the queried event count of its own"
                  + " eventType: hotmethods="
                  + hot
                  + " query="
                  + count);
    }
  }

  @Test
  @Timeout(120)
  void evalCompareTwoRecordingsOfTheSameFileReportsNoChange() throws Exception {
    try (McpStdioProcessHarness mcp = new McpStdioProcessHarness("eval-compare")) {
      Path fixture = McpEndToEndTest.findTestJfr();
      mcp.call("jfr_open", Map.of("path", fixture.toString(), "alias", "eval-base"));
      mcp.call("jfr_open", Map.of("path", fixture.toString(), "alias", "eval-cand"));

      // Task: "compare these two recordings" — same file twice: the answer must be "no change".
      JsonNode compare =
          mcp.call(
              "jfr_compare",
              Map.of("baselineSessionId", "eval-base", "candidateSessionId", "eval-cand"));
      JsonNode payload = mcp.contentJson(compare);
      assertEquals(
          payload.at("/baseline/samples").asLong(),
          payload.at("/candidate/samples").asLong(),
          () -> "compare=" + compare);
      // Identical inputs: no frame can be reported as changed, and no metric can move.
      assertEquals(
          0, payload.path("frames").size(), () -> "identical recordings, but frames=" + compare);
      JsonNode metrics = payload.path("metrics");
      assertTrue(metrics.isArray() && metrics.size() > 0, () -> "compare=" + compare);
      for (JsonNode metric : metrics) {
        assertEquals(
            metric.path("baselineCount").asLong(),
            metric.path("candidateCount").asLong(),
            () -> "identical recordings, but metric moved: " + metric + " in " + compare);
        assertEquals(0.0, metric.path("deltaRate").asDouble(), () -> "compare=" + compare);
      }
    }
  }

  @Test
  @Timeout(120)
  void evalDocumentedExecutionSampleExampleAnswersOnARealRecording() throws Exception {
    // The guidance-tie: an example the help actually documents (extracted the way an agent
    // reads the help, not a string duplicated here) must not merely parse — it must run
    // against the real recording. Delete the documented sentence and this eval fails; that
    // is its R5.
    List<QueryExamples.Candidate> examples = documentedExecutionSampleCounts();
    assertFalse(
        examples.isEmpty(),
        "no documented ExecutionSample count() example found in the guidance surfaces —"
            + " an agent is no longer taught the most basic profiling question");

    try (McpStdioProcessHarness mcp = new McpStdioProcessHarness("eval-doc")) {
      Path fixture = McpEndToEndTest.findTestJfr();
      mcp.call("jfr_open", Map.of("path", fixture.toString(), "alias", "eval-doc"));
      String query = examples.get(0).query();
      JsonNode result = mcp.call("jfr_query", Map.of("sessionId", "eval-doc", "query", query));
      JsonNode resultPayload = mcp.contentJson(result);
      assertEquals(
          1,
          resultPayload.path("resultCount").asInt(),
          () -> "documented example " + query + " did not answer: " + result);
      assertTrue(
          resultPayload.at("/results/0/count").canConvertToLong(),
          () -> "documented example " + query + " returned no count: " + result);
    }
  }

  /** Every documented ExecutionSample {@code count()} example, from the live guidance. */
  private static List<QueryExamples.Candidate> documentedExecutionSampleCounts() {
    GuidanceSurfaces surfaces = new GuidanceSurfaces();
    return surfaces.surfaces().stream()
        .filter(s -> s.language() == GuidanceSurfaces.QueryLanguage.JFRPATH)
        .flatMap(s -> QueryExamples.extract(s.name(), s.text(), s.language()).stream())
        .filter(c -> c.query().matches("events/jdk\\.ExecutionSample \\| count\\(\\)"))
        .toList();
  }
}

package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import io.jafar.otlp.MinimalOtlpBuilder;
import io.jafar.pprof.MinimalPprofBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/** One useful black-box workflow per format through {@code java -jar ... --stdio}. */
@Tag("e2e")
class McpAgentWorkflowEndToEndTest {

  private static final String[] WORKFLOW_ALIASES = {
    "jfr-workflow", "hprof-workflow", "pprof-workflow", "otlp-workflow"
  };

  @Test
  @Timeout(120)
  void jfrWorkflowReadsFixtureAndKeepsTheServerUsable() throws Exception {
    try (McpStdioProcessHarness mcp = new McpStdioProcessHarness("jfr-workflow")) {
      mcp.assertFreshSessions(WORKFLOW_ALIASES);
      Path fixture = McpEndToEndTest.findTestJfr();
      JsonNode open =
          mcp.call("jfr_open", Map.of("path", fixture.toString(), "alias", "jfr-workflow"));
      JsonNode openPayload = mcp.contentJson(open);
      assertEquals(1, openPayload.path("id").asInt(), () -> "open=" + open);
      assertEquals("jfr-workflow", openPayload.path("alias").asString(), () -> "open=" + open);
      assertEquals(fixture.toString(), openPayload.path("path").asString(), () -> "open=" + open);
      assertEquals(204, openPayload.path("availableTypes").asInt(), () -> "open=" + open);
      assertEquals(2, openPayload.path("chunkCount").asInt(), () -> "open=" + open);
      JsonNode summary = mcp.call("jfr_summary", Map.of("sessionId", "jfr-workflow"));
      JsonNode summaryPayload = mcp.contentJson(summary);
      assertEquals(1, summaryPayload.path("sessionId").asInt(), () -> "summary=" + summary);
      assertEquals(
          fixture.toString(),
          summaryPayload.path("recordingPath").asString(),
          () -> "summary=" + summary);
      JsonNode query =
          mcp.call(
              "jfr_query",
              Map.of(
                  "sessionId",
                  "jfr-workflow",
                  "query",
                  "events/jdk.JVMInformation | select(jvmVersion, jvmName)"));
      JsonNode queryPayload = mcp.contentJson(query);
      assertEquals(2, queryPayload.path("resultCount").asInt(), () -> "query=" + query);
      assertEquals(2, queryPayload.path("results").size(), () -> "query=" + query);
      assertEquals(
          "OpenJDK 64-Bit Server VM",
          queryPayload.at("/results/0/jvmName").asString(),
          () -> "query=" + query);
      JsonNode diagnose = mcp.call("jfr_diagnose", Map.of("sessionId", "jfr-workflow"), 4);
      assertTrue(mcp.contentJson(diagnose).has("findings"), () -> "diagnose=" + diagnose);
      mcp.assertProgress(4);
      mcp.call("jfr_close", Map.of("sessionId", "jfr-workflow"));
      mcp.call("jfr_help", Map.of("topic", "tools"));
    }
  }

  @Test
  @Timeout(120)
  void hprofWorkflowUsesPromotedSyntheticWriter() throws Exception {
    try (McpStdioProcessHarness mcp = new McpStdioProcessHarness("hprof-workflow")) {
      mcp.assertFreshSessions(WORKFLOW_ALIASES);
      Path fixture = mcp.root().resolve("tier1-seven-objects.hprof");
      SyntheticHeapDumpGenerator.generateMinimalHeapDump(fixture, 7);
      JsonNode open =
          mcp.call("hdump_open", Map.of("path", fixture.toString(), "alias", "hprof-workflow"));
      JsonNode openPayload = mcp.contentJson(open);
      assertEquals(1, openPayload.path("id").asInt(), () -> "open=" + open);
      assertEquals("hprof-workflow", openPayload.path("alias").asString(), () -> "open=" + open);
      assertEquals(7, openPayload.path("objectCount").asInt(), () -> "open=" + open);
      assertEquals(2, openPayload.path("classCount").asInt(), () -> "open=" + open);
      JsonNode summary = mcp.call("hdump_summary", Map.of("sessionId", "hprof-workflow"));
      JsonNode summaryPayload = mcp.contentJson(summary);
      assertEquals(7, summaryPayload.path("objectCount").asInt(), () -> "summary=" + summary);
      assertEquals(2, summaryPayload.path("classCount").asInt(), () -> "summary=" + summary);
      JsonNode query =
          mcp.call(
              "hdump_query",
              Map.of("sessionId", "hprof-workflow", "query", "classes | top(10, instanceCount)"));
      JsonNode queryPayload = mcp.contentJson(query);
      assertEquals(2, queryPayload.path("resultCount").asInt(), () -> "query=" + query);
      assertEquals(2, queryPayload.path("results").size(), () -> "query=" + query);
      assertTrue(
          queryPayload.path("results").toString().contains("java.lang.Object")
              && queryPayload.path("results").toString().contains("java.lang.String"),
          () -> "query=" + query);
      JsonNode report =
          mcp.call("hdump_report", Map.of("sessionId", "hprof-workflow", "focus", "histogram"));
      assertTrue(mcp.contentJson(report).has("findings"), () -> "report=" + report);
      mcp.call("hdump_close", Map.of("sessionId", "hprof-workflow"));
      mcp.call("jfr_help", Map.of("topic", "tools"));
    }
  }

  @Test
  @Timeout(120)
  void pprofWorkflowPreservesDistinctiveFunctionThroughStdio() throws Exception {
    try (McpStdioProcessHarness mcp = new McpStdioProcessHarness("pprof-workflow")) {
      mcp.assertFreshSessions(WORKFLOW_ALIASES);
      Path fixture = pprofFixture(mcp.root());
      JsonNode open =
          mcp.call("pprof_open", Map.of("path", fixture.toString(), "alias", "pprof-workflow"));
      assertEquals(1, mcp.contentJson(open).path("id").asInt(), () -> "open=" + open);
      JsonNode summary = mcp.call("pprof_summary", Map.of("sessionId", "pprof-workflow"));
      assertTrue(
          mcp.contentJson(summary).toString().contains("tier1.pprof.UniqueCpuMethod"),
          () -> "summary=" + summary);
      JsonNode query =
          mcp.call(
              "pprof_query",
              Map.of("sessionId", "pprof-workflow", "query", "samples | top(10, cpu)"));
      JsonNode queryPayload = mcp.contentJson(query);
      assertEquals(1, queryPayload.path("resultCount").asInt(), () -> "query=" + query);
      assertEquals(4242L, queryPayload.at("/results/0/cpu").asLong(), () -> "query=" + query);
      assertEquals(
          "tier1.pprof.UniqueCpuMethod",
          queryPayload.at("/results/0/stackTrace/0/name").asString(),
          () -> "query=" + query);
      assertEquals(
          42, queryPayload.at("/results/0/stackTrace/0/line").asInt(), () -> "query=" + query);
      JsonNode use = mcp.call("pprof_use", Map.of("sessionId", "pprof-workflow"), 5);
      assertFalse(mcp.contentJson(use).isEmpty(), () -> "use=" + use);
      mcp.assertProgress(5);
      mcp.call("pprof_close", Map.of("sessionId", "pprof-workflow"));
      mcp.call("jfr_help", Map.of("topic", "tools"));
    }
  }

  @Test
  @Timeout(120)
  void otlpWorkflowPreservesDistinctiveFunctionThroughStdio() throws Exception {
    try (McpStdioProcessHarness mcp = new McpStdioProcessHarness("otlp-workflow")) {
      mcp.assertFreshSessions(WORKFLOW_ALIASES);
      Path fixture = otlpFixture(mcp.root());
      JsonNode open =
          mcp.call("otlp_open", Map.of("path", fixture.toString(), "alias", "otlp-workflow"));
      assertEquals(1, mcp.contentJson(open).path("id").asInt(), () -> "open=" + open);
      JsonNode summary = mcp.call("otlp_summary", Map.of("sessionId", "otlp-workflow"));
      assertTrue(
          mcp.contentJson(summary).toString().contains("tier1.otlp.UniqueCpuMethod"),
          () -> "summary=" + summary);
      JsonNode query =
          mcp.call(
              "otlp_query",
              Map.of("sessionId", "otlp-workflow", "query", "samples | top(10, cpu)"));
      JsonNode queryPayload = mcp.contentJson(query);
      assertEquals(1, queryPayload.path("resultCount").asInt(), () -> "query=" + query);
      assertEquals(4343L, queryPayload.at("/results/0/cpu").asLong(), () -> "query=" + query);
      assertEquals(
          "tier1-main", queryPayload.at("/results/0/thread").asString(), () -> "query=" + query);
      assertEquals(
          "tier1.otlp.UniqueCpuMethod",
          queryPayload.at("/results/0/stackTrace/0/name").asString(),
          () -> "query=" + query);
      assertEquals(
          42, queryPayload.at("/results/0/stackTrace/0/line").asInt(), () -> "query=" + query);
      JsonNode use = mcp.call("otlp_use", Map.of("sessionId", "otlp-workflow"), 6);
      assertFalse(mcp.contentJson(use).isEmpty(), () -> "use=" + use);
      mcp.assertProgress(6);
      mcp.call("otlp_close", Map.of("sessionId", "otlp-workflow"));
      mcp.call("jfr_help", Map.of("topic", "tools"));
    }
  }

  private static Path pprofFixture(Path dir) throws Exception {
    MinimalPprofBuilder builder = new MinimalPprofBuilder();
    int cpu = builder.addString("cpu");
    int nanos = builder.addString("nanoseconds");
    builder.addSampleType(cpu, nanos).setDurationNanos(1_000_000_000L);
    long function =
        builder.addFunction(
            builder.addString("tier1.pprof.UniqueCpuMethod"), builder.addString("Tier1.java"));
    long location = builder.addLocation(function, 42);
    builder.addSample(List.of(location), List.of(4242L), List.of());
    return builder.write(dir);
  }

  private static Path otlpFixture(Path dir) throws Exception {
    MinimalOtlpBuilder builder = new MinimalOtlpBuilder();
    int cpu = builder.addString("cpu");
    int nanos = builder.addString("nanoseconds");
    builder.setSampleType(cpu, nanos).setDurationNanos(1_000_000_000L);
    int function =
        builder.addFunction(
            builder.addString("tier1.otlp.UniqueCpuMethod"), builder.addString("Tier1.java"));
    int location = builder.addLocation(function, 42);
    int stack = builder.addStack(List.of(location));
    int attribute = builder.addAttribute(builder.addString("thread"), "tier1-main");
    builder.addSample(stack, List.of(attribute), List.of(4343L));
    return builder.write(dir);
  }
}

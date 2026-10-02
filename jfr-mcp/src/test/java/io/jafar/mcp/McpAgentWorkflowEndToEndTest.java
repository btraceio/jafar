package io.jafar.mcp;

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

  @Test
  @Timeout(120)
  void jfrWorkflowReadsFixtureAndKeepsTheServerUsable() throws Exception {
    try (McpStdioProcessHarness mcp = new McpStdioProcessHarness("jfr-workflow")) {
      JsonNode open =
          mcp.call(
              "jfr_open",
              Map.of("path", McpEndToEndTest.findTestJfr().toString(), "alias", "jfr-workflow"));
      assertTrue(mcp.contentJson(open).path("id").isIntegralNumber(), () -> "open=" + open);
      JsonNode summary = mcp.call("jfr_summary", Map.of("sessionId", "jfr-workflow"));
      assertFalse(mcp.contentJson(summary).isEmpty(), () -> "summary=" + summary);
      JsonNode query =
          mcp.call(
              "jfr_query",
              Map.of(
                  "sessionId",
                  "jfr-workflow",
                  "query",
                  "events/jdk.JVMInformation | select(jvmVersion, jvmName)"));
      assertFalse(mcp.contentJson(query).path("results").isMissingNode(), () -> "query=" + query);
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
      Path fixture = mcp.root().resolve("tier1-seven-objects.hprof");
      SyntheticHeapDumpGenerator.generateMinimalHeapDump(fixture, 7);
      JsonNode open =
          mcp.call("hdump_open", Map.of("path", fixture.toString(), "alias", "hprof-workflow"));
      assertTrue(mcp.contentJson(open).path("id").isIntegralNumber(), () -> "open=" + open);
      JsonNode summary = mcp.call("hdump_summary", Map.of("sessionId", "hprof-workflow"));
      assertTrue(
          mcp.contentJson(summary).path("objectCount").asInt() >= 7, () -> "summary=" + summary);
      JsonNode query =
          mcp.call(
              "hdump_query",
              Map.of("sessionId", "hprof-workflow", "query", "classes | top(10, instanceCount)"));
      assertTrue(mcp.contentJson(query).path("resultCount").asInt() > 0, () -> "query=" + query);
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
      Path fixture = pprofFixture(mcp.root());
      JsonNode open =
          mcp.call("pprof_open", Map.of("path", fixture.toString(), "alias", "pprof-workflow"));
      assertTrue(mcp.contentJson(open).path("id").isIntegralNumber(), () -> "open=" + open);
      JsonNode summary = mcp.call("pprof_summary", Map.of("sessionId", "pprof-workflow"));
      assertTrue(
          mcp.contentJson(summary).toString().contains("tier1.pprof.UniqueCpuMethod"),
          () -> "summary=" + summary);
      JsonNode query =
          mcp.call(
              "pprof_query",
              Map.of("sessionId", "pprof-workflow", "query", "samples | top(10, cpu)"));
      assertTrue(
          mcp.contentJson(query).toString().contains("tier1.pprof.UniqueCpuMethod"),
          () -> "query=" + query);
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
      Path fixture = otlpFixture(mcp.root());
      JsonNode open =
          mcp.call("otlp_open", Map.of("path", fixture.toString(), "alias", "otlp-workflow"));
      assertTrue(mcp.contentJson(open).path("id").isIntegralNumber(), () -> "open=" + open);
      JsonNode summary = mcp.call("otlp_summary", Map.of("sessionId", "otlp-workflow"));
      assertTrue(
          mcp.contentJson(summary).toString().contains("tier1.otlp.UniqueCpuMethod"),
          () -> "summary=" + summary);
      JsonNode query =
          mcp.call(
              "otlp_query",
              Map.of("sessionId", "otlp-workflow", "query", "samples | top(10, cpu)"));
      assertTrue(
          mcp.contentJson(query).toString().contains("tier1.otlp.UniqueCpuMethod"),
          () -> "query=" + query);
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

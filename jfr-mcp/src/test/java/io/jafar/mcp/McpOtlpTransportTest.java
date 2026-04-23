package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.otlp.shell.MinimalOtlpBuilder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Transport-level integration tests for all otlp_* tools. Exercises the full JSON-RPC over stdio
 * path using in-process pipes and a synthetic OTLP profile.
 */
@Tag("integration")
class McpOtlpTransportTest extends McpTransportTestBase {

  @TempDir Path tempDir;

  private String profilePath;

  @BeforeEach
  void setUp() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    int cpuIdx = b.addString("cpu");
    int nsIdx = b.addString("nanoseconds");
    int threadKey = b.addString("thread");
    b.setSampleType(cpuIdx, nsIdx);
    b.setDurationNanos(30_000_000_000L);

    int fnWork = b.addFunction(b.addString("doWork"), b.addString("Worker.java"));
    int fnAlloc = b.addFunction(b.addString("allocBuffer"), b.addString("Buf.java"));
    int fnWait = b.addFunction(b.addString("park"), b.addString("LockSupport.java"));
    int fnMain = b.addFunction(b.addString("main"), b.addString("Main.java"));

    int locWork = b.addLocation(fnWork, 20);
    int locAlloc = b.addLocation(fnAlloc, 5);
    int locWait = b.addLocation(fnWait, 1);
    int locMain = b.addLocation(fnMain, 10);

    int stackWork = b.addStack(List.of(locWork, locMain));
    int stackAlloc = b.addStack(List.of(locAlloc, locMain));
    int stackWait = b.addStack(List.of(locWait, locMain));

    int attrWorkerA = b.addAttribute(threadKey, "workerA");
    int attrWorkerB = b.addAttribute(threadKey, "workerB");
    int attrMain = b.addAttribute(threadKey, "main");

    b.addSample(stackWork, List.of(attrWorkerA), List.of(5_000_000L));
    b.addSample(stackWork, List.of(attrWorkerA), List.of(3_000_000L));
    b.addSample(stackAlloc, List.of(attrWorkerB), List.of(1_000_000L));
    b.addSample(stackWait, List.of(attrMain), List.of(500_000L));

    profilePath = b.write(tempDir).toString();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_open
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpOpenReturnsSessionInfo() throws Exception {
    JsonNode resp = harness.callTool(1, "otlp_open", "{\"path\":\"" + profilePath + "\"}");
    assertSuccess(resp, 1);
    assertTrue(resp.at("/result/content/0/text").asText().contains("id"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_close
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpCloseSucceeds() throws Exception {
    harness.callTool(1, "otlp_open", "{\"path\":\"" + profilePath + "\",\"alias\":\"o\"}");
    JsonNode resp = harness.callTool(2, "otlp_close", "{\"sessionId\":\"o\"}");
    assertSuccess(resp, 2);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_query
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpQueryCountReturnsResult() throws Exception {
    harness.callTool(1, "otlp_open", "{\"path\":\"" + profilePath + "\"}");
    JsonNode resp = harness.callTool(2, "otlp_query", "{\"query\":\"samples | count\"}");
    assertSuccess(resp, 2);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_summary
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpSummaryReturnsSampleInfo() throws Exception {
    harness.callTool(1, "otlp_open", "{\"path\":\"" + profilePath + "\"}");
    JsonNode resp = harness.callTool(2, "otlp_summary", "{}");
    assertSuccess(resp, 2);
    assertTrue(resp.at("/result/content/0/text").asText().contains("sessionId"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpFlamegraphReturnsRows() throws Exception {
    harness.callTool(1, "otlp_open", "{\"path\":\"" + profilePath + "\"}");
    JsonNode resp = harness.callTool(2, "otlp_flamegraph", "{}");
    assertSuccess(resp, 2);
    assertTrue(resp.at("/result/content/0/text").asText().contains("rows"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_use
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpUseReturnsReport() throws Exception {
    harness.callTool(1, "otlp_open", "{\"path\":\"" + profilePath + "\"}");
    JsonNode resp = harness.callTool(2, "otlp_use", "{}");
    assertSuccess(resp, 2);
    assertTrue(resp.at("/result/content/0/text").asText().contains("USE"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_help
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void otlpHelpReturnsContent() throws Exception {
    JsonNode resp = harness.callTool(1, "otlp_help", "{}");
    assertSuccess(resp, 1);
    assertTrue(resp.at("/result/content/0/text").asText().length() > 0);
  }
}

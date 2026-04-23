package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.pprof.shell.MinimalPprofBuilder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Transport-level integration tests for all pprof_* tools. Exercises the full JSON-RPC over stdio
 * path using in-process pipes and a synthetic pprof profile.
 */
@Tag("integration")
class McpPprofTransportTest extends McpTransportTestBase {

  @TempDir Path tempDir;

  private String profilePath;

  @BeforeEach
  void setUp() throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    int thread = b.addString("thread");
    int workerA = b.addString("workerA");
    int workerB = b.addString("workerB");
    int mainStr = b.addString("main");

    b.addSampleType(cpu, ns);
    b.setDurationNanos(30_000_000_000L);

    long fnMain = b.addFunction(b.addString("main"), b.addString("Main.java"));
    long fnWork = b.addFunction(b.addString("doWork"), b.addString("Worker.java"));
    long fnAlloc = b.addFunction(b.addString("allocBuffer"), b.addString("Buf.java"));
    long fnWait = b.addFunction(b.addString("park"), b.addString("LockSupport.java"));

    long locMain = b.addLocation(fnMain, 10);
    long locWork = b.addLocation(fnWork, 20);
    long locAlloc = b.addLocation(fnAlloc, 5);
    long locWait = b.addLocation(fnWait, 1);

    b.addSample(
        List.of(locWork, locMain),
        List.of(5_000_000L),
        List.of(new long[] {thread, workerA, 0, 0}));
    b.addSample(
        List.of(locWork, locMain),
        List.of(3_000_000L),
        List.of(new long[] {thread, workerA, 0, 0}));
    b.addSample(
        List.of(locAlloc, locMain),
        List.of(1_000_000L),
        List.of(new long[] {thread, workerB, 0, 0}));
    b.addSample(
        List.of(locWait, locMain), List.of(500_000L), List.of(new long[] {thread, mainStr, 0, 0}));

    profilePath = b.write(tempDir).toString();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_open
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofOpenReturnsSessionInfo() throws Exception {
    JsonNode resp = harness.callTool(1, "pprof_open", "{\"path\":\"" + profilePath + "\"}");
    assertSuccess(resp, 1);
    String text = resp.at("/result/content/0/text").asText();
    assertTrue(text.contains("id"), "response must include session id");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_close
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofCloseSucceeds() throws Exception {
    harness.callTool(1, "pprof_open", "{\"path\":\"" + profilePath + "\",\"alias\":\"p\"}");
    JsonNode resp = harness.callTool(2, "pprof_close", "{\"sessionId\":\"p\"}");
    assertSuccess(resp, 2);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_query
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofQueryCountReturnsResult() throws Exception {
    harness.callTool(1, "pprof_open", "{\"path\":\"" + profilePath + "\"}");
    JsonNode resp = harness.callTool(2, "pprof_query", "{\"query\":\"samples | count\"}");
    assertSuccess(resp, 2);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_summary
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofSummaryReturnsSampleTypes() throws Exception {
    harness.callTool(1, "pprof_open", "{\"path\":\"" + profilePath + "\"}");
    JsonNode resp = harness.callTool(2, "pprof_summary", "{}");
    assertSuccess(resp, 2);
    assertTrue(resp.at("/result/content/0/text").asText().contains("sampleTypes"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofFlamegraphReturnsRows() throws Exception {
    harness.callTool(1, "pprof_open", "{\"path\":\"" + profilePath + "\"}");
    JsonNode resp = harness.callTool(2, "pprof_flamegraph", "{}");
    assertSuccess(resp, 2);
    assertTrue(resp.at("/result/content/0/text").asText().contains("rows"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_hotmethods
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofHotmethodsReturnsTopMethods() throws Exception {
    harness.callTool(1, "pprof_open", "{\"path\":\"" + profilePath + "\"}");
    JsonNode resp = harness.callTool(2, "pprof_hotmethods", "{}");
    assertSuccess(resp, 2);
    assertTrue(resp.at("/result/content/0/text").asText().contains("topMethods"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_use
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofUseReturnsReport() throws Exception {
    harness.callTool(1, "pprof_open", "{\"path\":\"" + profilePath + "\"}");
    JsonNode resp = harness.callTool(2, "pprof_use", "{}");
    assertSuccess(resp, 2);
    assertTrue(resp.at("/result/content/0/text").asText().contains("USE"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_tsa
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofTsaReturnsThreadDistribution() throws Exception {
    harness.callTool(1, "pprof_open", "{\"path\":\"" + profilePath + "\"}");
    JsonNode resp = harness.callTool(2, "pprof_tsa", "{}");
    assertSuccess(resp, 2);
    assertTrue(resp.at("/result/content/0/text").asText().contains("TSA"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_help
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void pprofHelpReturnsContent() throws Exception {
    JsonNode resp = harness.callTool(1, "pprof_help", "{}");
    assertSuccess(resp, 1);
    assertTrue(resp.at("/result/content/0/text").asText().length() > 0);
  }
}

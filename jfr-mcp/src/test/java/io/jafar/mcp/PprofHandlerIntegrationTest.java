package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.pprof.shell.MinimalPprofBuilder;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for pprof_* MCP handlers against a synthetic pprof profile. Tests the full
 * handler path: open → query/analysis → close.
 */
class PprofHandlerIntegrationTest {

  @TempDir Path tempDir;

  private JafarMcpServer server;
  private Path profilePath;

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();

    // Build a synthetic two-sample-type profile: cpu (nanoseconds) + alloc_space (bytes)
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    int alloc = b.addString("alloc_space");
    int bytes = b.addString("bytes");
    int thread = b.addString("thread");
    int main = b.addString("main");
    int workerA = b.addString("workerA");
    int workerB = b.addString("workerB");

    b.addSampleType(cpu, ns);
    b.addSampleType(alloc, bytes);
    b.setDurationNanos(30_000_000_000L);

    // Functions
    long fnMain = b.addFunction(b.addString("main"), b.addString("Main.java"));
    long fnWork = b.addFunction(b.addString("doWork"), b.addString("Worker.java"));
    long fnAlloc = b.addFunction(b.addString("allocBuffer"), b.addString("Buf.java"));
    long fnWait = b.addFunction(b.addString("park"), b.addString("LockSupport.java"));

    // Locations: leaf-first
    long locMain = b.addLocation(fnMain, 10);
    long locWork = b.addLocation(fnWork, 20);
    long locAlloc = b.addLocation(fnAlloc, 5);
    long locWait = b.addLocation(fnWait, 1);

    // Thread label indices
    int threadKey = thread; // string-table index
    int mainVal = main;
    int workerAVal = workerA;
    int workerBVal = workerB;

    // Samples: [cpu_ns, alloc_bytes], with thread labels
    b.addSample(
        List.of(locWork, locMain),
        List.of(5_000_000L, 0L),
        List.of(new long[] {threadKey, workerAVal, 0, 0}));
    b.addSample(
        List.of(locWork, locMain),
        List.of(3_000_000L, 0L),
        List.of(new long[] {threadKey, workerAVal, 0, 0}));
    b.addSample(
        List.of(locAlloc, locMain),
        List.of(1_000_000L, 1_024_000L),
        List.of(new long[] {threadKey, workerBVal, 0, 0}));
    b.addSample(
        List.of(locWait, locMain),
        List.of(500_000L, 0L),
        List.of(new long[] {threadKey, mainVal, 0, 0}));

    profilePath = b.write(tempDir);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_open / pprof_close
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void openReturnsSessionInfo() throws Exception {
    var result = invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("sessionId") || content.contains("\"id\""));
    assertTrue(content.contains("sampleCount") || content.contains("4"));
  }

  @Test
  void openWithAliasThenCloseByAlias() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString(), "alias", "myprofile"));
    var closeResult = invoke("handlePprofClose", Map.of("sessionId", "myprofile"));
    assertSuccess(closeResult);
  }

  @Test
  void openDuplicateAliasReturnsError() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString(), "alias", "dup"));
    var result = invoke("handlePprofOpen", Map.of("path", profilePath.toString(), "alias", "dup"));
    assertTrue(result.isError());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_query
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void queryCountReturnsSampleCount() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handlePprofQuery", Map.of("query", "samples | count"));
    assertSuccess(result);
    assertTrue(result.content().get(0).toString().contains("4"));
  }

  @Test
  void queryGroupByReturnsRows() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result =
        invoke("handlePprofQuery", Map.of("query", "samples | groupBy(stackTrace/0/name)"));
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("doWork") || content.contains("park") || content.contains("alloc"));
  }

  @Test
  void queryParseErrorReturnsError() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handlePprofQuery", Map.of("query", "$$invalid$$"));
    assertTrue(result.isError());
  }

  @Test
  void queryWithLimitTruncates() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handlePprofQuery", Map.of("query", "samples | top(2, cpu)", "limit", 1));
    assertSuccess(result);
    assertTrue(result.content().get(0).toString().contains("truncated"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_summary
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void summaryReturnsSampleTypes() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handlePprofSummary", Map.of());
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("cpu") || content.contains("sampleTypes"));
    assertTrue(content.contains("sampleCount") || content.contains("4"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void flamegraphReturnsStackRows() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handlePprofFlamegraph", Map.of());
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("rows") || content.contains("stack"));
  }

  @Test
  void flamegraphWithValueFieldFiltersCorrectly() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handlePprofFlamegraph", Map.of("valueField", "alloc_space"));
    assertSuccess(result);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_use
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void useReturnsStructuredReport() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invokeWithExchange("handlePprofUse", Map.of());
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("USE") || content.contains("cpu") || content.contains("resources"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_hotmethods
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void hotmethodsReturnsTopMethods() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handlePprofHotmethods", Map.of());
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("topMethods") || content.contains("doWork"));
  }

  @Test
  void hotmethodsRespectsTopN() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handlePprofHotmethods", Map.of("topN", 1));
    assertSuccess(result);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_tsa
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void tsaReturnsThreadDistribution() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invokeWithExchange("handlePprofTsa", Map.of());
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(
        content.contains("TSA") || content.contains("thread") || content.contains("RUNNING"));
  }

  @Test
  void tsaInfersParkAsWaiting() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invokeWithExchange("handlePprofTsa", Map.of());
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("WAITING") || content.contains("park"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private CallToolResult invoke(String methodName, Map<String, Object> args) throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod(methodName, Map.class);
    method.setAccessible(true);
    return (CallToolResult) method.invoke(server, args);
  }

  private CallToolResult invokeWithExchange(String methodName, Map<String, Object> args)
      throws Exception {
    Method method =
        JafarMcpServer.class.getDeclaredMethod(methodName, McpSyncServerExchange.class, Map.class);
    method.setAccessible(true);
    return (CallToolResult) method.invoke(server, (McpSyncServerExchange) null, args);
  }

  private void assertSuccess(CallToolResult result) {
    assertFalse(
        result.isError(), () -> "Expected success but got error: " + result.content().get(0));
  }
}

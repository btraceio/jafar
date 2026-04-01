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
    assertTrue(content.contains("id"), "Response should include session id");
    // getStatistics() uses key "samples" for the sample count
    assertTrue(content.contains("samples"), "Response should include samples count");
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
    // Profile has doWork (2 samples), allocBuffer (1), park (1) as leaf frames
    assertTrue(content.contains("doWork"), "Response should contain doWork as a leaf function");
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
    assertTrue(content.contains("sampleTypes"), "Response should contain sampleTypes field");
    // getStatistics() uses key "samples" for the sample count
    assertTrue(content.contains("samples"), "Response should contain samples count field");
    assertTrue(content.contains("cpu"), "Response should list cpu sample type from the profile");
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
    assertTrue(content.contains("rows"), "Response should contain rows field");
    assertTrue(content.contains("valueField"), "Response should contain valueField");
    assertTrue(content.contains("cpu"), "Default valueField should be cpu");
  }

  @Test
  void flamegraphWithValueFieldFiltersCorrectly() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handlePprofFlamegraph", Map.of("valueField", "alloc_space"));
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("alloc_space"), "Response should reflect requested valueField");
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
    assertTrue(content.contains("USE"), "Response should identify as USE method report");
    assertTrue(content.contains("resources"), "Response should contain resources breakdown");
    assertTrue(content.contains("cpu"), "Report should include cpu resource");
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
    assertTrue(content.contains("topMethods"), "Response should contain topMethods field");
    // doWork is the leaf function in 2 of 4 samples — it must appear as a hot method
    assertTrue(content.contains("doWork"), "doWork should appear in top methods");
  }

  @Test
  void hotmethodsRespectsTopN() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handlePprofHotmethods", Map.of("topN", 1));
    assertSuccess(result);
    String content = result.content().get(0).toString();
    // With topN=1, only the hottest method should appear; doWork has most samples
    assertTrue(
        content.contains("doWork"), "With topN=1 the single hottest method (doWork) should appear");
    // The other leaf functions should be absent
    assertFalse(content.contains("allocBuffer"), "allocBuffer should be excluded with topN=1");
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
    assertTrue(content.contains("TSA"), "Response should identify as TSA method report");
    assertTrue(
        content.contains("threadDistribution"), "Response should contain threadDistribution");
    assertTrue(
        content.contains("inferredStateDistribution"),
        "Response should contain state distribution");
    // Profile has 3 distinct threads: workerA, workerB, main
    assertTrue(content.contains("workerA"), "Thread workerA should appear in distribution");
  }

  @Test
  void tsaInfersParkAsWaiting() throws Exception {
    invoke("handlePprofOpen", Map.of("path", profilePath.toString()));
    var result = invokeWithExchange("handlePprofTsa", Map.of());
    assertSuccess(result);
    String content = result.content().get(0).toString();
    // park() is a WAITING keyword — the WAITING state must be present in distribution
    assertTrue(content.contains("WAITING"), "park() leaf frame should be classified as WAITING");
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

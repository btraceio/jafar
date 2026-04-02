package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.otlp.shell.MinimalOtlpBuilder;
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
 * Integration tests for otlp_* MCP handlers against a synthetic OTLP profile. Tests the full
 * handler path: open → query/analysis → close.
 */
class OtlpHandlerIntegrationTest {

  @TempDir Path tempDir;

  private JafarMcpServer server;
  private Path profilePath;

  @BeforeEach
  void setUp() throws Exception {
    server = new JafarMcpServer();

    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    int cpuIdx = b.addString("cpu");
    int nsIdx = b.addString("nanoseconds");
    int threadKey = b.addString("thread");
    b.setSampleType(cpuIdx, nsIdx);
    b.setDurationNanos(30_000_000_000L);

    // Functions
    int fnWork = b.addFunction(b.addString("doWork"), b.addString("Worker.java"));
    int fnAlloc = b.addFunction(b.addString("allocBuffer"), b.addString("Buf.java"));
    int fnWait = b.addFunction(b.addString("park"), b.addString("LockSupport.java"));
    int fnMain = b.addFunction(b.addString("main"), b.addString("Main.java"));

    // Locations (1-based)
    int locWork = b.addLocation(fnWork, 20);
    int locAlloc = b.addLocation(fnAlloc, 5);
    int locWait = b.addLocation(fnWait, 1);
    int locMain = b.addLocation(fnMain, 10);

    // Stacks: leaf-first
    int stackWork = b.addStack(List.of(locWork, locMain));
    int stackAlloc = b.addStack(List.of(locAlloc, locMain));
    int stackWait = b.addStack(List.of(locWait, locMain));

    // Attributes: thread labels
    int attrWorkerA = b.addAttribute(threadKey, "workerA");
    int attrWorkerB = b.addAttribute(threadKey, "workerB");
    int attrMain = b.addAttribute(threadKey, "main");

    // Samples: stackIndex, attrIndices, values
    b.addSample(stackWork, List.of(attrWorkerA), List.of(5_000_000L));
    b.addSample(stackWork, List.of(attrWorkerA), List.of(3_000_000L));
    b.addSample(stackAlloc, List.of(attrWorkerB), List.of(1_000_000L));
    b.addSample(stackWait, List.of(attrMain), List.of(500_000L));

    profilePath = b.write(tempDir);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_open / otlp_close
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void openReturnsSessionInfo() throws Exception {
    var result = invoke("handleOtlpOpen", Map.of("path", profilePath.toString()));
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("id"), "Response should include session id");
    assertTrue(content.contains("samples"), "Response should include samples count");
  }

  @Test
  void openWithAliasThenCloseByAlias() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString(), "alias", "myprofile"));
    var closeResult = invoke("handleOtlpClose", Map.of("sessionId", "myprofile"));
    assertSuccess(closeResult);
  }

  @Test
  void openDuplicateAliasReturnsError() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString(), "alias", "dup"));
    var result = invoke("handleOtlpOpen", Map.of("path", profilePath.toString(), "alias", "dup"));
    assertTrue(result.isError());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_query
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void queryCountReturnsSampleCount() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handleOtlpQuery", Map.of("query", "samples | count"));
    assertSuccess(result);
    assertTrue(result.content().get(0).toString().contains("4"));
  }

  @Test
  void queryGroupByReturnsLeafFunctions() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handleOtlpQuery", Map.of("query", "samples | groupBy(stackTrace/0/name)"));
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("doWork"), "doWork should appear as a leaf function");
  }

  @Test
  void queryParseErrorReturnsError() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handleOtlpQuery", Map.of("query", "$$invalid$$"));
    assertTrue(result.isError());
  }

  @Test
  void queryWithLimitTruncates() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handleOtlpQuery", Map.of("query", "samples | top(4, cpu)", "limit", 1));
    assertSuccess(result);
    assertTrue(result.content().get(0).toString().contains("truncated"));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_summary
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void summaryReturnsSampleTypeInfo() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handleOtlpSummary", Map.of());
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("sessionId"), "Response should contain sessionId");
    assertTrue(content.contains("samples"), "Response should contain samples count");
    assertTrue(content.contains("cpu"), "Response should list cpu sample type");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void flamegraphReturnsStackRows() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handleOtlpFlamegraph", Map.of());
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("rows"), "Response should contain rows field");
    assertTrue(content.contains("valueField"), "Response should contain valueField");
    assertTrue(content.contains("cpu"), "Default valueField should be cpu");
  }

  @Test
  void flamegraphRejectsFilterWithClosingBracket() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString()));
    var result = invoke("handleOtlpFlamegraph", Map.of("filter", "cpu > 0] | top(5)"));
    assertTrue(result.isError(), "Filter containing ']' must be rejected");
    assertTrue(
        result.content().get(0).toString().contains("]"),
        "Error should mention the offending character");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_use
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void useReturnsStructuredReport() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString()));
    var result = invokeWithExchange("handleOtlpUse", Map.of());
    assertSuccess(result);
    String content = result.content().get(0).toString();
    assertTrue(content.contains("USE"), "Response should identify as USE method report");
    assertTrue(content.contains("resources"), "Response should contain resources breakdown");
    assertTrue(content.contains("cpu"), "Report should include cpu resource");
  }

  @Test
  void useThreadsReturnsDistribution() throws Exception {
    invoke("handleOtlpOpen", Map.of("path", profilePath.toString()));
    var result = invokeWithExchange("handleOtlpUse", Map.of("resources", List.of("threads")));
    assertSuccess(result);
    String content = result.content().get(0).toString();
    // Profile has 3 threads: workerA (2 samples), workerB (1), main (1)
    assertTrue(content.contains("workerA"), "workerA should appear in thread distribution");
    assertTrue(content.contains("threadCount"), "Response should contain threadCount");
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

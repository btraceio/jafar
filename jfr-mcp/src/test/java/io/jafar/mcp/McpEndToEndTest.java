package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end test that spawns the MCP server as a subprocess — exactly as the Claude Code plugin
 * launcher does — and drives the analysis workflow that previously caused the server to disconnect.
 *
 * <p>Run with: {@code ./gradlew :jfr-mcp:endToEndTest}
 *
 * <p>By default the test uses the locally-built shadow jar. Pass {@code -Dmcp.e2e.use.jbang=true}
 * to use {@code jbang jfr-mcp@btraceio} instead (tests the published release against the same
 * workflow).
 */
@Tag("e2e")
class McpEndToEndTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long TOOL_TIMEOUT_MS = 30_000;

  // ─────────────────────────────────────────────────────────────────────────────
  // Regression: wrong progressToken caused Claude Code to close stdin
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Reproduces the "plugin disconnected" failure mode: jfr_open → jfr_query → jfr_diagnose.
   *
   * <p>Before the fix, jfr_diagnose sent progress notifications with a string token ("diagnose")
   * while Claude Code had sent {@code progressToken: 4} (an integer). Claude Code's MCP client
   * closed stdin within ~5 ms of receiving the mismatched notification, killing the server before
   * it could return the diagnosis result.
   */
  @Test
  @Timeout(120)
  void serverSurvivesOpenQueryDiagnoseSequence() throws Exception {
    Path jar = findShadowJar();
    Path jfr = findTestJfr();

    Process proc = startServer(jar);
    MessageCollector collector = new MessageCollector(proc.getInputStream());
    PrintWriter writer = writerFor(proc);

    try {
      handshake(writer, collector);

      // Step 1: open the recording
      sendCall(writer, 2, "jfr_open", Map.of("path", jfr.toAbsolutePath().toString()), null);
      JsonNode openResp = collector.waitForId(2, TOOL_TIMEOUT_MS);
      assertNotNull(openResp, "jfr_open must return a response");
      assertFalse(openResp.at("/result/isError").asBoolean(), "jfr_open must not error");
      assertTrue(proc.isAlive(), "server must be alive after jfr_open");

      // Step 2: query JVM info (the intermediate step in the real workflow)
      sendCall(
          writer,
          3,
          "jfr_query",
          Map.of("query", "events/jdk.JVMInformation | select(jvmVersion, jvmName)"),
          3);
      JsonNode queryResp = collector.waitForId(3, TOOL_TIMEOUT_MS);
      assertNotNull(queryResp, "jfr_query must return a response");
      assertTrue(proc.isAlive(), "server must be alive after jfr_query");

      // Step 3: jfr_diagnose — this is the call that previously killed the server.
      // The request carries progressToken:4 (integer) to match Claude Code's real payload.
      sendCall(writer, 4, "jfr_diagnose", Map.of(), 4);
      JsonNode diagnoseResp = collector.waitForId(4, TOOL_TIMEOUT_MS);

      assertNotNull(
          diagnoseResp,
          "jfr_diagnose must return a response — server must not disconnect mid-session");
      assertFalse(diagnoseResp.has("error"), "jfr_diagnose must not be a JSON-RPC error");
      assertFalse(
          diagnoseResp.at("/result/isError").asBoolean(),
          "jfr_diagnose result.isError must be false");
      assertTrue(
          proc.isAlive(), "server process must still be running after jfr_diagnose completes");

    } finally {
      writer.close();
      proc.destroyForcibly();
      proc.waitFor(5, TimeUnit.SECONDS);
      collector.stop();
    }
  }

  /**
   * Regression: jfr_use sends multiple progress notifications (for CPU, memory, threads, I/O).
   * After jfr_open + jfr_use the server must still respond to further calls.
   */
  @Test
  @Timeout(120)
  void serverSurvivesUseMethodAfterOpen() throws Exception {
    Path jar = findShadowJar();
    Path jfr = findTestJfr();

    Process proc = startServer(jar);
    MessageCollector collector = new MessageCollector(proc.getInputStream());
    PrintWriter writer = writerFor(proc);

    try {
      handshake(writer, collector);

      sendCall(writer, 2, "jfr_open", Map.of("path", jfr.toAbsolutePath().toString()), null);
      assertNotNull(collector.waitForId(2, TOOL_TIMEOUT_MS), "jfr_open must respond");
      assertTrue(proc.isAlive(), "server must be alive after jfr_open");

      sendCall(writer, 3, "jfr_use", Map.of(), 3);
      JsonNode useResp = collector.waitForId(3, TOOL_TIMEOUT_MS);
      assertNotNull(useResp, "jfr_use must return a response");
      assertFalse(useResp.has("error"), "jfr_use must not JSON-RPC error");
      assertTrue(proc.isAlive(), "server must be alive after jfr_use");

      // Server must still handle a subsequent call
      sendCall(writer, 4, "jfr_help", Map.of(), null);
      JsonNode helpResp = collector.waitForId(4, TOOL_TIMEOUT_MS);
      assertNotNull(helpResp, "jfr_help must respond after jfr_use");
      assertTrue(proc.isAlive(), "server must be alive after subsequent call");

    } finally {
      writer.close();
      proc.destroyForcibly();
      proc.waitFor(5, TimeUnit.SECONDS);
      collector.stop();
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Infrastructure helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private static Process startServer(Path jar) throws IOException {
    List<String> cmd;
    if (Boolean.getBoolean("mcp.e2e.use.jbang")) {
      String jbang = System.getProperty("mcp.e2e.jbang.cmd", "jbang");
      cmd = List.of(jbang, "jfr-mcp@btraceio", "--stdio");
    } else {
      String java = ProcessHandle.current().info().command().orElse("java");
      cmd = List.of(java, "-jar", jar.toAbsolutePath().toString(), "--stdio");
    }

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
    return pb.start();
  }

  private static PrintWriter writerFor(Process proc) {
    return new PrintWriter(
        new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8), true);
  }

  private static void handshake(PrintWriter writer, MessageCollector collector)
      throws InterruptedException {
    writer.println(
        "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-06-18\","
            + "\"capabilities\":{},\"clientInfo\":{\"name\":\"e2e-test\",\"version\":\"1.0\"}}}");
    JsonNode resp = collector.waitForId(0, TOOL_TIMEOUT_MS);
    assumeTrue(resp != null && !resp.has("error"), "MCP handshake failed: " + resp);
    writer.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
  }

  /**
   * Sends a {@code tools/call} request that mirrors Claude Code's real payload, including a {@code
   * _meta.progressToken} when {@code progressToken} is non-null.
   */
  private static void sendCall(
      PrintWriter writer, int id, String tool, Map<String, Object> args, Integer progressToken)
      throws Exception {
    ObjectNode params = MAPPER.createObjectNode();
    params.put("name", tool);
    params.set("arguments", MAPPER.valueToTree(args));
    if (progressToken != null) {
      ObjectNode meta = MAPPER.createObjectNode();
      meta.put("claudecode/toolUseId", "toolu_e2e_" + id);
      meta.put("progressToken", progressToken);
      params.set("_meta", meta);
    }
    ObjectNode req = MAPPER.createObjectNode();
    req.put("jsonrpc", "2.0");
    req.put("id", id);
    req.put("method", "tools/call");
    req.set("params", params);
    writer.println(MAPPER.writeValueAsString(req));
  }

  private static Path findShadowJar() {
    if (Boolean.getBoolean("mcp.e2e.use.jbang")) {
      return Path.of("/dev/null"); // unused in jbang mode
    }
    Path libs = Paths.get("build/libs");
    if (!Files.isDirectory(libs)) {
      libs = Paths.get("jfr-mcp/build/libs");
    }
    try {
      return Files.list(libs)
          .filter(p -> p.getFileName().toString().matches("jfr-mcp-.*-all\\.jar"))
          .max(
              java.util.Comparator.comparingLong(
                  p -> {
                    try {
                      return Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                      return 0L;
                    }
                  }))
          .orElseGet(
              () -> {
                assumeTrue(false, "No jfr-mcp-*-all.jar found — run ./gradlew :jfr-mcp:shadowJar");
                return null; // unreachable
              });
    } catch (IOException e) {
      assumeTrue(false, "Could not list build/libs: " + e.getMessage());
      return null; // unreachable
    }
  }

  private static Path findTestJfr() {
    // First try a custom override (useful for reproducing with specific recordings)
    String override = System.getProperty("mcp.e2e.jfr");
    if (override != null) {
      Path p = Path.of(override);
      assumeTrue(Files.exists(p), "JFR file from mcp.e2e.jfr not found: " + override);
      return p;
    }
    // Fall back to the repo's small test recording
    Path p = Paths.get("../demo/src/test/resources/test-dd.jfr").normalize();
    if (!Files.exists(p)) {
      p = Paths.get("demo/src/test/resources/test-dd.jfr").normalize();
    }
    assumeTrue(Files.exists(p), "test-dd.jfr not found — ensure the demo module is present");
    return p;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Background message reader
  // ─────────────────────────────────────────────────────────────────────────────

  static final class MessageCollector {

    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    private final Thread thread;

    MessageCollector(java.io.InputStream in) {
      thread =
          new Thread(
              () -> {
                try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                      messages.put(MAPPER.readTree(line));
                    } catch (InterruptedException ie) {
                      Thread.currentThread().interrupt();
                      break;
                    } catch (Exception ignored) {
                    }
                  }
                } catch (Exception ignored) {
                }
              },
              "e2e-reader");
      thread.setDaemon(true);
      thread.start();
    }

    JsonNode waitForId(int id, long timeoutMs) throws InterruptedException {
      long deadline = System.currentTimeMillis() + timeoutMs;
      List<JsonNode> stash = new ArrayList<>();
      try {
        while (System.currentTimeMillis() < deadline) {
          long remaining = deadline - System.currentTimeMillis();
          JsonNode msg = messages.poll(remaining, TimeUnit.MILLISECONDS);
          if (msg == null) break;
          if (msg.has("id") && msg.get("id").asInt() == id) return msg;
          stash.add(msg);
        }
        return null;
      } finally {
        messages.addAll(stash);
      }
    }

    void stop() {
      thread.interrupt();
    }
  }
}

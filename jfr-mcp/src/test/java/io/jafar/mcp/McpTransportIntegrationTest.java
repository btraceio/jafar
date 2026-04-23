package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for the MCP stdio transport layer.
 *
 * <p>These tests exercise the full JSON-RPC over stdio path using in-process pipes, catching
 * regressions in protocol negotiation, tool execution, and clean shutdown — things that
 * reflection-based unit tests cannot reach.
 */
@Tag("integration")
class McpTransportIntegrationTest extends BaseJfrTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long RESPONSE_TIMEOUT_MS = 15_000;

  private PipedOutputStream clientOut;
  private PipedInputStream serverIn;
  private PipedInputStream clientIn;
  private PipedOutputStream serverOut;

  private FixedStdioServerTransportProvider transport;
  private McpSyncServer mcpServer;
  private MessageCollector collector;

  @BeforeEach
  void setUp() throws Exception {
    serverIn = new PipedInputStream(1 << 20);
    clientOut = new PipedOutputStream(serverIn);
    clientIn = new PipedInputStream(1 << 20);
    serverOut = new PipedOutputStream(clientIn);

    transport =
        new FixedStdioServerTransportProvider(
            io.modelcontextprotocol.json.McpJsonDefaults.getMapper(), serverIn, serverOut);

    JafarMcpServer jafarServer = new JafarMcpServer();
    var tools = jafarServer.createToolSpecifications();

    mcpServer =
        McpServer.sync(transport)
            .serverInfo("jafar-mcp-test", "0.0.0")
            .capabilities(ServerCapabilities.builder().tools(true).logging().build())
            .tools(tools)
            .build();

    collector = new MessageCollector(clientIn);

    // Perform the MCP handshake so individual tests start with an initialized session.
    handshake("2025-06-18");
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      clientOut.close();
    } catch (IOException ignored) {
    }
    try {
      transport.awaitShutdown().timeout(Duration.ofSeconds(5)).block();
    } finally {
      collector.stop();
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Protocol-negotiation tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void serverAcceptsProtocol_2025_06_18() {
    // Handshake already performed in setUp with 2025-06-18; setUp would throw on failure.
    assertNotNull(mcpServer, "MCP server must be initialized after handshake");
  }

  @Test
  void initializeResponseContainsExpectedFields() throws Exception {
    // A second client on fresh pipes with explicit version check
    PipedInputStream freshServerIn = new PipedInputStream(1 << 20);
    PipedOutputStream freshClientOut = new PipedOutputStream(freshServerIn);
    PipedInputStream freshClientIn = new PipedInputStream(1 << 20);
    PipedOutputStream freshServerOut = new PipedOutputStream(freshClientIn);

    FixedStdioServerTransportProvider freshTransport =
        new FixedStdioServerTransportProvider(
            io.modelcontextprotocol.json.McpJsonDefaults.getMapper(),
            freshServerIn,
            freshServerOut);
    JafarMcpServer jafarServer2 = new JafarMcpServer();
    var tools2 = jafarServer2.createToolSpecifications();
    McpServer.sync(freshTransport)
        .serverInfo("jafar-mcp-test", "0.0.0")
        .capabilities(ServerCapabilities.builder().tools(true).logging().build())
        .tools(tools2)
        .build();

    MessageCollector freshCollector = new MessageCollector(freshClientIn);
    PrintWriter writer =
        new PrintWriter(
            new java.io.OutputStreamWriter(freshClientOut, StandardCharsets.UTF_8), true);

    try {
      writer.println(initializeRequest(1, "2025-06-18"));
      JsonNode response = freshCollector.waitForId(1, RESPONSE_TIMEOUT_MS);
      assertNotNull(response, "initialize must return a response");
      assertFalse(response.has("error"), "initialize must not return an error");

      JsonNode result = response.get("result");
      assertNotNull(result);
      assertEquals("2025-06-18", result.get("protocolVersion").asText());
      assertNotNull(result.get("serverInfo"));
      assertNotNull(result.get("capabilities"));
    } finally {
      freshClientOut.close();
      freshTransport.awaitShutdown().timeout(Duration.ofSeconds(5)).block();
      freshCollector.stop();
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Tool-execution tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void toolsListContainsJfrTools() throws Exception {
    send(
        """
        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
        """
            .strip());

    JsonNode response = collector.waitForId(2, RESPONSE_TIMEOUT_MS);
    assertNotNull(response, "tools/list must return a response");
    assertFalse(response.has("error"));

    JsonNode tools = response.at("/result/tools");
    assertTrue(tools.isArray() && tools.size() > 0, "server must advertise at least one tool");

    List<String> names = new ArrayList<>();
    tools.forEach(t -> names.add(t.get("name").asText()));
    assertTrue(names.contains("jfr_open"), "jfr_open must be listed");
    assertTrue(names.contains("jfr_summary"), "jfr_summary must be listed");
    assertTrue(names.contains("jfr_list_types"), "jfr_list_types must be listed");
  }

  @Test
  void jfrOpenSucceedsWithRealFile() throws Exception {
    String path = getComprehensiveJfr();
    send(
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"jfr_open\",\"arguments\":{\"path\":\""
            + path
            + "\"}}}");

    JsonNode response = collector.waitForId(3, RESPONSE_TIMEOUT_MS);
    assertNotNull(response, "jfr_open must return a response");
    assertFalse(response.has("error"));
    assertFalse(response.at("/result/isError").asBoolean(), "jfr_open must not report isError");
  }

  @Test
  void jfrSummaryReturnsResult() throws Exception {
    // Open a recording first
    String path = getComprehensiveJfr();
    send(
        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"jfr_open\",\"arguments\":{\"path\":\""
            + path
            + "\"}}}");
    assertNotNull(collector.waitForId(4, RESPONSE_TIMEOUT_MS), "jfr_open must respond");

    // Run summary (may emit progress notifications before the result)
    send(
        "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"jfr_summary\",\"arguments\":{}}}");

    JsonNode response = collector.waitForId(5, RESPONSE_TIMEOUT_MS);
    assertNotNull(response, "jfr_summary must return a response");
    assertFalse(response.has("error"));
    assertFalse(response.at("/result/isError").asBoolean());

    // Verify content shape
    String text = response.at("/result/content/0/text").asText();
    JsonNode summary = MAPPER.readTree(text);
    assertTrue(summary.has("totalEvents"), "summary must include totalEvents");
    assertTrue(summary.has("topEventTypes"), "summary must include topEventTypes");
  }

  @Test
  void subsequentToolCallSucceedsAfterFailedCall() throws Exception {
    // Unknown tool → server should return an error result, not crash
    send(
        "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"nonexistent_tool\",\"arguments\":{}}}");
    JsonNode errorResponse = collector.waitForId(6, RESPONSE_TIMEOUT_MS);
    assertNotNull(errorResponse, "bad tool call must get a response");

    // Server must still respond to subsequent valid calls
    send(
        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"jfr_open\",\"arguments\":{\"path\":\"/nonexistent.jfr\"}}}");
    JsonNode followUp = collector.waitForId(7, RESPONSE_TIMEOUT_MS);
    assertNotNull(followUp, "server must respond to calls after a failed one");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Shutdown tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void serverExitsCleanlyWhenStdinCloses() throws Exception {
    // Close the client-side write end (simulates stdin EOF)
    clientOut.close();

    // awaitShutdown() must complete — no hang
    Boolean completed =
        transport.awaitShutdown().timeout(Duration.ofSeconds(10)).thenReturn(Boolean.TRUE).block();
    assertTrue(Boolean.TRUE.equals(completed), "server must shut down when stdin closes");
  }

  @Test
  void serverExitsCleanlyAfterToolCompletes() throws Exception {
    String path = getComprehensiveJfr();
    send(
        "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"jfr_open\",\"arguments\":{\"path\":\""
            + path
            + "\"}}}");
    assertNotNull(collector.waitForId(8, RESPONSE_TIMEOUT_MS), "jfr_open must respond");

    // Close stdin after the tool completes
    clientOut.close();

    Boolean completed =
        transport.awaitShutdown().timeout(Duration.ofSeconds(10)).thenReturn(Boolean.TRUE).block();
    assertTrue(Boolean.TRUE.equals(completed), "server must shut down after tool completes");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private void handshake(String protocolVersion) throws Exception {
    send(initializeRequest(0, protocolVersion));
    JsonNode resp = collector.waitForId(0, RESPONSE_TIMEOUT_MS);
    assertNotNull(resp, "initialize must return a response");
    assertFalse(resp.has("error"), "initialize must not return an error: " + resp);

    // notifications/initialized has no id — fire and forget
    write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
  }

  private static String initializeRequest(int id, String version) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + id
        + ",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
        + version
        + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";
  }

  private void send(String json) throws IOException {
    write(json.strip());
  }

  private void write(String line) throws IOException {
    clientOut.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    clientOut.flush();
  }

  /** Reads JSON-RPC messages from the server in a background thread. */
  private static final class MessageCollector {

    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    private final Thread thread;

    MessageCollector(PipedInputStream in) {
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
                    } catch (Exception e) {
                      // skip unparseable lines
                    }
                  }
                } catch (Exception ignored) {
                }
              },
              "mcp-test-reader");
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

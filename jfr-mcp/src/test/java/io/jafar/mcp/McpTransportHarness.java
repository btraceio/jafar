package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Test helper that wraps the full MCP stdio transport pipeline (piped streams + {@link
 * FixedStdioServerTransportProvider}) and provides helpers for sending JSON-RPC messages.
 *
 * <p>Create one instance per test via {@code @BeforeEach}, close it in {@code @AfterEach}.
 *
 * <p>{@link #waitForId} is not thread-safe; do not call it from concurrent test threads.
 */
final class McpTransportHarness implements AutoCloseable {

  static final long RESPONSE_TIMEOUT_MS = Long.getLong("mcp.test.timeout.ms", 15_000);
  private static final Logger logger = LoggerFactory.getLogger(McpTransportHarness.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final PipedOutputStream clientOut;
  private final FixedStdioServerTransportProvider transport;
  private final MessageCollector collector;
  private final AtomicInteger nextId = new AtomicInteger(1);

  McpTransportHarness() throws Exception {
    PipedInputStream serverIn = new PipedInputStream(1 << 20);
    clientOut = new PipedOutputStream(serverIn);
    PipedInputStream clientIn = new PipedInputStream(1 << 20);
    PipedOutputStream serverOut = new PipedOutputStream(clientIn);

    transport =
        new FixedStdioServerTransportProvider(
            io.modelcontextprotocol.json.McpJsonDefaults.getMapper(), serverIn, serverOut);

    JafarMcpServer jafarServer = new JafarMcpServer();
    List<McpServerFeatures.SyncToolSpecification> tools = jafarServer.createToolSpecifications();

    McpServer.sync(transport)
        .serverInfo("jafar-mcp-test", "0.0.0")
        .capabilities(ServerCapabilities.builder().tools(true).logging().build())
        .tools(tools)
        .build();

    collector = new MessageCollector(clientIn);
    handshake();
  }

  /** Sends a {@code tools/call} request and waits for the response with an auto-generated id. */
  JsonNode callTool(String toolName, String argsJson) throws Exception {
    return callTool(nextId.getAndIncrement(), toolName, argsJson);
  }

  /**
   * Sends a {@code tools/call} request and waits for the response with the given {@code id}.
   *
   * <p>Callers must supply a unique {@code id} per harness instance to avoid a response being
   * returned to the wrong waiter. Use {@link #callTool(String, String)} for auto-generated ids.
   */
  JsonNode callTool(int id, String toolName, String argsJson) throws Exception {
    String json =
        "{\"jsonrpc\":\"2.0\",\"id\":"
            + id
            + ",\"method\":\"tools/call\","
            + "\"params\":{\"name\":\""
            + toolName
            + "\",\"arguments\":"
            + argsJson
            + "}}";
    write(json);
    return collector.waitForId(id, RESPONSE_TIMEOUT_MS);
  }

  void write(String line) throws IOException {
    clientOut.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    clientOut.flush();
  }

  MessageCollector collector() {
    return collector;
  }

  private void handshake() throws Exception {
    String initReq =
        "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\","
            + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";
    write(initReq);
    JsonNode resp = collector.waitForId(0, RESPONSE_TIMEOUT_MS);
    if (resp == null || resp.has("error")) {
      throw new AssertionError("MCP handshake failed: " + resp);
    }
    write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
  }

  @Override
  public void close() throws Exception {
    try {
      clientOut.close();
    } catch (IOException ignored) {
    }
    try {
      transport.awaitShutdown().timeout(Duration.ofSeconds(5)).block();
    } catch (Exception e) {
      logger.warn("Transport shutdown timed out or failed during close: {}", e.getMessage());
    } finally {
      collector.stop();
    }
  }

  /** Reads JSON-RPC messages from the server in a background thread. */
  static final class MessageCollector {

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
                      logger.warn("Unparseable server line: {}", line, e);
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

  /** Asserts that a tool response is a successful, non-error result. */
  static void assertSuccess(JsonNode resp, int id) {
    assertNotNull(resp, "tool call id=" + id + " must return a response");
    assertFalse(resp.has("error"), "must not be a JSON-RPC error");
    assertFalse(resp.at("/result/isError").asBoolean(), "result.isError must be false");
  }
}

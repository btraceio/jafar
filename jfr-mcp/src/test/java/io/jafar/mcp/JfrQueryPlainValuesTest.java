package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #283: {@code jfr_query} rows hold the untyped parser's wrappers, which Jackson wrote as beans: a
 * stack trace's {@code frames} as {@code {"array": [...], "type": "[Ljava.lang.Object;"}}, each
 * constant as {@code {"simpleType": ..., "value": ...}}.
 */
class JfrQueryPlainValuesTest {

  @TempDir Path dir;

  private JafarMcpServer server;
  private Path recording;

  @BeforeEach
  void setUp() throws Exception {
    // A copy in a temporary directory: a session left in the persisted store is restored only
    // while its file exists, so it cannot outlive the test
    recording =
        Files.copy(
            Path.of("../jfr-shell-tck/src/main/resources/tck-test.jfr")
                .toAbsolutePath()
                .normalize(),
            dir.resolve("tck-test.jfr"));
    server = new JafarMcpServer();
  }

  @AfterEach
  void closeSessions() throws Exception {
    Method close = JafarMcpServer.class.getDeclaredMethod("handleJfrClose", Map.class);
    close.setAccessible(true);
    close.invoke(server, new HashMap<>(Map.of("closeAll", true)));
  }

  @Test
  void stackTraceFramesAreAnArrayOfFrames() throws Exception {
    CallToolResult result = query("events/datadog.ExecutionSample | head(1) | select(stackTrace)");

    JsonNode root = JsonMapper.builder().build().readTree(text(result));
    JsonNode frames = root.path("results").get(0).path("stackTrace").path("frames");
    assertTrue(frames.isArray(), frames.toString());
    assertTrue(frames.size() > 0, frames.toString());
    JsonNode method = frames.get(0).path("method");
    assertTrue(method.isObject(), frames.get(0).toString());
    assertFalse(method.has("simpleType"), method.toString());
    assertFalse(method.has("value"), method.toString());
    // A nested constant is its wrapper map, not a bean
    JsonNode name = method.path("name");
    assertTrue(name.path("string").isString(), name.toString());
    assertEquals(1, name.size(), name.toString());

    // The structured content is the same plain shape
    Map<?, ?> structured = assertInstanceOf(Map.class, result.structuredContent());
    Object row = ((List<?>) structured.get("results")).get(0);
    Object stackTrace = ((Map<?, ?>) row).get("stackTrace");
    assertInstanceOf(List.class, ((Map<?, ?>) stackTrace).get("frames"), stackTrace.toString());
  }

  @Test
  void aRowWithoutWrappersIsUnchanged() throws Exception {
    CallToolResult result = query("events/datadog.ExecutionSample | count()");

    JsonNode root = JsonMapper.builder().build().readTree(text(result));
    assertEquals(1, root.path("resultCount").asInt(), root.toString());
    JsonNode count = root.path("results").get(0).path("count");
    assertTrue(count.isIntegralNumber(), count.toString());
    assertTrue(count.asLong() > 0, count.toString());
    // The response's keys keep the order the tool put them in
    List<String> keys = new java.util.ArrayList<>();
    root.propertyNames().forEach(keys::add);
    assertEquals(List.of("query", "sessionId", "resultCount", "results"), keys, root.toString());
  }

  private CallToolResult query(String query) throws Exception {
    Method open = JafarMcpServer.class.getDeclaredMethod("handleJfrOpen", Map.class);
    open.setAccessible(true);
    assertFalse(
        ((CallToolResult) open.invoke(server, Map.of("path", recording.toString()))).isError());
    Map<String, Object> arguments = new HashMap<>();
    arguments.put("query", query);
    Method handler;
    Object[] args;
    try {
      handler = JafarMcpServer.class.getDeclaredMethod("handleJfrQuery", Map.class);
      args = new Object[] {arguments};
    } catch (NoSuchMethodException e) {
      handler =
          JafarMcpServer.class.getDeclaredMethod(
              "handleJfrQuery", McpSyncServerExchange.class, Map.class, Object.class);
      args = new Object[] {null, arguments, null};
    }
    handler.setAccessible(true);
    CallToolResult result = (CallToolResult) handler.invoke(server, args);
    assertFalse(result.isError(), result.content().toString());
    return result;
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }
}

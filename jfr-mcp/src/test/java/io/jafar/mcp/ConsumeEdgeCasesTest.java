package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.mcp.query.DefaultQueryParser;
import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.query.QueryParser;
import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPath;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests verifying that each handler returns a valid empty/no-data response when the QueryEvaluator
 * produces zero events via consume().
 */
class ConsumeEdgeCasesTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Path executionSampleFile;

  private JafarMcpServer server;
  private String sessionId;

  @BeforeAll
  static void createTestFiles() throws Exception {
    executionSampleFile = SimpleJfrFileBuilder.createExecutionSampleFile(5);
  }

  @BeforeEach
  void setUp() throws Exception {
    QueryEvaluator emptyEvaluator =
        new QueryEvaluator() {
          @Override
          public List<Map<String, Object>> evaluate(JFRSession session, JfrPath.Query query)
              throws Exception {
            return Collections.emptyList();
          }

          @Override
          public void consume(
              JFRSession session, JfrPath.Query query, Consumer<Map<String, Object>> consumer) {
            // never calls consumer — zero-events scenario
          }

          @Override
          public Map<String, Long> countAllEventTypes(JFRSession session) throws Exception {
            return Collections.emptyMap();
          }
        };

    QueryParser parser = new DefaultQueryParser();
    server =
        new JafarMcpServer(
            new SessionRegistry(),
            new HeapSessionRegistry(),
            new PprofSessionRegistry(),
            new OtlpSessionRegistry(),
            emptyEvaluator,
            parser);

    // Open a real session so handlers can look up session info
    Method handleJfrOpen = getMethod("handleJfrOpen", Map.class);
    CallToolResult openResult =
        (CallToolResult)
            handleJfrOpen.invoke(server, Map.of("path", executionSampleFile.toString()));
    assertFalse(openResult.isError(), "Failed to open session");
    JsonNode openNode = MAPPER.readTree(extractTextContent(openResult));
    sessionId = openNode.get("id").asText();
  }

  @ParameterizedTest
  @ValueSource(strings = {"jfr_exceptions", "jfr_hotmethods", "jfr_flamegraph"})
  void handlerReturnsValidResponseForZeroEvents(String toolName) throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", sessionId);
    if ("jfr_flamegraph".equals(toolName)) {
      args.put("eventType", "jdk.ExecutionSample");
      args.put("format", "tree");
    }
    if ("jfr_hotmethods".equals(toolName)) {
      args.put("eventType", "jdk.ExecutionSample");
    }
    if ("jfr_exceptions".equals(toolName)) {
      args.put("eventType", "jdk.JavaExceptionThrow");
    }

    CallToolResult result = invokeTool(toolName, args);
    assertNotNull(result, "Handler returned null for " + toolName);
    // Result should either be a successful empty response or an error with a useful message
    String text = extractTextContent(result);
    assertNotNull(text, "No text content for " + toolName);
    assertTrue(!text.isEmpty(), "Empty response text for " + toolName);
  }

  @Test
  void jfrExceptionsWithZeroEventsReturnsEmptyResponse() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", sessionId);
    args.put("eventType", "jdk.JavaExceptionThrow");

    Method method = getMethod("handleJfrExceptions", McpSyncServerExchange.class, Map.class);
    CallToolResult result =
        (CallToolResult) method.invoke(server, (McpSyncServerExchange) null, args);

    assertNotNull(result);
    String text = extractTextContent(result);
    // Either no events found message, or a valid result with 0 exceptions
    assertTrue(
        text.contains("No exception events found") || text.contains("totalExceptions"),
        "Unexpected response: " + text);
  }

  @Test
  void jfrHotmethodsWithZeroEventsReturnsValidResponse() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", sessionId);
    args.put("eventType", "jdk.ExecutionSample");

    CallToolResult result = invokeTool("jfr_hotmethods", args);
    assertNotNull(result);
    String text = extractTextContent(result);
    assertFalse(text.isEmpty());
  }

  private CallToolResult invokeTool(String toolName, Map<String, Object> args) throws Exception {
    String methodName = camelCase("handle_" + toolName);
    try {
      Method method = getMethod(methodName, McpSyncServerExchange.class, Map.class);
      return (CallToolResult) method.invoke(server, (McpSyncServerExchange) null, args);
    } catch (NoSuchMethodException e) {
      Method method = getMethod(methodName, Map.class);
      return (CallToolResult) method.invoke(server, args);
    }
  }

  private String camelCase(String snakeCase) {
    StringBuilder result = new StringBuilder();
    boolean capitalizeNext = false;
    for (char c : snakeCase.toCharArray()) {
      if (c == '_') {
        capitalizeNext = true;
      } else {
        result.append(capitalizeNext ? Character.toUpperCase(c) : c);
        capitalizeNext = false;
      }
    }
    return result.toString();
  }

  private Method getMethod(String name, Class<?>... parameterTypes) throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method;
  }

  private String extractTextContent(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }
}

package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for JafarMcpServer validation and error handling logic. */
class JafarMcpServerUnitTest extends BaseJfrTest {

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  @Test
  void handleJfrOpenValidatesNullPath() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrOpen", Map.class);
    method.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("path", null);

    CallToolResult result = (CallToolResult) method.invoke(server, args);

    assertTrue(result.isError());
    assertTrue(result.content().get(0).toString().contains("Path is required"));
  }

  @Test
  void handleJfrOpenValidatesBlankPath() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrOpen", Map.class);
    method.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("path", "   ");

    CallToolResult result = (CallToolResult) method.invoke(server, args);

    assertTrue(result.isError());
    assertTrue(result.content().get(0).toString().contains("Path is required"));
  }

  @Test
  void handleJfrOpenValidatesFileExists() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrOpen", Map.class);
    method.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("path", "/nonexistent/path/file.jfr");

    CallToolResult result = (CallToolResult) method.invoke(server, args);

    assertTrue(result.isError());
    assertTrue(result.content().get(0).toString().contains("File not found"));
  }

  @Test
  void handleJfrQueryValidatesNullSessionWithNoCurrentSession() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrQuery", Map.class);
    method.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("query", "events/jdk.ExecutionSample | count()");
    args.put("sessionId", null);

    CallToolResult result = (CallToolResult) method.invoke(server, args);

    assertTrue(result.isError());
    assertTrue(
        result.content().get(0).toString().contains("No session open")
            || result.content().get(0).toString().contains("not found"));
  }

  @Test
  void handleJfrQueryValidatesNullQuery() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrQuery", Map.class);
    method.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("query", null);

    CallToolResult result = (CallToolResult) method.invoke(server, args);

    assertTrue(result.isError());
    assertTrue(result.content().get(0).toString().contains("Query is required"));
  }

  @Test
  void handleJfrQueryValidatesBlankQuery() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrQuery", Map.class);
    method.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("query", "  ");

    CallToolResult result = (CallToolResult) method.invoke(server, args);

    assertTrue(result.isError());
    assertTrue(result.content().get(0).toString().contains("Query is required"));
  }

  @Test
  void handleJfrListTypesValidatesSessionId() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrListTypes", Map.class);
    method.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "nonexistent");

    CallToolResult result = (CallToolResult) method.invoke(server, args);

    assertTrue(result.isError());
  }

  @Test
  void handleJfrCloseValidatesSessionId() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrClose", Map.class);
    method.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("sessionId", "nonexistent");
    args.put("closeAll", false);

    CallToolResult result = (CallToolResult) method.invoke(server, args);

    assertTrue(result.isError());
  }

  @Test
  void handleJfrHelpReturnsOverviewByDefault() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrHelp", Map.class);
    method.setAccessible(true);

    Map<String, Object> args = new HashMap<>();

    CallToolResult result = (CallToolResult) method.invoke(server, args);

    assertFalse(result.isError());
    String content = result.content().get(0).toString();
    assertTrue(content.contains("JfrPath") || content.contains("query"));
  }

  @Test
  void handleJfrHelpReturnsTopic() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("handleJfrHelp", Map.class);
    method.setAccessible(true);

    Map<String, Object> args = new HashMap<>();
    args.put("topic", "filters");

    CallToolResult result = (CallToolResult) method.invoke(server, args);

    assertFalse(result.isError());
    String content = result.content().get(0).toString();
    assertTrue(content.contains("filter") || content.contains("Filter"));
  }

  @Test
  void errorResultCreatesErrorResponse() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("errorResult", String.class);
    method.setAccessible(true);

    CallToolResult result = (CallToolResult) method.invoke(server, "Test error message");

    assertTrue(result.isError());
    assertTrue(result.content().get(0).toString().contains("Test error message"));
  }

  @Test
  void successResultCreatesSuccessResponse() throws Exception {
    var method = JafarMcpServer.class.getDeclaredMethod("successResult", Map.class);
    method.setAccessible(true);

    Map<String, Object> data = new HashMap<>();
    data.put("key", "value");

    CallToolResult result = (CallToolResult) method.invoke(server, data);

    assertFalse(result.isError());
    assertTrue(result.content().get(0).toString().contains("value"));
  }
}

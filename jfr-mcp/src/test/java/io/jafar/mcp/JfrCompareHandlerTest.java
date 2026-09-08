package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Argument validation and tool registration for {@code jfr_compare}. */
class JfrCompareHandlerTest {

  private JafarMcpServer server;

  @BeforeEach
  void setUp() {
    server = new JafarMcpServer();
  }

  @Test
  void rejectsMissingBaseline() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("baselineSessionId", null);
    assertError(invoke(args), "baselineSessionId is required");
  }

  @Test
  void rejectsBlankBaseline() throws Exception {
    assertError(invoke(Map.of("baselineSessionId", "   ")), "baselineSessionId is required");
  }

  @Test
  void rejectsNonPositiveLimit() throws Exception {
    assertError(invoke(Map.of("baselineSessionId", "1", "limit", 0)), "limit must be positive");
    assertError(invoke(Map.of("baselineSessionId", "1", "limit", -3)), "limit must be positive");
  }

  @Test
  void failsClearlyWithNoOpenSession() throws Exception {
    // Validation happens before session lookup, so a valid-looking argument set surfaces the
    // session problem rather than an argument problem.
    CallToolResult result = invoke(Map.of("baselineSessionId", "1"));
    assertTrue(result.isError(), "Expected an error when no sessions are open");
  }

  @Test
  void isRegisteredWithARequiredBaselineArgument() throws Exception {
    Method createTools = JafarMcpServer.class.getDeclaredMethod("createToolSpecifications");
    createTools.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> tools =
        (List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification>)
            createTools.invoke(server);

    var compare =
        tools.stream()
            .filter(t -> "jfr_compare".equals(t.tool().name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("jfr_compare is not registered"));

    assertEquals("jfr_compare", compare.tool().name());
    assertTrue(
        compare.tool().inputSchema().toString().contains("baselineSessionId"),
        "schema should declare baselineSessionId");
  }

  private CallToolResult invoke(Map<String, Object> args) throws Exception {
    Method method = JafarMcpServer.class.getDeclaredMethod("handleJfrCompare", Map.class);
    method.setAccessible(true);
    return (CallToolResult) method.invoke(server, args);
  }

  private void assertError(CallToolResult result, String expectedFragment) {
    assertTrue(result.isError(), "Expected error result");
    String content = result.content().get(0).toString();
    assertTrue(
        content.contains(expectedFragment), "Expected '" + expectedFragment + "' in: " + content);
  }
}

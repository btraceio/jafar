package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandlerCrashSafetyTest {

  @Test
  void throwingHandlerIsConvertedToErrorResult() throws Exception {
    JafarMcpServer server = new JafarMcpServer();
    Method wrap =
        JafarMcpServer.class.getDeclaredMethod("withActivityTracking", SyncToolSpecification.class);
    wrap.setAccessible(true);

    Tool dummyTool =
        Tool.builder()
            .name("boom")
            .description("always throws")
            .inputSchema(McpJsonDefaults.getMapper(), "{\"type\":\"object\"}")
            .build();
    SyncToolSpecification crashing =
        new SyncToolSpecification(
            dummyTool,
            (exchange, args) -> {
              throw new RuntimeException("kaboom");
            });

    SyncToolSpecification wrapped = (SyncToolSpecification) wrap.invoke(server, crashing);
    CallToolResult result =
        wrapped.callHandler().apply(null, new CallToolRequest("boom", Map.of()));

    assertNotNull(result);
    assertTrue(result.isError(), "wrapper must return a structured error result");
    String text = ((TextContent) result.content().get(0)).text();
    assertTrue(
        text.contains("boom"), "error result must include the failing tool name; was: " + text);
  }
}

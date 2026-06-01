package io.jafar.mcp.tool;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;

/** Command abstraction for one MCP tool implementation. */
public interface McpToolCommand {

  ToolDescriptor descriptor();

  CallToolResult execute(
      ToolExecutionContext context,
      McpSyncServerExchange exchange,
      Map<String, Object> arguments,
      Object progressToken);
}

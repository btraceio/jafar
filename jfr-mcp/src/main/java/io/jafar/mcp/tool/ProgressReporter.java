package io.jafar.mcp.tool;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/** Helper for MCP progress notifications. */
public final class ProgressReporter {

  public Object progressToken(McpSchema.CallToolRequest request) {
    var meta = request.meta();
    return meta != null ? meta.get("progressToken") : null;
  }

  public void send(
      McpSyncServerExchange exchange,
      Object progressToken,
      double progress,
      double total,
      String message) {
    if (exchange == null || progressToken == null) {
      return;
    }
    try {
      exchange.progressNotification(
          new McpSchema.ProgressNotification(progressToken, progress, total, message));
    } catch (Exception ignored) {
      // Client may not support progress notifications.
    }
  }
}

package io.jafar.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures;

/** Decorates MCP tool specifications with cross-cutting behavior. */
public interface ToolInterceptor {
  McpServerFeatures.SyncToolSpecification apply(McpServerFeatures.SyncToolSpecification spec);
}

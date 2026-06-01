package io.jafar.mcp.tool;

import java.util.List;

/** Supplies a cohesive group of MCP tool commands, typically for one analysis domain. */
public interface ToolProvider {
  List<McpToolCommand> tools();
}

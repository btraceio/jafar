package io.jafar.mcp.result;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Creates MCP tool results with the response shape expected by existing clients. */
public final class McpResultFactory {

  private final ObjectMapper mapper;

  public McpResultFactory() {
    this(new ObjectMapper());
  }

  public McpResultFactory(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public CallToolResult success(Map<String, Object> data) {
    try {
      String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
      return new CallToolResult(List.of(new TextContent(json)), false, data, null);
    } catch (Exception e) {
      return new CallToolResult(List.of(new TextContent(data.toString())), false, data, null);
    }
  }

  public CallToolResult error(String message) {
    Map<String, Object> error = Map.of("error", message, "success", false);
    try {
      String json = mapper.writeValueAsString(error);
      return new CallToolResult(List.of(new TextContent(json)), true, error, null);
    } catch (Exception e) {
      return new CallToolResult(List.of(new TextContent(message)), true, error, null);
    }
  }
}

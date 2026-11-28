package io.jafar.mcp.result;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

/** Creates MCP tool results with the response shape expected by existing clients. */
public final class McpResultFactory {

  private final ObjectMapper mapper;
  private final ObjectWriter prettyWriter;

  public McpResultFactory() {
    this(new ObjectMapper());
  }

  public McpResultFactory(ObjectMapper mapper) {
    this.mapper = mapper;
    this.prettyWriter = mapper.writerWithDefaultPrettyPrinter();
  }

  /**
   * The result for {@code data}, as text and as structured content. Both are made of {@link
   * PlainValues}: parser wrappers in query rows would otherwise be written as Java beans (#283).
   */
  public CallToolResult success(Map<String, Object> data) {
    // A constant that throws while resolving throws here as it did in serialization before; the
    // tool handler turns it into an error result
    Map<String, Object> plain = PlainValues.of(data);
    try {
      String json = prettyWriter.writeValueAsString(plain);
      return new CallToolResult(List.of(new TextContent(json)), false, plain, null);
    } catch (Exception e) {
      return new CallToolResult(List.of(new TextContent(plain.toString())), false, plain, null);
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

package io.jafar.mcp.tool;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/** Adapts command-style tool implementations to MCP SDK synchronous tool specifications. */
public final class ToolSpecificationFactory {

  private final ToolExecutionContext context;

  public ToolSpecificationFactory(ToolExecutionContext context) {
    this.context = context;
  }

  public McpServerFeatures.SyncToolSpecification create(McpToolCommand command) {
    ToolDescriptor descriptor = command.descriptor();
    Tool tool =
        Tool.builder()
            .name(descriptor.name())
            .description(descriptor.description())
            .inputSchema(McpJsonDefaults.getMapper(), descriptor.inputSchemaJson())
            .build();

    return new McpServerFeatures.SyncToolSpecification(
        tool,
        (exchange, request) ->
            command.execute(
                context,
                exchange,
                request.arguments(),
                context.progressReporter().progressToken(request)));
  }
}

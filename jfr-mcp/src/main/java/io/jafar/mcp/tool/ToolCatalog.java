package io.jafar.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.ArrayList;
import java.util.List;

/** Registry/factory for MCP tool specifications. */
public final class ToolCatalog {

  private final List<McpToolCommand> commands;
  private final ToolSpecificationFactory specificationFactory;

  public ToolCatalog(List<McpToolCommand> commands, ToolSpecificationFactory specificationFactory) {
    this.commands = List.copyOf(commands);
    this.specificationFactory = specificationFactory;
  }

  public static ToolCatalog fromProviders(
      List<ToolProvider> providers, ToolSpecificationFactory specificationFactory) {
    List<McpToolCommand> commands = new ArrayList<>();
    for (ToolProvider provider : providers) {
      commands.addAll(provider.tools());
    }
    return new ToolCatalog(commands, specificationFactory);
  }

  public List<McpServerFeatures.SyncToolSpecification> createSpecifications() {
    return commands.stream().map(specificationFactory::create).toList();
  }
}

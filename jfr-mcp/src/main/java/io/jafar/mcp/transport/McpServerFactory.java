package io.jafar.mcp.transport;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.util.List;

/** Builds configured MCP server instances independent of the chosen transport. */
public final class McpServerFactory {

  private static final String SERVER_NAME = "jafar-mcp";
  private static final String SERVER_VERSION = "0.10.0";

  public McpSyncServer createSyncServer(
      McpServerTransportProvider transportProvider,
      List<McpServerFeatures.SyncToolSpecification> tools) {
    return createSyncServer(transportProvider, tools, List.of(), List.of());
  }

  /**
   * Builds a server that also advertises prompts and resources.
   *
   * <p>Capabilities are declared from what is actually supplied: a client that sees {@code prompts}
   * or {@code resources} in the handshake will list them, so advertising an empty set would be a
   * lie the client pays a round trip to discover.
   */
  public McpSyncServer createSyncServer(
      McpServerTransportProvider transportProvider,
      List<McpServerFeatures.SyncToolSpecification> tools,
      List<McpServerFeatures.SyncPromptSpecification> prompts,
      List<McpServerFeatures.SyncResourceSpecification> resources) {
    ServerCapabilities.Builder capabilities = ServerCapabilities.builder().tools(true).logging();
    if (!prompts.isEmpty()) {
      capabilities.prompts(false);
    }
    if (!resources.isEmpty()) {
      // No subscribe support; listChanged is false because the set is fixed at startup.
      capabilities.resources(false, false);
    }

    var spec =
        McpServer.sync(transportProvider)
            .serverInfo(SERVER_NAME, SERVER_VERSION)
            .capabilities(capabilities.build())
            .tools(tools);
    if (!prompts.isEmpty()) {
      spec = spec.prompts(prompts);
    }
    if (!resources.isEmpty()) {
      spec = spec.resources(resources);
    }
    return spec.build();
  }
}

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
    return McpServer.sync(transportProvider)
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        .capabilities(ServerCapabilities.builder().tools(true).logging().build())
        .tools(tools)
        .build();
  }
}

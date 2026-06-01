package io.jafar.mcp.config;

/** System-property-backed configuration values for the MCP server. */
public final class McpServerConfig {

  /** Cap on rows returned by jfr_query. Configurable via {@code mcp.jfr.query.max-rows}. */
  public static final int MAX_QUERY_ROWS = Integer.getInteger("mcp.jfr.query.max-rows", 50_000);

  /**
   * Cap on flamegraph nodes/paths returned. Configurable via {@code mcp.jfr.flamegraph.max-nodes}.
   */
  public static final int MAX_FLAMEGRAPH_NODES =
      Integer.getInteger("mcp.jfr.flamegraph.max-nodes", 100_000);

  /** Cap on callgraph nodes returned. Configurable via {@code mcp.jfr.callgraph.max-nodes}. */
  public static final int MAX_CALLGRAPH_NODES =
      Integer.getInteger("mcp.jfr.callgraph.max-nodes", 100_000);

  /** Cap on hdump_report findings. Configurable via {@code mcp.hdump.report.max-findings}. */
  public static final int MAX_HDUMP_FINDINGS =
      Integer.getInteger("mcp.hdump.report.max-findings", 5_000);

  private McpServerConfig() {}
}

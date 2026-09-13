package io.jafar.mcp.jfr;

import io.jafar.mcp.config.McpServerConfig;
import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.query.QueryParser;
import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.result.ResultLimiter;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.mcp.tool.ProgressReporter;
import io.jafar.shell.core.analysis.AnalysisTarget;
import io.jafar.shell.core.analysis.JfrAnalyses;
import io.jafar.shell.core.analysis.Progress;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** MCP tool implementations for higher-level JFR analyses. */
public final class JfrAnalysisTools {

  private static final Logger LOG = LoggerFactory.getLogger(JfrAnalysisTools.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The analyses themselves, which no longer live here.
   *
   * <p>They were entangled with {@code CallToolResult} and the progress transport, which is why the
   * shell could not use any of them. This class is now the MCP adapter around them: schemas in,
   * JSON results out.
   */
  private final JfrAnalyses analyses;

  /** Adapts the MCP session registry's view of a session to the analyses' own. */
  private static AnalysisTarget target(SessionRegistry.SessionInfo sessionInfo) {
    return new AnalysisTarget(sessionInfo.id(), sessionInfo.recordingPath(), sessionInfo.session());
  }

  private static final int MAX_FLAMEGRAPH_NODES = McpServerConfig.MAX_FLAMEGRAPH_NODES;
  private static final int MAX_CALLGRAPH_NODES = McpServerConfig.MAX_CALLGRAPH_NODES;

  private final SessionRegistry sessionRegistry;
  private final QueryEvaluator evaluator;
  private final QueryParser queryParser;
  private final McpResultFactory resultFactory;
  private final ProgressReporter progressReporter;

  public JfrAnalysisTools(
      SessionRegistry sessionRegistry,
      QueryEvaluator evaluator,
      QueryParser queryParser,
      McpResultFactory resultFactory,
      ProgressReporter progressReporter) {
    this.sessionRegistry = sessionRegistry;
    this.evaluator = evaluator;
    // The analyses read the recording through the evaluator this server was given, not one of
    // their own: the injection is what lets a test substitute an empty one.
    this.analyses =
        new JfrAnalyses(
            new io.jafar.shell.core.analysis.JfrQuerySource() {
              @Override
              public java.util.List<Map<String, Object>> evaluate(
                  io.jafar.shell.JFRSession session, io.jafar.shell.jfrpath.JfrPath.Query query)
                  throws Exception {
                return evaluator.evaluate(session, query);
              }

              @Override
              public void consume(
                  io.jafar.shell.JFRSession session,
                  io.jafar.shell.jfrpath.JfrPath.Query query,
                  java.util.function.Consumer<Map<String, Object>> consumer)
                  throws Exception {
                evaluator.consume(session, query, consumer);
              }

              @Override
              public Map<String, Long> countAllEventTypes(io.jafar.shell.JFRSession session)
                  throws Exception {
                return evaluator.countAllEventTypes(session);
              }
            });
    this.queryParser = queryParser;
    this.resultFactory = resultFactory;
    this.progressReporter = progressReporter;
  }

  private static Tool buildTool(String name, String description, String schema) {
    return Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(McpJsonDefaults.getMapper(), schema)
        .build();
  }

  private CallToolResult successResult(Map<String, Object> data) {
    return resultFactory.success(data);
  }

  private CallToolResult errorResult(String message) {
    return resultFactory.error(message);
  }

  private Object progressToken(McpSchema.CallToolRequest request) {
    return progressReporter.progressToken(request);
  }

  private void sendProgress(
      McpSyncServerExchange exchange,
      Object progressToken,
      double progress,
      double total,
      String message) {
    progressReporter.send(exchange, progressToken, progress, total, message);
  }

  private static int truncate(List<?> list, int max) {
    return ResultLimiter.truncate(list, max);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrFlamegraphTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "eventType": {
              "type": "string",
              "description": "Event type to analyze (e.g., jdk.ExecutionSample, jdk.ObjectAllocationSample)"
            },
            "direction": {
              "type": "string",
              "description": "Stack direction: bottom-up (hot methods at root) or top-down (entry points at root)",
              "enum": ["bottom-up", "top-down"],
              "default": "bottom-up"
            },
            "format": {
              "type": "string",
              "description": "Output format: folded (semicolon-separated) or tree (JSON)",
              "enum": ["folded", "tree"],
              "default": "folded"
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "minSamples": {
              "type": "integer",
              "description": "Minimum sample count to include in output (default: 1)"
            },
            "maxDepth": {
              "type": "integer",
              "description": "Maximum stack depth to include (default: unlimited)"
            }
          },
          "required": ["eventType"]
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_flamegraph",
            "Generates aggregated stack trace data for flamegraph-style analysis. "
                + "Returns stack paths with sample counts in folded or tree format. "
                + "Use direction=bottom-up to see hot methods (where time is spent), "
                + "or direction=top-down to see call paths from entry points. "
                + "Folded format is semicolon-separated paths compatible with standard flamegraph tools.",
            schema),
        (exchange, args) -> handleJfrFlamegraph(exchange, args.arguments(), progressToken(args)));
  }

  public CallToolResult handleJfrFlamegraph(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String eventType = (String) args.get("eventType");
    String direction = (String) args.getOrDefault("direction", "bottom-up");
    String format = (String) args.getOrDefault("format", "folded");
    String sessionId = (String) args.get("sessionId");
    Integer minSamples = args.get("minSamples") instanceof Number n ? n.intValue() : 1;
    Integer maxDepth = args.get("maxDepth") instanceof Number n ? n.intValue() : null;

    if (eventType == null || eventType.isBlank()) {
      return errorResult("Event type is required");
    }
    if (!"bottom-up".equals(direction) && !"top-down".equals(direction)) {
      return errorResult("direction must be 'bottom-up' or 'top-down'");
    }
    if (!"folded".equals(format) && !"tree".equals(format)) {
      return errorResult("format must be 'folded' or 'tree'");
    }
    if (minSamples != null && minSamples < 1) {
      return errorResult("minSamples must be >= 1");
    }
    if (maxDepth != null && maxDepth < 1) {
      return errorResult("maxDepth must be >= 1");
    }

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      // Query all events with non-empty stack traces
      sendProgress(exchange, progressToken, 0, 2, "Querying events...");
      JfrPath.Query parsed = queryParser.parse("events/" + eventType);

      // Build aggregation tree
      sendProgress(exchange, progressToken, 1, 2, "Building flamegraph tree...");
      FlameNode root = new FlameNode("root");
      LongAdder processedEvents = new LongAdder();

      evaluator.consume(
          sessionInfo.session(),
          parsed,
          event -> {
            List<String> frames = analyses.extractFrames(event, direction, maxDepth);
            if (!frames.isEmpty()) {
              root.addPath(frames);
              processedEvents.increment();
            }
          });

      // Pre-format structural cap: refuse oversized trees uniformly across formats.
      int totalNodes = countNodes(root);
      if (totalNodes > MAX_FLAMEGRAPH_NODES) {
        return errorResult(
            "flamegraph would exceed "
                + MAX_FLAMEGRAPH_NODES
                + " nodes; tighten the query or raise mcp.jfr.flamegraph.max-nodes");
      }

      // Format output
      sendProgress(exchange, progressToken, 2, 2, "Done");
      if ("tree".equals(format)) {
        return formatFlamegraphTree(root, direction, (int) processedEvents.sum(), minSamples);
      } else {
        return formatFlamegraphFolded(root, minSamples);
      }

    } catch (IllegalArgumentException e) {
      LOG.warn("Flamegraph error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to generate flamegraph: {}", e.getMessage(), e);
      return errorResult("Failed to generate flamegraph: " + e.getMessage());
    }
  }

  /** Unwraps Jafar wrapper types (ArrayType, ComplexType) to their underlying values. */
  @SuppressWarnings("unchecked")
  public CallToolResult formatFlamegraphFolded(FlameNode root, int minSamples) {
    List<String> lines = new ArrayList<>();
    List<String> path = new ArrayList<>();
    collectFoldedPaths(root, path, lines, minSamples);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("format", "folded");
    result.put("totalSamples", root.value.sum());

    int dropped = truncate(lines, MAX_FLAMEGRAPH_NODES);
    if (dropped > 0) {
      LOG.warn("jfr_flamegraph truncated {} paths beyond cap {}", dropped, MAX_FLAMEGRAPH_NODES);
      result.put("truncated", true);
      result.put("droppedRows", dropped);
    }

    StringBuilder sb = new StringBuilder();
    for (String line : lines) {
      sb.append(line).append("\n");
    }
    result.put("data", sb.toString());
    return successResult(result);
  }

  private void collectFoldedPaths(
      FlameNode node, List<String> path, List<String> lines, int minSamples) {
    if (node.children.isEmpty()) {
      // Leaf node - output the path
      if (node.value.sum() >= minSamples && !path.isEmpty()) {
        lines.add(String.join(";", path) + " " + node.value.sum());
      }
    } else {
      for (Map.Entry<String, FlameNode> entry : node.children.entrySet()) {
        path.add(entry.getKey());
        collectFoldedPaths(entry.getValue(), path, lines, minSamples);
        path.remove(path.size() - 1);
      }
    }
  }

  private CallToolResult formatFlamegraphTree(
      FlameNode root, String direction, int processedEvents, int minSamples) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("format", "tree");
    result.put("direction", direction);
    result.put("totalSamples", processedEvents);
    result.put("root", nodeToMap(root, minSamples));
    return successResult(result);
  }

  private Map<String, Object> nodeToMap(FlameNode node, int minSamples) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", node.name);
    map.put("value", node.value.sum());

    if (!node.children.isEmpty()) {
      List<Map<String, Object>> children = new ArrayList<>();
      for (FlameNode child : node.children.values()) {
        if (child.value.sum() >= minSamples) {
          children.add(nodeToMap(child, minSamples));
        }
      }
      // Sort children by value descending
      children.sort((a, b) -> Long.compare((Long) b.get("value"), (Long) a.get("value")));
      map.put("children", children);
    }
    return map;
  }

  /** Tree node for flamegraph aggregation. */
  private static class FlameNode {
    final String name;
    final LongAdder value = new LongAdder();
    final Map<String, FlameNode> children = new ConcurrentHashMap<>();

    FlameNode(String name) {
      this.name = name;
    }

    void addPath(List<String> frames) {
      value.increment();
      if (!frames.isEmpty()) {
        String head = frames.get(0);
        children.computeIfAbsent(head, FlameNode::new).addPath(frames.subList(1, frames.size()));
      }
    }
  }

  /** Counts {@code n} plus all of its descendants via an iterative walk. */
  private static int countNodes(FlameNode n) {
    if (n == null) return 0;
    int count = 0;
    ArrayDeque<FlameNode> stack = new ArrayDeque<>();
    stack.push(n);
    while (!stack.isEmpty()) {
      FlameNode current = stack.pop();
      count++;
      for (FlameNode child : current.children.values()) {
        stack.push(child);
      }
    }
    return count;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_callgraph
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrCallgraphTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "eventType": {
              "type": "string",
              "description": "Event type to analyze (e.g., jdk.ExecutionSample, jdk.ObjectAllocationSample)"
            },
            "format": {
              "type": "string",
              "description": "Output format: dot (graphviz) or json",
              "enum": ["dot", "json"],
              "default": "dot"
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "minWeight": {
              "type": "integer",
              "description": "Minimum edge weight to include (default: 1)"
            }
          },
          "required": ["eventType"]
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_callgraph",
            "Generates a call graph showing caller-callee relationships from stack traces. "
                + "Unlike flamegraph (which preserves full paths), this shows which methods call which, "
                + "revealing convergence points where multiple callers invoke the same method. "
                + "DOT format can be visualized with graphviz. JSON format includes node and edge data.",
            schema),
        (exchange, args) -> handleJfrCallgraph(exchange, args.arguments(), progressToken(args)));
  }

  public CallToolResult handleJfrCallgraph(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String eventType = (String) args.get("eventType");
    String format = (String) args.getOrDefault("format", "dot");
    String sessionId = (String) args.get("sessionId");
    Integer minWeight = args.get("minWeight") instanceof Number n ? n.intValue() : 1;

    if (eventType == null || eventType.isBlank()) {
      return errorResult("Event type is required");
    }
    if (!"dot".equals(format) && !"json".equals(format)) {
      return errorResult("format must be 'dot' or 'json'");
    }
    if (minWeight < 1) {
      return errorResult("minWeight must be >= 1");
    }

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      // Query all events
      sendProgress(exchange, progressToken, 0, 2, "Querying events...");
      JfrPath.Query parsed = queryParser.parse("events/" + eventType);

      // Build call graph
      sendProgress(exchange, progressToken, 1, 2, "Building call graph...");
      CallGraph graph = new CallGraph();
      LongAdder processedEvents = new LongAdder();

      evaluator.consume(
          sessionInfo.session(),
          parsed,
          event -> {
            List<String> frames =
                analyses.extractFrames(
                    event, "top-down", null); // top-down for caller->callee order
            if (!frames.isEmpty()) {
              graph.addStack(frames);
              processedEvents.increment();
            }
          });

      // Compute inDegree for convergence point detection
      graph.computeInDegree();

      // Pre-format structural cap: refuse oversized graphs uniformly across formats.
      int totalNodes = graph.nodeSamples.size();
      if (totalNodes > MAX_CALLGRAPH_NODES) {
        return errorResult(
            "callgraph would exceed "
                + MAX_CALLGRAPH_NODES
                + " nodes; tighten the query or raise mcp.jfr.callgraph.max-nodes");
      }

      // Format output
      sendProgress(exchange, progressToken, 2, 2, "Done");
      if ("json".equals(format)) {
        return formatCallgraphJson(graph, (int) processedEvents.sum(), minWeight);
      } else {
        return formatCallgraphDot(graph, minWeight);
      }

    } catch (IllegalArgumentException e) {
      LOG.warn("Callgraph error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to generate callgraph: {}", e.getMessage(), e);
      return errorResult("Failed to generate callgraph: " + e.getMessage());
    }
  }

  public CallToolResult formatCallgraphDot(CallGraph graph, int minWeight) {
    StringBuilder sb = new StringBuilder();
    sb.append("digraph callgraph {\n");
    sb.append("  rankdir=TB;\n");
    sb.append("  node [shape=box, fontsize=10];\n");
    sb.append("  edge [fontsize=8];\n\n");

    // Output edges
    for (Map.Entry<String, Long> edge : graph.edges.entrySet()) {
      if (edge.getValue() < minWeight) {
        continue;
      }
      String[] parts = edge.getKey().split("->");
      if (parts.length == 2) {
        String from = escapeForDot(parts[0]);
        String to = escapeForDot(parts[1]);
        sb.append("  \"")
            .append(from)
            .append("\" -> \"")
            .append(to)
            .append("\" [label=\"")
            .append(edge.getValue())
            .append("\"];\n");
      }
    }

    sb.append("}\n");

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("format", "dot");
    result.put("totalSamples", graph.totalSamples.sum());
    result.put("nodeCount", graph.nodeSamples.size());
    result.put("edgeCount", graph.edges.size());
    result.put("data", sb.toString());
    return successResult(result);
  }

  private String escapeForDot(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  public CallToolResult formatCallgraphJson(CallGraph graph, int processedEvents, int minWeight) {
    // Build nodes list
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (Map.Entry<String, Long> entry : graph.nodeSamples.entrySet()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", entry.getKey());
      node.put("samples", entry.getValue());
      Integer inDegree = graph.inDegree.get(entry.getKey());
      if (inDegree != null && inDegree > 1) {
        node.put("inDegree", inDegree); // Only show if convergence point
      }
      nodes.add(node);
    }
    // Sort by samples descending
    nodes.sort((a, b) -> Long.compare((Long) b.get("samples"), (Long) a.get("samples")));

    // Build edges list
    List<Map<String, Object>> edges = new ArrayList<>();
    for (Map.Entry<String, Long> entry : graph.edges.entrySet()) {
      if (entry.getValue() < minWeight) {
        continue;
      }
      String[] parts = entry.getKey().split("->");
      if (parts.length == 2) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", parts[0]);
        edge.put("to", parts[1]);
        edge.put("weight", entry.getValue());
        edges.add(edge);
      }
    }
    // Sort by weight descending
    edges.sort((a, b) -> Long.compare((Long) b.get("weight"), (Long) a.get("weight")));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("format", "json");
    result.put("totalSamples", processedEvents);

    int dropped = truncate(nodes, MAX_CALLGRAPH_NODES);
    if (dropped > 0) {
      LOG.warn("jfr_callgraph truncated {} nodes beyond cap {}", dropped, MAX_CALLGRAPH_NODES);
      result.put("truncated", true);
      result.put("droppedRows", dropped);
    }
    result.put("nodes", nodes);
    result.put("edges", edges);
    return successResult(result);
  }

  /** Graph structure for caller-callee relationship aggregation. */
  private static class CallGraph {
    final Map<String, Long> nodeSamples = new ConcurrentHashMap<>();
    final Map<String, Long> edges = new ConcurrentHashMap<>();
    final Map<String, Integer> inDegree = new HashMap<>();
    final LongAdder totalSamples = new LongAdder();

    void addStack(List<String> frames) {
      totalSamples.increment();

      // Process caller->callee pairs
      for (int i = 0; i < frames.size() - 1; i++) {
        String caller = frames.get(i);
        String callee = frames.get(i + 1);
        String edge = caller + "->" + callee;

        edges.merge(edge, 1L, Long::sum);
        nodeSamples.merge(caller, 1L, Long::sum);
      }

      // Count the leaf node too
      if (!frames.isEmpty()) {
        String leaf = frames.get(frames.size() - 1);
        nodeSamples.merge(leaf, 1L, Long::sum);
      }
    }

    /** Compute inDegree from edges map (number of unique callers per method). */
    void computeInDegree() {
      inDegree.clear();
      for (String edge : edges.keySet()) {
        String[] parts = edge.split("->");
        if (parts.length == 2) {
          String callee = parts[1];
          inDegree.merge(callee, 1, Integer::sum);
        }
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_exceptions
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrExceptionsTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "eventType": {
              "type": "string",
              "description": "Exception event type (e.g., datadog.ExceptionSample, jdk.JavaExceptionThrow). Auto-detects if not specified."
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "minCount": {
              "type": "integer",
              "description": "Minimum exception count to include in results (default: 1)"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of exception types to return (default: 50)"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_exceptions",
            "Analyzes exception events in a JFR recording. Extracts exception types from stack traces, "
                + "groups by exception class, and identifies throw sites. Works with both JDK exception events "
                + "(jdk.JavaExceptionThrow) and profiler exception samples (datadog.ExceptionSample). "
                + "Returns exception type counts, throw site locations, and patterns.",
            schema),
        (exchange, args) -> handleJfrExceptions(exchange, args.arguments(), progressToken(args)));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_summary
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrSummaryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_summary",
            "Provides a quick overview of a JFR recording including duration, event counts, "
                + "and key highlights like GC statistics, exception rates, and top CPU consumers. "
                + "Useful for getting oriented with a new recording before deeper analysis.",
            schema),
        (exchange, args) -> handleJfrSummary(exchange, args.arguments(), progressToken(args)));
  }

  public CallToolResult handleJfrSummary(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String sessionId = (String) args.get("sessionId");
    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);
      return successResult(
          analyses.summary(
              target(sessionInfo),
              (current, total, message) ->
                  sendProgress(exchange, progressToken, current, total, message)));
    } catch (Exception e) {
      LOG.error("Failed to generate summary: {}", e.getMessage(), e);
      return errorResult("Failed to generate summary: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_hotmethods
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrHotmethodsTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "eventType": {
              "type": "string",
              "description": "Execution sample event type (e.g., datadog.ExecutionSample, jdk.ExecutionSample). Auto-detects if not specified."
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of methods to return (default: 20)"
            },
            "includeNative": {
              "type": "boolean",
              "description": "Include native/VM methods (default: true)"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_hotmethods",
            "Returns the hottest methods (leaf frames) from CPU profiling samples. "
                + "Simpler and more compact than full flamegraph - just shows which methods are consuming CPU. "
                + "Useful for quick CPU hotspot identification.",
            schema),
        (exchange, args) -> handleJfrHotmethods(exchange, args.arguments(), progressToken(args)));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_use - USE Method Analysis (Utilization, Saturation, Errors)
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrUseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "startTime": {
              "type": "integer",
              "description": "Start time in nanoseconds from recording start (optional)"
            },
            "endTime": {
              "type": "integer",
              "description": "End time in nanoseconds from recording start (optional)"
            },
            "resources": {
              "type": "array",
              "items": {
                "type": "string",
                "enum": ["cpu", "memory", "threads", "io", "all"]
              },
              "description": "Which resources to analyze (default: all)"
            },
            "includeInsights": {
              "type": "boolean",
              "description": "Include actionable insights and recommendations (default: true)"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_use",
            "Analyzes JFR recording using Brendan Gregg's USE Method (Utilization, Saturation, Errors). "
                + "Examines CPU, Memory, Threads/Locks, and I/O resources to identify bottlenecks. "
                + "Returns metrics for utilization (how busy), saturation (queued work), and errors for each resource.",
            schema),
        (exchange, args) -> handleJfrUse(exchange, args.arguments(), progressToken(args)));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_tsa - Thread State Analysis (TSA Method)
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrTsaTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "startTime": {
              "type": "integer",
              "description": "Start time in nanoseconds from recording start (optional)"
            },
            "endTime": {
              "type": "integer",
              "description": "End time in nanoseconds from recording start (optional)"
            },
            "topThreads": {
              "type": "integer",
              "description": "Number of top threads to analyze per state (default: 10)"
            },
            "minSamples": {
              "type": "integer",
              "description": "Minimum samples for a thread to be included (default: 5)"
            },
            "correlateBlocking": {
              "type": "boolean",
              "description": "Correlate blocking states with lock/monitor events (default: true)"
            },
            "includeInsights": {
              "type": "boolean",
              "description": "Include actionable insights and recommendations (default: true)"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_tsa",
            "Analyzes JFR recording using Thread State Analysis (TSA) methodology. "
                + "Shows how threads spend their time across different states (RUNNABLE, WAITING, BLOCKED, etc.). "
                + "Identifies problematic threads and correlates blocking states with contended locks/monitors.",
            schema),
        (exchange, args) -> handleJfrTsa(exchange, args.arguments(), progressToken(args)));
  }

  /** Helper class to track per-thread state metrics. */

  /** Helper class to track monitor correlation data. */

  /** Helper class to track queue correlation data. */

  // ─────────────────────────────────────────────────────────────────────────────
  // Shared helper methods for USE and TSA analysis
  // ─────────────────────────────────────────────────────────────────────────────

  /** Extract thread state from ExecutionSample event (handles both jdk and datadog formats). */

  /** Extract thread ID from event. */

  /** Extract thread name from event. */

  /** Extract simple class name from fully qualified name. */

  /** Build JfrPath time filter for time-window queries. */

  /** Assess CPU utilization level. */

  /** Assess memory pressure based on heap usage and GC time. */

  /** Assess thread behavior based on state distribution. */

  /** Assess queue saturation level based on average queue time. */

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_diagnose
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrDiagnoseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "includeAnalysis": {
              "type": "boolean",
              "description": "Include full analysis results from triggered tools (default: true)"
            },
            "depth": {
              "type": "string",
              "enum": ["quick", "full"],
              "description": "quick = summary-derived thresholds only; full (default) also runs the USE and TSA analyses in-process and merges their findings"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_diagnose",
            "Diagnoses performance issues in a JFR recording by running the appropriate analyses "
                + "and merging their results. Covers exception rates, GC pressure, CPU hotspots, "
                + "resource bottlenecks (USE) and thread states (TSA), and returns severity-ranked "
                + "structured findings plus the capability gaps that limit what this recording can "
                + "answer. Use this as the first step on an unfamiliar recording; pass depth=quick "
                + "to skip the USE and TSA passes on very large files.",
            schema),
        (exchange, args) -> handleJfrDiagnose(exchange, args.arguments(), progressToken(args)));
  }

  /**
   * Turns the top entries of a {@code jfr_hotmethods} result into findings.
   *
   * <p>Only frames above the 5% self-time mark become findings: below that, a single leaf frame is
   * rarely worth a recommendation on its own, and the flat list is better read as a whole.
   */
  @SuppressWarnings("unchecked")
  public CallToolResult handleJfrExceptions(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return wrap(
        args, exchange, progressToken, "Failed to analyze exceptions", analyses::exceptions);
  }

  public CallToolResult handleJfrHotmethods(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return wrap(
        args, exchange, progressToken, "Failed to analyze hot methods", analyses::hotmethods);
  }

  public CallToolResult handleJfrUse(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return wrap(args, exchange, progressToken, "Failed to perform USE analysis", analyses::use);
  }

  public CallToolResult handleJfrTsa(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return wrap(args, exchange, progressToken, "Failed to perform TSA analysis", analyses::tsa);
  }

  public CallToolResult handleJfrDiagnose(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return wrap(args, exchange, progressToken, "Failed to diagnose recording", analyses::diagnose);
  }

  // ── Helpers other MCP tools reach through this class ───────────────────────
  // JfrCompareTools already depended on these, so the seam is kept rather than moved: they now
  // forward to the single implementation instead of being a second copy of it.

  public String detectExecutionEventType(SessionRegistry.SessionInfo sessionInfo) {
    return analyses.detectExecutionEventType(target(sessionInfo));
  }

  public List<String> extractFrames(Map<String, Object> event, String direction, Integer maxDepth) {
    return analyses.extractFrames(event, direction, maxDepth);
  }

  public String extractMethodName(Object frame) {
    return analyses.extractMethodName(frame);
  }

  public boolean isNativeMethod(String methodName) {
    return analyses.isNativeMethod(methodName);
  }

  /** An analysis that needs the session, its arguments, and somewhere to report progress. */
  @FunctionalInterface
  private interface Analysis {
    Map<String, Object> run(AnalysisTarget target, Map<String, Object> args, Progress progress)
        throws Exception;
  }

  /**
   * Runs an analysis and turns its outcome into MCP's shape.
   *
   * <p>This is all that is left of the handlers: resolve the session, forward progress, and
   * translate an exception into the error text the tool has always returned. The distinction
   * between {@link IllegalArgumentException} and everything else is preserved — the first is a
   * caller's mistake and is reported as-is, the rest are failures and get the tool's prefix.
   */
  private CallToolResult wrap(
      Map<String, Object> args,
      McpSyncServerExchange exchange,
      Object progressToken,
      String failurePrefix,
      Analysis analysis) {
    try {
      SessionRegistry.SessionInfo sessionInfo =
          sessionRegistry.getOrCurrent((String) args.get("sessionId"));
      return successResult(
          analysis.run(
              target(sessionInfo),
              args,
              (current, total, message) ->
                  sendProgress(exchange, progressToken, current, total, message)));
    } catch (IllegalArgumentException e) {
      LOG.warn("{}: {}", failurePrefix, e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("{}: {}", failurePrefix, e.getMessage(), e);
      return errorResult(failurePrefix + ": " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_stackprofile - Structured stack profiling with time-series and threads
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrStackprofileTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "eventType": {
              "type": "string",
              "description": "Execution sample event type (e.g., jdk.ExecutionSample, datadog.ExecutionSample). Auto-detects if not specified."
            },
            "direction": {
              "type": "string",
              "description": "Stack direction: top-down (entry points first) or bottom-up (hot methods first)",
              "enum": ["top-down", "bottom-up"],
              "default": "top-down"
            },
            "buckets": {
              "type": "integer",
              "description": "Number of time buckets for temporal distribution (default: 10)"
            },
            "minPct": {
              "type": "number",
              "description": "Minimum percentage threshold to include a frame (default: 1.0)"
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of frames to return (default: 200)"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_stackprofile",
            "Returns structured stack profiling data with time-series distribution and per-thread "
                + "breakdown for each frame. Unlike jfr_flamegraph (which returns aggregated stack paths "
                + "for visualization), this tool returns machine-readable JSON with: (1) raw time-bucket "
                + "arrays showing how each method's samples distribute over the recording duration — use "
                + "this to detect bursty vs steady hotspots and N+1 query patterns; (2) per-thread sample "
                + "counts revealing thread affinity; (3) numeric percentage fields and a derived category "
                + "(normal/hotspot/steady-hotspot). Choose jfr_stackprofile when you need to programmatically "
                + "analyze CPU behavior over time or across threads. Choose jfr_flamegraph when you need "
                + "aggregated call-path data for visualization or simple hotspot listing.",
            schema),
        (exchange, args) -> handleJfrStackprofile(exchange, args.arguments(), progressToken(args)));
  }

  @SuppressWarnings("unchecked")
  public CallToolResult handleJfrStackprofile(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String eventType = (String) args.get("eventType");
    String direction = (String) args.getOrDefault("direction", "top-down");
    int buckets = args.get("buckets") instanceof Number n ? n.intValue() : 10;
    double minPct = args.get("minPct") instanceof Number n ? n.doubleValue() : 1.0;
    String sessionId = (String) args.get("sessionId");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 200;

    if (!"top-down".equals(direction) && !"bottom-up".equals(direction)) {
      return errorResult("direction must be 'top-down' or 'bottom-up'");
    }
    if (buckets < 1) {
      return errorResult("buckets must be >= 1");
    }
    if (minPct < 0) {
      return errorResult("minPct must be >= 0");
    }
    if (limit < 1) {
      return errorResult("limit must be >= 1");
    }

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      // Auto-detect execution sample event type if not specified
      if (eventType == null || eventType.isBlank()) {
        eventType = analyses.detectExecutionEventType(target(sessionInfo));
        if (eventType == null) {
          return errorResult(
              "No execution sample events found in recording. "
                  + "Specify eventType explicitly (e.g., jdk.ExecutionSample or datadog.ExecutionSample)");
        }
      }

      // Build and execute stackprofile query
      String query =
          "events/"
              + eventType
              + " | stackprofile(direction="
              + direction
              + ", buckets="
              + buckets
              + ", minPct="
              + minPct
              + ")";
      JfrPath.Query parsed = queryParser.parse(query);
      JfrPathEvaluator.ProgressListener progress =
          (p, t, msg) -> sendProgress(exchange, progressToken, p, t, msg);
      List<Map<String, Object>> rows = evaluator.evaluate(sessionInfo.session(), parsed, progress);

      // Transform TUI rows into structured JSON
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("eventType", eventType);
      result.put("direction", direction);
      result.put("bucketCount", buckets);
      result.put("minPct", minPct);

      List<Map<String, Object>> frames = new ArrayList<>();
      long totalSamples = 0;

      for (Map<String, Object> row : rows) {
        if (frames.size() >= limit) break;

        Map<String, Object> frame = new LinkedHashMap<>();

        // Extract method name and depth from indented method string
        String methodStr = (String) row.get("method");
        if (methodStr == null) continue;
        int depth = 0;
        while (depth < methodStr.length() && methodStr.charAt(depth) == ' ') {
          depth++;
        }
        frame.put("method", methodStr.substring(depth));
        frame.put("depth", depth);

        // Build structured profile from the profile sub-map
        Map<String, Object> srcProfile = (Map<String, Object>) row.get("profile");
        if (srcProfile != null) {
          Map<String, Object> profile = new LinkedHashMap<>();
          long self = srcProfile.get("self") instanceof Number n ? n.longValue() : 0L;
          long total = srcProfile.get("total") instanceof Number n ? n.longValue() : 0L;
          profile.put("self", self);
          profile.put("total", total);

          // Convert percentage strings to doubles
          double totalPctVal = parsePercentage(srcProfile.get("totalPct"));
          double selfPctVal = parsePercentage(srcProfile.get("selfPct"));
          profile.put("totalPct", totalPctVal);
          profile.put("selfPct", selfPctVal);

          String pattern = (String) srcProfile.get("pattern");
          profile.put("pattern", pattern);

          // Derive category using selfPctOfTotal (self as % of root total),
          // matching the TUI marker logic in JfrPathEvaluator.flattenNode()
          double selfPctOfTotal = totalPctVal * selfPctVal / 100.0;
          String category;
          if (selfPctOfTotal >= 1.0 && "steady".equals(pattern)) {
            category = "steady-hotspot";
          } else if (selfPctOfTotal >= 1.0) {
            category = "hotspot";
          } else {
            category = "normal";
          }
          profile.put("category", category);

          // Pass through raw timeBuckets and threadCounts
          Object timeBucketsObj = srcProfile.get("timeBuckets");
          if (timeBucketsObj instanceof long[] tb) {
            profile.put("timeBuckets", tb);
          }
          Object threadCountsObj = srcProfile.get("threadCounts");
          if (threadCountsObj instanceof Map<?, ?> tc) {
            profile.put("threadCounts", tc);
          }

          frame.put("profile", profile);

          // Sum total of depth-0 frames for totalSamples
          if (depth == 0) {
            totalSamples += total;
          }
        }

        frames.add(frame);
      }

      result.put("totalSamples", totalSamples);
      result.put("frameCount", frames.size());
      result.put("frames", frames);

      return successResult(result);

    } catch (IllegalArgumentException e) {
      LOG.warn("Stackprofile error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to analyze stack profile: {}", e.getMessage(), e);
      return errorResult("Failed to analyze stack profile: " + e.getMessage());
    }
  }

  private static double parsePercentage(Object value) {
    if (value instanceof Number n) return n.doubleValue();
    if (value instanceof String s) {
      String stripped = s.endsWith("%") ? s.substring(0, s.length() - 1).trim() : s.trim();
      try {
        return Double.parseDouble(stripped);
      } catch (NumberFormatException e) {
        return 0.0;
      }
    }
    return 0.0;
  }
}

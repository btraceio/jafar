package io.jafar.mcp.jfr;

import io.jafar.mcp.config.McpServerConfig;
import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.query.QueryParser;
import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.result.ResultLimiter;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.mcp.tool.ProgressReporter;
import io.jafar.parser.api.Values;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** MCP tool implementations for higher-level JFR analyses. */
public final class JfrAnalysisTools {

  private static final Logger LOG = LoggerFactory.getLogger(JfrAnalysisTools.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> BLOCKING_STATES =
      Set.of("WAITING", "BLOCKED", "PARKED", "TIMED_WAITING");
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
            List<String> frames = extractFrames(event, direction, maxDepth);
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
  private Object unwrapValue(Object obj) {
    if (obj instanceof io.jafar.parser.api.ArrayType arr) {
      return arr.getArray();
    }
    if (obj instanceof io.jafar.parser.api.ComplexType ct) {
      return ct.getValue();
    }
    return obj;
  }

  @SuppressWarnings("unchecked")
  private List<String> extractFrames(
      Map<String, Object> event, String direction, Integer maxDepth) {
    List<String> frames = new ArrayList<>();

    Object stackTrace = event.get("stackTrace");
    if (stackTrace == null) {
      return frames;
    }

    Object framesObj = null;
    if (stackTrace instanceof Map<?, ?> stMap) {
      framesObj = stMap.get("frames");
    }

    if (framesObj == null) {
      return frames;
    }

    // Unwrap {type: ..., array: [...]} wrapper if present
    framesObj = unwrapValue(framesObj);

    // Handle array of frames
    Object[] frameArray = null;
    if (framesObj != null && framesObj.getClass().isArray()) {
      int len = java.lang.reflect.Array.getLength(framesObj);
      frameArray = new Object[len];
      for (int i = 0; i < len; i++) {
        frameArray[i] = java.lang.reflect.Array.get(framesObj, i);
      }
    } else if (framesObj instanceof List<?> list) {
      frameArray = list.toArray();
    }

    if (frameArray == null || frameArray.length == 0) {
      return frames;
    }

    // Extract method names from frames
    for (Object frame : frameArray) {
      String methodName = extractMethodName(frame);
      if (methodName != null) {
        frames.add(methodName);
      }
      if (maxDepth != null && frames.size() >= maxDepth) {
        break;
      }
    }

    // For bottom-up: frames[0] is the hot method (leaf), walk to callers
    // JFR stores frames with index 0 = top of stack (most recent call)
    // So for bottom-up we keep order as-is (hot method first)
    // For top-down we reverse (entry point first)
    if ("top-down".equals(direction)) {
      java.util.Collections.reverse(frames);
    }

    return frames;
  }

  @SuppressWarnings("unchecked")
  public String extractMethodName(Object frame) {
    if (frame == null) {
      return null;
    }

    Map<String, Object> frameMap = null;
    if (frame instanceof Map<?, ?> fm) {
      frameMap = (Map<String, Object>) fm;
    } else {
      return null;
    }

    Object method = frameMap.get("method");
    if (method == null) {
      return null;
    }

    // Unwrap {value: ...} wrapper if present (Datadog format)
    method = unwrapValue(method);

    Map<String, Object> methodMap = null;
    if (method instanceof Map<?, ?> mm) {
      methodMap = (Map<String, Object>) mm;
    } else {
      return null;
    }

    // Get class name - handle nested value wrappers
    String className = "";
    Object type = unwrapValue(methodMap.get("type"));
    if (type instanceof Map<?, ?> typeMap) {
      Object name = unwrapValue(typeMap.get("name"));
      if (name instanceof Map<?, ?> nameMap) {
        Object str = nameMap.get("string");
        if (str != null) {
          className = str.toString();
        }
      } else if (name != null) {
        className = name.toString();
      }
    }

    // Get method name - handle nested value wrappers
    String methodName = "";
    Object nameObj = unwrapValue(methodMap.get("name"));
    if (nameObj instanceof Map<?, ?> nameMap) {
      Object str = nameMap.get("string");
      if (str != null) {
        methodName = str.toString();
      }
    } else if (nameObj != null) {
      methodName = nameObj.toString();
    }

    if (className.isEmpty() && methodName.isEmpty()) {
      return null;
    }

    return className.isEmpty() ? methodName : className + "." + methodName;
  }

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
                extractFrames(event, "top-down", null); // top-down for caller->callee order
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

  public CallToolResult handleJfrExceptions(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String eventType = (String) args.get("eventType");
    String sessionId = (String) args.get("sessionId");
    int minCount = args.get("minCount") instanceof Number n ? n.intValue() : 1;
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 50;

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      // Auto-detect exception event type if not specified
      if (eventType == null || eventType.isBlank()) {
        eventType = detectExceptionEventType(sessionInfo);
        if (eventType == null) {
          return errorResult(
              "No exception events found in recording. "
                  + "Specify eventType explicitly (e.g., jdk.JavaExceptionThrow or datadog.ExceptionSample)");
        }
      }

      // Query and stream exception events, accumulating analysis without materialising the list
      sendProgress(exchange, progressToken, 0, 2, "Querying exception events...");
      JfrPath.Query parsed = queryParser.parse("events/" + eventType);
      ExceptionAnalysis analysis = new ExceptionAnalysis();
      evaluator.consume(
          sessionInfo.session(),
          parsed,
          event -> {
            analysis.totalEvents.increment();
            ExceptionInfo info = extractExceptionInfo(event);
            if (info.exceptionType != null) {
              analysis.totalExceptions.increment();
              analysis.exceptionTypes.merge(info.exceptionType, 1L, Long::sum);
              if (info.throwSite != null) {
                analysis.throwSites.merge(info.throwSite, 1L, Long::sum);
                analysis
                    .throwSitesByType
                    .computeIfAbsent(info.exceptionType, k -> new ConcurrentHashMap<>())
                    .merge(info.throwSite, 1L, Long::sum);
              }
            }
          });
      // Compute top throw site per exception type
      for (Map.Entry<String, Map<String, Long>> entry : analysis.throwSitesByType.entrySet()) {
        entry.getValue().entrySet().stream()
            .max(Comparator.comparingLong(Map.Entry::getValue))
            .ifPresent(e -> analysis.topThrowSiteByType.put(entry.getKey(), e.getKey()));
      }

      long totalEvents = analysis.totalEvents.sum();
      if (totalEvents == 0) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventType);
        result.put("totalExceptions", 0);
        result.put("message", "No exception events found for type: " + eventType);
        return successResult(result);
      }

      sendProgress(exchange, progressToken, 1, 2, "Analyzing exception patterns...");

      // Build result
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("eventType", eventType);
      result.put("totalExceptions", analysis.totalExceptions.sum());

      // Exception types by frequency
      List<Map<String, Object>> byType = new ArrayList<>();
      analysis.exceptionTypes.entrySet().stream()
          .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
          .filter(e -> e.getValue() >= minCount)
          .limit(limit)
          .forEach(
              e -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                String fullName = e.getKey();
                entry.put("type", extractSimpleName(fullName));
                entry.put("fullType", fullName);
                entry.put("count", e.getValue());
                entry.put("pct", String.format("%.1f%%", e.getValue() * 100.0 / totalEvents));
                // Add top throw site for this exception type
                String topSite = analysis.topThrowSiteByType.get(fullName);
                if (topSite != null) {
                  entry.put("topThrowSite", topSite);
                }
                byType.add(entry);
              });
      result.put("byType", byType);

      // Top throw sites overall
      List<Map<String, Object>> throwSites = new ArrayList<>();
      analysis.throwSites.entrySet().stream()
          .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
          .filter(e -> e.getValue() >= minCount)
          .limit(20)
          .forEach(
              e -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("site", e.getKey());
                entry.put("count", e.getValue());
                entry.put("pct", String.format("%.1f%%", e.getValue() * 100.0 / totalEvents));
                throwSites.add(entry);
              });
      result.put("topThrowSites", throwSites);

      // Summary statistics
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("uniqueExceptionTypes", analysis.exceptionTypes.size());
      summary.put("uniqueThrowSites", analysis.throwSites.size());
      if (analysis.exceptionTypes.size() > 0) {
        String topException =
            analysis.exceptionTypes.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(e -> extractSimpleName(e.getKey()))
                .orElse("unknown");
        summary.put("mostCommonException", topException);
      }
      result.put("summary", summary);

      sendProgress(exchange, progressToken, 2, 2, "Done");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      LOG.warn("Exception analysis error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to analyze exceptions: {}", e.getMessage(), e);
      return errorResult("Failed to analyze exceptions: " + e.getMessage());
    }
  }

  private String detectExceptionEventType(SessionRegistry.SessionInfo sessionInfo) {
    String[] candidateTypes = {
      "jdk.JavaExceptionThrow", "datadog.ExceptionSample", "jdk.ExceptionStatistics"
    };
    try {
      Map<String, Long> counts = evaluator.countAllEventTypes(sessionInfo.session());
      for (String type : candidateTypes) {
        if (counts.getOrDefault(type, 0L) > 0) return type;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private ExceptionInfo extractExceptionInfo(Map<String, Object> event) {
    ExceptionInfo info = new ExceptionInfo();

    // First, check for explicit exception type field (jdk.JavaExceptionThrow has thrownClass)
    Object thrownClass = event.get("thrownClass");
    if (thrownClass != null) {
      info.exceptionType = extractClassName(thrownClass);
    }

    // Extract from stack trace
    Object stackTrace = event.get("stackTrace");
    if (stackTrace instanceof Map<?, ?> stMap) {
      Object framesObj = stMap.get("frames");
      framesObj = unwrapValue(framesObj);

      Object[] frameArray = toObjectArray(framesObj);
      if (frameArray != null && frameArray.length > 0) {
        // Find exception type from <init> chain
        String lastExceptionInit = null;
        String firstNonInitFrame = null;

        for (Object frame : frameArray) {
          String methodName = extractMethodName(frame);
          if (methodName == null) continue;

          if (methodName.endsWith(".<init>")) {
            String className = methodName.substring(0, methodName.length() - 7);
            if (isExceptionClass(className)) {
              lastExceptionInit = className;
            }
          } else if (lastExceptionInit != null && firstNonInitFrame == null) {
            firstNonInitFrame = methodName;
          }
        }

        // If we found exception type from stack, use it (more specific than thrownClass)
        if (lastExceptionInit != null) {
          info.exceptionType = lastExceptionInit;
        }
        if (firstNonInitFrame != null) {
          info.throwSite = firstNonInitFrame;
        }
      }
    }

    return info;
  }

  private boolean isExceptionClass(String className) {
    return className.endsWith("Exception")
        || className.endsWith("Error")
        || className.endsWith("Throwable")
        || className.contains("/Exception")
        || className.contains("/Error");
  }

  @SuppressWarnings("unchecked")
  private String extractClassName(Object classObj) {
    classObj = unwrapValue(classObj);
    if (classObj instanceof Map<?, ?> classMap) {
      Object name = classMap.get("name");
      name = unwrapValue(name);
      if (name instanceof Map<?, ?> nameMap) {
        Object str = nameMap.get("string");
        if (str != null) return str.toString();
      } else if (name != null) {
        return name.toString();
      }
    }
    return null;
  }

  private Object[] toObjectArray(Object obj) {
    if (obj == null) return null;
    if (obj.getClass().isArray()) {
      int len = java.lang.reflect.Array.getLength(obj);
      Object[] result = new Object[len];
      for (int i = 0; i < len; i++) {
        result[i] = java.lang.reflect.Array.get(obj, i);
      }
      return result;
    } else if (obj instanceof List<?> list) {
      return list.toArray();
    }
    return null;
  }

  private String extractSimpleName(String fullName) {
    if (fullName == null) return "unknown";
    int lastSlash = fullName.lastIndexOf('/');
    return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
  }

  private static class ExceptionAnalysis {
    final LongAdder totalEvents = new LongAdder();
    final LongAdder totalExceptions = new LongAdder();
    final Map<String, Long> exceptionTypes = new ConcurrentHashMap<>();
    final Map<String, Long> throwSites = new ConcurrentHashMap<>();
    final Map<String, Map<String, Long>> throwSitesByType = new ConcurrentHashMap<>();
    final Map<String, String> topThrowSiteByType = new ConcurrentHashMap<>();
  }

  private static class ExceptionInfo {
    String exceptionType;
    String throwSite;
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

      Map<String, Object> result = new LinkedHashMap<>();

      // Recording metadata
      result.put("recordingPath", sessionInfo.recordingPath().toString());
      result.put("sessionId", sessionInfo.id());

      // Single-pass count of all event types — O(file_size) instead of O(N × file_size)
      sendProgress(exchange, progressToken, 0, 2, "Counting events...");
      Map<String, Long> rawCounts = evaluator.countAllEventTypes(sessionInfo.session());
      sendProgress(exchange, progressToken, 1, 2, "Aggregating...");

      Map<String, Long> eventCounts = new LinkedHashMap<>();
      long totalEvents = 0;
      Set<String> types = sessionInfo.session().getAvailableTypes();
      for (String type : types) {
        long count = rawCounts.getOrDefault(type, 0L);
        if (count > 0) {
          eventCounts.put(type, count);
          totalEvents += count;
        }
      }

      result.put("totalEvents", totalEvents);
      result.put("totalEventTypes", eventCounts.size());

      // Top event types
      final long finalTotalEvents = totalEvents; // Make effectively final for lambda
      List<Map<String, Object>> topTypes = new ArrayList<>();
      eventCounts.entrySet().stream()
          .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
          .limit(15)
          .forEach(
              e -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", e.getKey());
                entry.put("count", e.getValue());
                entry.put("pct", String.format("%.1f%%", e.getValue() * 100.0 / finalTotalEvents));
                topTypes.add(entry);
              });
      result.put("topEventTypes", topTypes);

      // Compute highlights
      Map<String, Object> highlights = new LinkedHashMap<>();

      // GC statistics
      try {
        highlights.put("gc", computeGcStats(sessionInfo));
      } catch (Exception e) {
        highlights.put("gc", Map.of("error", "Unable to compute GC stats"));
      }

      // Exception statistics
      Long exceptionCount =
          eventCounts.entrySet().stream()
              .filter(
                  e -> e.getKey().contains("Exception") || e.getKey().endsWith("ExceptionSample"))
              .mapToLong(Map.Entry::getValue)
              .sum();
      if (exceptionCount > 0) {
        Map<String, Object> exceptionStats = new LinkedHashMap<>();
        exceptionStats.put("totalExceptions", exceptionCount);
        highlights.put("exceptions", exceptionStats);
      }

      // CPU sampling statistics
      Long cpuSamples =
          eventCounts.entrySet().stream()
              .filter(
                  e ->
                      e.getKey().endsWith("ExecutionSample")
                          || e.getKey().equals("jdk.ExecutionSample"))
              .mapToLong(Map.Entry::getValue)
              .sum();
      if (cpuSamples > 0) {
        Map<String, Object> cpuStats = new LinkedHashMap<>();
        cpuStats.put("totalSamples", cpuSamples);

        // Try to get top CPU method
        try {
          String topMethod = getTopCpuMethod(sessionInfo);
          if (topMethod != null) {
            cpuStats.put("topMethod", topMethod);
          }
        } catch (Exception ignored) {
          // Skip if can't determine
        }

        highlights.put("cpu", cpuStats);
      }

      result.put("highlights", highlights);

      sendProgress(exchange, progressToken, 2, 2, "Done");
      return successResult(result);

    } catch (Exception e) {
      LOG.error("Failed to generate summary: {}", e.getMessage(), e);
      return errorResult("Failed to generate summary: " + e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> computeGcStats(SessionRegistry.SessionInfo sessionInfo) {
    Map<String, Object> stats = new LinkedHashMap<>();

    String[] gcTypes = {
      "jdk.GarbageCollection",
      "jdk.YoungGarbageCollection",
      "jdk.OldGarbageCollection",
      "jdk.G1GarbageCollection"
    };

    Set<String> availableTypes = sessionInfo.session().getAvailableTypes();
    List<String> presentGcTypes = new ArrayList<>();
    for (String type : gcTypes) {
      if (availableTypes.contains(type)) {
        presentGcTypes.add(type);
      }
    }
    if (presentGcTypes.isEmpty()) {
      return stats;
    }

    String typeExpr =
        presentGcTypes.size() == 1
            ? presentGcTypes.get(0)
            : "(" + String.join("|", presentGcTypes) + ")";

    try {
      JfrPath.Query parsed = queryParser.parse("events/" + typeExpr);
      List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);
      if (!events.isEmpty()) {
        long totalPauseNs = 0;
        for (Map<String, Object> event : events) {
          Object duration = event.get("duration");
          if (duration instanceof Number n) {
            totalPauseNs += n.longValue();
          }
        }
        long totalGCs = events.size();
        stats.put("totalCollections", totalGCs);
        stats.put("totalPauseMs", totalPauseNs / 1_000_000.0);
        stats.put("avgPauseMs", totalPauseNs / (totalGCs * 1_000_000.0));
        stats.put("primaryType", presentGcTypes.get(0));
      }
    } catch (Exception ignored) {
    }

    return stats;
  }

  private String getTopCpuMethod(SessionRegistry.SessionInfo sessionInfo) {
    // Find execution sample event type
    String eventType = null;
    Set<String> types = sessionInfo.session().getAvailableTypes();
    if (types.contains("datadog.ExecutionSample")) {
      eventType = "datadog.ExecutionSample";
    } else if (types.contains("jdk.ExecutionSample")) {
      eventType = "jdk.ExecutionSample";
    }

    if (eventType == null) {
      return null;
    }

    // Stream events and count leaf methods without materialising all events into a list
    try {
      JfrPath.Query parsed = queryParser.parse("events/" + eventType);
      Map<String, Long> methodCounts = new ConcurrentHashMap<>();
      LongAdder total = new LongAdder();
      evaluator.consume(
          sessionInfo.session(),
          parsed,
          event -> {
            total.increment();
            List<String> frames = extractFrames(event, "bottom-up", 1);
            if (!frames.isEmpty()) {
              methodCounts.merge(frames.get(0), 1L, Long::sum);
            }
          });

      if (methodCounts.isEmpty()) {
        return null;
      }

      final long totalSamples = total.sum();
      return methodCounts.entrySet().stream()
          .max(Comparator.comparingLong(Map.Entry::getValue))
          .map(e -> String.format("%s (%.1f%%)", e.getKey(), e.getValue() * 100.0 / totalSamples))
          .orElse(null);

    } catch (Exception e) {
      return null;
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

  public CallToolResult handleJfrHotmethods(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String eventType = (String) args.get("eventType");
    String sessionId = (String) args.get("sessionId");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 20;
    boolean includeNative = args.get("includeNative") instanceof Boolean b ? b : true;

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      // Auto-detect execution sample event type if not specified
      if (eventType == null || eventType.isBlank()) {
        eventType = detectExecutionEventType(sessionInfo);
        if (eventType == null) {
          return errorResult(
              "No execution sample events found in recording. "
                  + "Specify eventType explicitly (e.g., jdk.ExecutionSample or datadog.ExecutionSample)");
        }
      }

      // Query execution events
      sendProgress(exchange, progressToken, 0, 2, "Querying execution samples...");
      JfrPath.Query parsed = queryParser.parse("events/" + eventType);
      Map<String, Long> methodCounts = new ConcurrentHashMap<>();
      LongAdder totalSamples = new LongAdder();
      evaluator.consume(
          sessionInfo.session(),
          parsed,
          event -> {
            totalSamples.increment();
            List<String> frames = extractFrames(event, "bottom-up", 1);
            if (!frames.isEmpty()) {
              methodCounts.merge(frames.get(0), 1L, Long::sum);
            }
          });

      if (totalSamples.sum() == 0) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventType);
        result.put("totalSamples", 0);
        result.put("message", "No execution sample events found for type: " + eventType);
        return successResult(result);
      }

      // Build result
      sendProgress(exchange, progressToken, 1, 2, "Identifying hot methods...");
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("eventType", eventType);
      result.put("totalSamples", totalSamples.sum());
      result.put("uniqueMethods", methodCounts.size());

      // Top methods
      List<Map<String, Object>> methods = new ArrayList<>();
      methodCounts.entrySet().stream()
          .filter(e -> includeNative || !isNativeMethod(e.getKey()))
          .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
          .limit(limit)
          .forEach(
              e -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                String methodName = e.getKey();
                entry.put("method", methodName);
                entry.put("samples", e.getValue());
                entry.put(
                    "pct", String.format("%.1f%%", e.getValue() * 100.0 / totalSamples.sum()));
                entry.put("type", isNativeMethod(methodName) ? "native" : "java");
                methods.add(entry);
              });
      result.put("methods", methods);

      // Category breakdown
      Map<String, Long> categoryBreakdown = new LinkedHashMap<>();
      long nativeSamples = 0;
      long javaSamples = 0;
      for (Map.Entry<String, Long> entry : methodCounts.entrySet()) {
        if (isNativeMethod(entry.getKey())) {
          nativeSamples += entry.getValue();
        } else {
          javaSamples += entry.getValue();
        }
      }
      categoryBreakdown.put("native", nativeSamples);
      categoryBreakdown.put("java", javaSamples);
      result.put("categoryBreakdown", categoryBreakdown);

      sendProgress(exchange, progressToken, 2, 2, "Done");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      LOG.warn("Hotmethods error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to analyze hot methods: {}", e.getMessage(), e);
      return errorResult("Failed to analyze hot methods: " + e.getMessage());
    }
  }

  private String detectExecutionEventType(SessionRegistry.SessionInfo sessionInfo) {
    String[] candidateTypes = {
      "jdk.ExecutionSample", "datadog.ExecutionSample", "jdk.NativeMethodSample"
    };
    try {
      Map<String, Long> counts = evaluator.countAllEventTypes(sessionInfo.session());
      for (String type : candidateTypes) {
        if (counts.getOrDefault(type, 0L) > 0) return type;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private String detectQueueTimeEventType(SessionRegistry.SessionInfo sessionInfo) {
    try {
      Map<String, Long> counts = evaluator.countAllEventTypes(sessionInfo.session());
      return counts.getOrDefault("datadog.QueueTime", 0L) > 0 ? "datadog.QueueTime" : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  private String detectAllocationEventType(SessionRegistry.SessionInfo sessionInfo) {
    String[] candidateTypes = {
      "datadog.ObjectSample",
      "jdk.ObjectAllocationSample",
      "jdk.ObjectAllocationInNewTLAB",
      "jdk.ObjectAllocationOutsideTLAB"
    };
    try {
      Map<String, Long> counts = evaluator.countAllEventTypes(sessionInfo.session());
      for (String type : candidateTypes) {
        if (counts.getOrDefault(type, 0L) > 0) return type;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private boolean isNativeMethod(String methodName) {
    if (methodName == null) return false;
    // C++ mangled names typically have < > :: or start with special chars
    return methodName.contains("<")
        || methodName.contains(">::")
        || methodName.contains("::")
        || methodName.startsWith("_")
        || methodName.toLowerCase().contains("atomic");
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

  public CallToolResult handleJfrUse(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String sessionId = (String) args.get("sessionId");
    Long startTimeNs = args.get("startTime") instanceof Number n ? n.longValue() : null;
    Long endTimeNs = args.get("endTime") instanceof Number n ? n.longValue() : null;
    boolean includeInsights = args.get("includeInsights") instanceof Boolean b ? b : true;

    @SuppressWarnings("unchecked")
    List<String> resourcesList =
        args.get("resources") instanceof List<?> l ? (List<String>) l : List.of("all");
    Set<String> resources =
        resourcesList.contains("all")
            ? Set.of("cpu", "memory", "threads", "io")
            : Set.copyOf(resourcesList);

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);
      String timeFilter = buildTimeFilter(startTimeNs, endTimeNs);

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("method", "USE");
      result.put("recordingPath", sessionInfo.recordingPath().toString());
      if (startTimeNs != null || endTimeNs != null) {
        Map<String, Object> timeWindow = new LinkedHashMap<>();
        if (startTimeNs != null) timeWindow.put("startTime", startTimeNs);
        if (endTimeNs != null) timeWindow.put("endTime", endTimeNs);
        result.put("timeWindow", timeWindow);
      }

      Map<String, Object> resourceMetrics = new LinkedHashMap<>();
      int step = 0;
      int totalSteps = resources.size() + 1;

      // CPU Resource Analysis
      if (resources.contains("cpu")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Analyzing CPU...");
        resourceMetrics.put("cpu", analyzeCpuResource(sessionInfo, timeFilter));
      }

      // Memory Resource Analysis
      if (resources.contains("memory")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Analyzing memory...");
        resourceMetrics.put("memory", analyzeMemoryResource(sessionInfo, timeFilter));
      }

      // Threads/Locks Resource Analysis
      if (resources.contains("threads")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Analyzing threads...");
        resourceMetrics.put("threads", analyzeThreadsResource(sessionInfo, timeFilter));
      }

      // I/O Resource Analysis
      if (resources.contains("io")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Analyzing I/O...");
        resourceMetrics.put("io", analyzeIoResource(sessionInfo, timeFilter));
      }

      result.put("resources", resourceMetrics);

      // Generate insights and summary
      sendProgress(exchange, progressToken, step, totalSteps, "Generating insights...");
      if (includeInsights) {
        result.put("insights", generateUseInsights(resourceMetrics));
        result.put("summary", generateUseSummary(resourceMetrics));
      }

      sendProgress(exchange, progressToken, totalSteps, totalSteps, "Done");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      LOG.warn("USE analysis error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to perform USE analysis: {}", e.getMessage(), e);
      return errorResult("Failed to perform USE analysis: " + e.getMessage());
    }
  }

  private Map<String, Object> analyzeCpuResource(
      SessionRegistry.SessionInfo sessionInfo, String timeFilter) {
    Map<String, Object> cpu = new LinkedHashMap<>();

    try {
      // Query jdk.CPULoad events for actual CPU utilization
      String cpuLoadQuery = "events/jdk.CPULoad" + timeFilter;
      JfrPath.Query parsed = queryParser.parse(cpuLoadQuery);
      List<Map<String, Object>> cpuLoadEvents = evaluator.evaluate(sessionInfo.session(), parsed);

      if (!cpuLoadEvents.isEmpty()) {
        // Calculate statistics from jdk.CPULoad events
        List<Double> machineTotals = new ArrayList<>();
        List<Double> jvmUsers = new ArrayList<>();
        List<Double> jvmSystems = new ArrayList<>();

        for (Map<String, Object> event : cpuLoadEvents) {
          Object machineTotal = Values.get(event, "machineTotal");
          Object jvmUser = Values.get(event, "jvmUser");
          Object jvmSystem = Values.get(event, "jvmSystem");

          if (machineTotal instanceof Number) {
            machineTotals.add(((Number) machineTotal).doubleValue());
          }
          if (jvmUser instanceof Number) {
            jvmUsers.add(((Number) jvmUser).doubleValue());
          }
          if (jvmSystem instanceof Number) {
            jvmSystems.add(((Number) jvmSystem).doubleValue());
          }
        }

        if (!machineTotals.isEmpty()) {
          // Sort for percentile calculation
          machineTotals.sort(Double::compareTo);
          jvmUsers.sort(Double::compareTo);
          jvmSystems.sort(Double::compareTo);

          double avgMachineTotal = machineTotals.stream().mapToDouble(d -> d).average().orElse(0.0);
          double avgJvmUser = jvmUsers.stream().mapToDouble(d -> d).average().orElse(0.0);
          double avgJvmSystem = jvmSystems.stream().mapToDouble(d -> d).average().orElse(0.0);

          double minMachineTotal = machineTotals.get(0);
          double maxMachineTotal = machineTotals.get(machineTotals.size() - 1);

          int p95Idx = (int) (machineTotals.size() * 0.95);
          int p99Idx = (int) (machineTotals.size() * 0.99);
          double p95MachineTotal = machineTotals.get(Math.min(p95Idx, machineTotals.size() - 1));
          double p99MachineTotal = machineTotals.get(Math.min(p99Idx, machineTotals.size() - 1));

          // Utilization
          Map<String, Object> utilization = new LinkedHashMap<>();
          utilization.put("value", Math.round(avgMachineTotal * 1000) / 10.0); // to percentage
          utilization.put("unit", "%");
          utilization.put(
              "detail",
              String.format(
                  "Avg %.1f%%, min %.1f%%, max %.1f%%, p95 %.1f%%, p99 %.1f%%",
                  avgMachineTotal * 100,
                  minMachineTotal * 100,
                  maxMachineTotal * 100,
                  p95MachineTotal * 100,
                  p99MachineTotal * 100));

          Map<String, Object> breakdown = new LinkedHashMap<>();
          breakdown.put("machineTotal", Math.round(avgMachineTotal * 1000) / 10.0);
          breakdown.put("jvmUser", Math.round(avgJvmUser * 1000) / 10.0);
          breakdown.put("jvmSystem", Math.round(avgJvmSystem * 1000) / 10.0);
          breakdown.put(
              "otherProcesses",
              Math.round((avgMachineTotal - avgJvmUser - avgJvmSystem) * 1000) / 10.0);
          utilization.put("breakdown", breakdown);

          Map<String, Object> stats = new LinkedHashMap<>();
          stats.put("samples", machineTotals.size());
          stats.put("min", Math.round(minMachineTotal * 1000) / 10.0);
          stats.put("max", Math.round(maxMachineTotal * 1000) / 10.0);
          stats.put("avg", Math.round(avgMachineTotal * 1000) / 10.0);
          stats.put("p95", Math.round(p95MachineTotal * 1000) / 10.0);
          stats.put("p99", Math.round(p99MachineTotal * 1000) / 10.0);
          utilization.put("stats", stats);

          cpu.put("utilization", utilization);

          // Check for container CPU throttling
          Map<String, Object> saturation = new LinkedHashMap<>();
          try {
            String throttleQuery = "events/jdk.ContainerCPUThrottling" + timeFilter;
            JfrPath.Query throttleParsed = queryParser.parse(throttleQuery);
            List<Map<String, Object>> throttleEvents =
                evaluator.evaluate(sessionInfo.session(), throttleParsed);

            long totalThrottledTime = 0;
            long totalThrottledSlices = 0;
            long totalElapsedSlices = 0;

            for (Map<String, Object> event : throttleEvents) {
              Object throttledTime = Values.get(event, "cpuThrottledTime");
              Object throttledSlices = Values.get(event, "cpuThrottledSlices");
              Object elapsedSlices = Values.get(event, "cpuElapsedSlices");

              if (throttledTime instanceof Number) {
                totalThrottledTime += ((Number) throttledTime).longValue();
              }
              if (throttledSlices instanceof Number) {
                totalThrottledSlices += ((Number) throttledSlices).longValue();
              }
              if (elapsedSlices instanceof Number) {
                totalElapsedSlices += ((Number) elapsedSlices).longValue();
              }
            }

            if (!throttleEvents.isEmpty()) {
              saturation.put("throttledTimeNs", totalThrottledTime);
              saturation.put("throttledSlices", totalThrottledSlices);
              saturation.put("elapsedSlices", totalElapsedSlices);

              if (totalThrottledTime > 0) {
                saturation.put("value", totalThrottledSlices);
                saturation.put("unit", "slices");
                saturation.put(
                    "detail",
                    String.format(
                        "Container throttled %d times, %d ns total",
                        totalThrottledSlices, totalThrottledTime));
              } else {
                saturation.put("value", 0);
                saturation.put("detail", "No container CPU throttling detected");
              }
            } else {
              saturation.put("value", 0);
              saturation.put("detail", "Container throttling events not available");
            }
          } catch (Exception e) {
            saturation.put("value", "N/A");
            saturation.put("detail", "Could not check container throttling: " + e.getMessage());
          }

          cpu.put("saturation", saturation);

          // Errors
          Map<String, Object> errors = new LinkedHashMap<>();
          errors.put("value", 0);
          errors.put("detail", "No compilation failures detected");
          cpu.put("errors", errors);

          // Assessment based on actual CPU load
          cpu.put("assessment", assessCpuUtilization(avgMachineTotal * 100));
        } else {
          cpu.put("message", "No valid CPU load data found");
        }
      } else {
        // Fallback to thread state analysis if jdk.CPULoad not available
        cpu.put("warning", "jdk.CPULoad events not found, falling back to thread state analysis");

        String eventType = detectExecutionEventType(sessionInfo);
        if (eventType == null) {
          cpu.put("error", "No execution sample events found");
          return cpu;
        }

        JfrPath.Query stateParsed = queryParser.parse("events/" + eventType + timeFilter);
        AtomicLongArray counters = new AtomicLongArray(3); // [total, runnable, saturated]
        evaluator.consume(
            sessionInfo.session(),
            stateParsed,
            event -> {
              counters.incrementAndGet(0);
              String state = extractState(event);
              if ("RUNNABLE".equals(state)) {
                counters.incrementAndGet(1);
              } else if (BLOCKING_STATES.contains(state)) {
                counters.incrementAndGet(2);
              }
            });

        if (counters.get(0) == 0) {
          cpu.put("message", "No execution samples in time window");
          return cpu;
        }

        long runnableCount = counters.get(1);
        long saturatedCount = counters.get(2);
        long totalSamples = counters.get(0);
        double threadStatePct = (runnableCount * 100.0) / totalSamples;

        Map<String, Object> utilization = new LinkedHashMap<>();
        utilization.put("value", Math.round(threadStatePct * 10) / 10.0);
        utilization.put("unit", "%");
        utilization.put(
            "detail",
            String.format(
                "%.1f%% of samples in RUNNABLE state (not actual CPU load)", threadStatePct));
        utilization.put(
            "note",
            "Thread state != CPU utilization. Enable jdk.CPULoad events for accurate data.");
        cpu.put("utilization", utilization);

        Map<String, Object> saturation = new LinkedHashMap<>();
        saturation.put("value", saturatedCount);
        saturation.put("detail", saturatedCount + " samples in blocking states");
        cpu.put("saturation", saturation);

        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("value", 0);
        errors.put("detail", "No compilation failures detected");
        cpu.put("errors", errors);

        cpu.put("assessment", "UNKNOWN");
      }

    } catch (Exception e) {
      cpu.put("error", "Failed to analyze CPU: " + e.getMessage());
    }

    return cpu;
  }

  private Map<String, Object> analyzeMemoryResource(
      SessionRegistry.SessionInfo sessionInfo, String timeFilter) {
    Map<String, Object> memory = new LinkedHashMap<>();

    try {
      // Get heap usage (after GC)
      String heapQuery = "events/jdk.GCHeapSummary" + timeFilter;
      JfrPath.Query parsed = queryParser.parse(heapQuery);
      List<Map<String, Object>> heapEvents = evaluator.evaluate(sessionInfo.session(), parsed);

      Map<String, Object> utilization = new LinkedHashMap<>();
      if (!heapEvents.isEmpty()) {
        // Find most recent "After GC" event
        Map<String, Object> latestHeap = null;
        for (Map<String, Object> event : heapEvents) {
          Object when = Values.get(event, "when", "when");
          if ("After GC".equals(String.valueOf(when))) {
            latestHeap = event;
          }
        }

        if (latestHeap != null) {
          Object heapUsedObj = Values.get(latestHeap, "heapUsed");
          Object heapCommittedObj = Values.get(latestHeap, "heapSpace", "committedSize");

          if (heapUsedObj instanceof Number && heapCommittedObj instanceof Number) {
            long heapUsed = ((Number) heapUsedObj).longValue();
            long heapCommitted = ((Number) heapCommittedObj).longValue();
            double heapPct = (heapUsed * 100.0) / heapCommitted;

            utilization.put("value", Math.round(heapPct * 10) / 10.0);
            utilization.put("unit", "%");
            utilization.put("detail", String.format("Heap %.1f%% full after GC", heapPct));
            utilization.put("heapUsedMB", heapUsed / (1024 * 1024));
            utilization.put("heapCommittedMB", heapCommitted / (1024 * 1024));
          }
        }
      }

      if (utilization.isEmpty()) {
        utilization.put("value", "N/A");
        utilization.put("detail", "No GCHeapSummary events found");
      }
      memory.put("utilization", utilization);

      // Get GC pause statistics
      String gcQuery = "events/jdk.GCPhasePause" + timeFilter;
      parsed = queryParser.parse(gcQuery);
      List<Map<String, Object>> gcEvents = evaluator.evaluate(sessionInfo.session(), parsed);

      Map<String, Object> saturation = new LinkedHashMap<>();
      if (!gcEvents.isEmpty()) {
        long totalPauseNs = 0;
        long maxPauseNs = 0;
        for (Map<String, Object> event : gcEvents) {
          Object durationObj = Values.get(event, "duration");
          if (durationObj instanceof Number) {
            long durationNs = ((Number) durationObj).longValue();
            totalPauseNs += durationNs;
            maxPauseNs = Math.max(maxPauseNs, durationNs);
          }
        }

        double totalPauseMs = totalPauseNs / 1_000_000.0;
        double avgPauseMs = totalPauseMs / gcEvents.size();
        double maxPauseMs = maxPauseNs / 1_000_000.0;

        saturation.put("gcPauseTimeMs", Math.round(totalPauseMs * 10) / 10.0);
        saturation.put("gcCount", gcEvents.size());
        saturation.put("avgPauseMs", Math.round(avgPauseMs * 10) / 10.0);
        saturation.put("maxPauseMs", Math.round(maxPauseMs * 10) / 10.0);
      } else {
        saturation.put("message", "No GC pause events found");
      }
      memory.put("saturation", saturation);

      // Get top allocators
      try {
        JfrPath.Query allocParsed =
            queryParser.parse("events/jdk.ObjectAllocationSample" + timeFilter);
        Map<String, Long> allocByClass = new ConcurrentHashMap<>();
        evaluator.consume(
            sessionInfo.session(),
            allocParsed,
            event -> {
              Object classObj = Values.get(event, "objectClass", "name");
              if (classObj == null) {
                classObj = Values.get(event, "objectClass");
              }
              String className = classObj != null ? String.valueOf(classObj) : "unknown";
              Object weightObj = Values.get(event, "weight");
              long weight = weightObj instanceof Number ? ((Number) weightObj).longValue() : 1;
              allocByClass.merge(className, weight, Long::sum);
            });

        if (!allocByClass.isEmpty()) {

          List<Map<String, Object>> topAllocators = new ArrayList<>();
          allocByClass.entrySet().stream()
              .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
              .limit(10)
              .forEach(
                  e -> {
                    Map<String, Object> alloc = new LinkedHashMap<>();
                    alloc.put("class", e.getKey());
                    alloc.put("bytes", e.getValue());
                    alloc.put("mb", Math.round(e.getValue() / (1024.0 * 1024.0) * 10) / 10.0);
                    topAllocators.add(alloc);
                  });

          memory.put("topAllocators", topAllocators);
        }
      } catch (Exception ignored) {
        // Allocation events optional
      }

      // Errors
      Map<String, Object> errors = new LinkedHashMap<>();
      errors.put("value", 0);
      errors.put("detail", "No allocation failures detected");
      memory.put("errors", errors);

      // Assessment
      double heapPct = utilization.get("value") instanceof Number n ? n.doubleValue() : 0.0;
      double gcTimePct = 0.0; // Would need recording duration to calculate
      memory.put("assessment", assessMemoryPressure(heapPct, gcTimePct));

    } catch (Exception e) {
      memory.put("error", "Failed to analyze memory: " + e.getMessage());
    }

    return memory;
  }

  private Map<String, Object> analyzeThreadsResource(
      SessionRegistry.SessionInfo sessionInfo, String timeFilter) {
    Map<String, Object> threads = new LinkedHashMap<>();

    try {
      // Get unique thread count from execution samples
      String eventType = detectExecutionEventType(sessionInfo);
      if (eventType != null) {
        JfrPath.Query parsed = queryParser.parse("events/" + eventType + timeFilter);
        Set<String> uniqueThreads = ConcurrentHashMap.newKeySet();
        evaluator.consume(
            sessionInfo.session(), parsed, event -> uniqueThreads.add(extractThreadId(event)));

        Map<String, Object> utilization = new LinkedHashMap<>();
        utilization.put("value", uniqueThreads.size());
        utilization.put("unit", "threads");
        utilization.put("detail", uniqueThreads.size() + " active threads observed");
        threads.put("utilization", utilization);
      }

      // Get monitor contention
      try {
        JfrPath.Query parsed = queryParser.parse("events/jdk.JavaMonitorEnter" + timeFilter);
        AtomicLongArray monitorCounters = new AtomicLongArray(3); // [count, totalNs, maxNs]
        Map<String, Long> contentionByClass = new ConcurrentHashMap<>();
        evaluator.consume(
            sessionInfo.session(),
            parsed,
            event -> {
              monitorCounters.incrementAndGet(0);
              Object durationObj = Values.get(event, "duration");
              if (durationObj instanceof Number) {
                long durationNs = ((Number) durationObj).longValue();
                monitorCounters.addAndGet(1, durationNs);
                monitorCounters.accumulateAndGet(2, durationNs, Math::max);
              }
              Object classObj = Values.get(event, "monitorClass", "name");
              if (classObj == null) classObj = Values.get(event, "monitorClass");
              String className = classObj != null ? String.valueOf(classObj) : "unknown";
              contentionByClass.merge(className, 1L, Long::sum);
            });

        Map<String, Object> saturation = new LinkedHashMap<>();
        if (monitorCounters.get(0) > 0) {
          double totalContentionMs = monitorCounters.get(1) / 1_000_000.0;
          double avgContentionMs = totalContentionMs / monitorCounters.get(0);
          double maxContentionMs = monitorCounters.get(2) / 1_000_000.0;

          saturation.put("contentionEvents", monitorCounters.get(0));
          saturation.put("totalContentionMs", Math.round(totalContentionMs * 10) / 10.0);
          saturation.put("avgContentionMs", Math.round(avgContentionMs * 10) / 10.0);
          saturation.put("maxContentionMs", Math.round(maxContentionMs * 10) / 10.0);

          contentionByClass.entrySet().stream()
              .max(Map.Entry.comparingByValue())
              .ifPresent(e -> saturation.put("topContendedClass", e.getKey()));

          saturation.put(
              "assessment",
              monitorCounters.get(0) < 100 ? "LOW_CONTENTION" : "MODERATE_CONTENTION");
        } else {
          saturation.put("message", "No monitor contention detected");
          saturation.put("assessment", "NO_CONTENTION");
        }
        threads.put("saturation", saturation);
      } catch (Exception ignored) {
        Map<String, Object> saturation = new LinkedHashMap<>();
        saturation.put("message", "No monitor events available");
        threads.put("saturation", saturation);
      }

      // Get queue saturation
      String queueEventType = detectQueueTimeEventType(sessionInfo);
      if (queueEventType != null) {
        try {
          JfrPath.Query parsed = queryParser.parse("events/" + queueEventType + timeFilter);
          Map<String, QueueCorrelation> queueMetrics = new ConcurrentHashMap<>();
          AtomicLongArray queueTotals = new AtomicLongArray(2); // [totalNs, totalItems]
          evaluator.consume(
              sessionInfo.session(),
              parsed,
              event -> {
                Object durationObj = Values.get(event, "duration");
                if (!(durationObj instanceof Number)) return;
                long durationNs = ((Number) durationObj).longValue();
                queueTotals.addAndGet(0, durationNs);
                queueTotals.incrementAndGet(1);

                Object schedulerObj = Values.get(event, "scheduler", "name");
                if (schedulerObj == null) schedulerObj = Values.get(event, "scheduler");
                String scheduler =
                    extractSimpleClassName(
                        schedulerObj != null ? String.valueOf(schedulerObj) : "unknown");

                Object queueTypeObj = Values.get(event, "queueType", "name");
                if (queueTypeObj == null) queueTypeObj = Values.get(event, "queueType");
                String queueType =
                    extractSimpleClassName(
                        queueTypeObj != null ? String.valueOf(queueTypeObj) : "unknown");

                String threadId = extractThreadId(event);
                String key = scheduler + "|" + queueType;
                queueMetrics
                    .computeIfAbsent(key, k -> new QueueCorrelation(scheduler, queueType))
                    .addSample(durationNs, threadId);
              });

          if (!queueMetrics.isEmpty()) {
            long totalQueueTimeNs = queueTotals.get(0);
            long totalQueuedItems = queueTotals.get(1);

            // Build queue saturation output
            Map<String, Object> queueSaturation = new LinkedHashMap<>();
            queueSaturation.put(
                "totalQueueTimeMs", Math.round(totalQueueTimeNs / 1_000_000.0 * 10) / 10.0);
            queueSaturation.put("totalQueuedItems", totalQueuedItems);

            double avgQueueMs =
                totalQueuedItems > 0
                    ? (totalQueueTimeNs / (double) totalQueuedItems) / 1_000_000.0
                    : 0.0;
            queueSaturation.put("avgQueueTimeMs", Math.round(avgQueueMs * 10) / 10.0);

            // Find max queue time
            long maxQueueNs =
                queueMetrics.values().stream()
                    .mapToLong(c -> c.maxDurationNs.get())
                    .max()
                    .orElse(0);
            queueSaturation.put("maxQueueTimeMs", Math.round(maxQueueNs / 1_000_000.0 * 10) / 10.0);

            // Group by scheduler
            Map<String, Object> byScheduler = new LinkedHashMap<>();
            queueMetrics.entrySet().stream()
                .sorted(
                    (a, b) -> Long.compare(b.getValue().samples.sum(), a.getValue().samples.sum()))
                .limit(10)
                .forEach(
                    e -> {
                      QueueCorrelation corr = e.getValue();
                      Map<String, Object> schedulerInfo = new LinkedHashMap<>();
                      schedulerInfo.put("queueType", corr.queueType);
                      schedulerInfo.put("count", corr.samples.sum());
                      schedulerInfo.put(
                          "totalTimeMs",
                          Math.round(corr.totalDurationNs.sum() / 1_000_000.0 * 10) / 10.0);
                      schedulerInfo.put(
                          "avgTimeMs", Math.round(corr.getAvgDurationMs() * 10) / 10.0);
                      schedulerInfo.put(
                          "maxTimeMs",
                          Math.round(corr.maxDurationNs.get() / 1_000_000.0 * 10) / 10.0);
                      byScheduler.put(corr.scheduler, schedulerInfo);
                    });
            queueSaturation.put("byScheduler", byScheduler);

            queueSaturation.put("assessment", assessQueueSaturation(avgQueueMs));

            // Merge with existing saturation (lock contention)
            if (threads.containsKey("saturation")) {
              @SuppressWarnings("unchecked")
              Map<String, Object> existingSat = (Map<String, Object>) threads.get("saturation");

              // Restructure to have both lock and queue saturation
              Map<String, Object> lockContention = new LinkedHashMap<>();
              lockContention.put("contentionEvents", existingSat.remove("contentionEvents"));
              lockContention.put("totalContentionMs", existingSat.remove("totalContentionMs"));
              lockContention.put("avgContentionMs", existingSat.remove("avgContentionMs"));
              lockContention.put("maxContentionMs", existingSat.remove("maxContentionMs"));
              Object topContendedClass = existingSat.remove("topContendedClass");
              if (topContendedClass != null) {
                lockContention.put("topContendedClass", topContendedClass);
              }
              Object message = existingSat.remove("message");
              if (message != null) {
                lockContention.put("message", message);
              }
              lockContention.put("assessment", existingSat.remove("assessment"));

              existingSat.put("lockContention", lockContention);
              existingSat.put("queueSaturation", queueSaturation);
            } else {
              Map<String, Object> saturation = new LinkedHashMap<>();
              saturation.put("queueSaturation", queueSaturation);
              threads.put("saturation", saturation);
            }
          }
        } catch (Exception e) {
          LOG.debug("Failed to analyze queue saturation: {}", e.getMessage());
        }
      }

      // Errors
      Map<String, Object> errors = new LinkedHashMap<>();
      errors.put("value", "N/A");
      errors.put("detail", "Deadlock detection not available in JFR");
      threads.put("errors", errors);

    } catch (Exception e) {
      threads.put("error", "Failed to analyze threads: " + e.getMessage());
    }

    return threads;
  }

  private Map<String, Object> analyzeIoResource(
      SessionRegistry.SessionInfo sessionInfo, String timeFilter) {
    Map<String, Object> io = new LinkedHashMap<>();

    try {
      LongAdder ioOps = new LongAdder();
      LongAdder ioTotalNs = new LongAdder();
      AtomicLong ioMaxNs = new AtomicLong(0L);
      LongAdder ioSlowCount = new LongAdder();

      // Single-pass over all four I/O types
      JfrPath.Query ioParsed =
          queryParser.parse(
              "events/(jdk.FileRead|jdk.FileWrite|jdk.SocketRead|jdk.SocketWrite)" + timeFilter);
      evaluator.consume(
          sessionInfo.session(),
          ioParsed,
          event -> {
            ioOps.increment();
            Object durationObj = Values.get(event, "duration");
            if (durationObj instanceof Number) {
              long durationNs = ((Number) durationObj).longValue();
              ioTotalNs.add(durationNs);
              ioMaxNs.accumulateAndGet(durationNs, Math::max);
              if (durationNs > 10_000_000) {
                ioSlowCount.increment();
              }
            }
          });
      long totalOps = ioOps.longValue();

      if (totalOps > 0) {
        Map<String, Object> utilization = new LinkedHashMap<>();
        utilization.put("totalOperations", totalOps);
        utilization.put("totalTimeMs", Math.round(ioTotalNs.longValue() / 1_000_000.0 * 10) / 10.0);
        io.put("utilization", utilization);

        Map<String, Object> saturation = new LinkedHashMap<>();
        saturation.put("maxDurationMs", Math.round(ioMaxNs.longValue() / 1_000_000.0 * 10) / 10.0);
        saturation.put("slowOperations", ioSlowCount.longValue());
        saturation.put("slowThreshold", "10ms");
        io.put("saturation", saturation);

        io.put("assessment", totalOps < 1000 ? "LOW_IO" : "MODERATE_IO");
      } else {
        io.put("message", "No I/O events detected");
        io.put("assessment", "NO_IO");
      }

      // Errors
      Map<String, Object> errors = new LinkedHashMap<>();
      errors.put("value", "N/A");
      errors.put("detail", "I/O failure tracking not available in standard JFR");
      io.put("errors", errors);

    } catch (Exception e) {
      io.put("error", "Failed to analyze I/O: " + e.getMessage());
    }

    return io;
  }

  private Map<String, Object> generateUseInsights(Map<String, Object> resourceMetrics) {
    Map<String, Object> insights = new LinkedHashMap<>();
    List<String> recommendations = new ArrayList<>();
    List<String> bottlenecks = new ArrayList<>();

    // Analyze CPU
    @SuppressWarnings("unchecked")
    Map<String, Object> cpu = (Map<String, Object>) resourceMetrics.get("cpu");
    if (cpu != null && !cpu.containsKey("error")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> cpuSat = (Map<String, Object>) cpu.get("saturation");
      if (cpuSat != null && cpuSat.get("value") instanceof Number) {
        double satPct = ((Number) cpuSat.get("value")).doubleValue();
        if (satPct > 30) {
          bottlenecks.add("cpu_saturation");
          recommendations.add(
              String.format(
                  "Investigate thread blocking: %.1f%% of CPU time spent waiting/blocked", satPct));
        }
      }
    }

    // Analyze Memory
    @SuppressWarnings("unchecked")
    Map<String, Object> memory = (Map<String, Object>) resourceMetrics.get("memory");
    if (memory != null && !memory.containsKey("error")) {
      String assessment = (String) memory.get("assessment");
      if ("HIGH_PRESSURE".equals(assessment) || "MODERATE_PRESSURE".equals(assessment)) {
        bottlenecks.add("memory_pressure");
        recommendations.add("Consider heap tuning or reducing allocation rate");
      }
    }

    // Analyze Threads
    @SuppressWarnings("unchecked")
    Map<String, Object> threadsRes = (Map<String, Object>) resourceMetrics.get("threads");
    if (threadsRes != null && !threadsRes.containsKey("error")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> threadsSat = (Map<String, Object>) threadsRes.get("saturation");
      if (threadsSat != null) {
        // Check lock contention (may be nested or flat structure)
        Object contentionEvents = threadsSat.get("contentionEvents");
        if (contentionEvents == null && threadsSat.containsKey("lockContention")) {
          @SuppressWarnings("unchecked")
          Map<String, Object> lockCont = (Map<String, Object>) threadsSat.get("lockContention");
          contentionEvents = lockCont.get("contentionEvents");
        }
        if (contentionEvents instanceof Number && ((Number) contentionEvents).intValue() > 100) {
          bottlenecks.add("thread_contention");
          Object topClass = threadsSat.get("topContendedClass");
          if (topClass == null && threadsSat.containsKey("lockContention")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> lockCont = (Map<String, Object>) threadsSat.get("lockContention");
            topClass = lockCont.get("topContendedClass");
          }
          if (topClass != null) {
            recommendations.add(
                "Lock contention detected on " + topClass + " - review synchronization");
          }
        }

        // Check queue saturation
        if (threadsSat.containsKey("queueSaturation")) {
          @SuppressWarnings("unchecked")
          Map<String, Object> queueSat = (Map<String, Object>) threadsSat.get("queueSaturation");
          String queueAssessment = (String) queueSat.get("assessment");
          if ("HIGH_QUEUE_SATURATION".equals(queueAssessment)) {
            bottlenecks.add("queue_saturation");
            Object avgQueueMs = queueSat.get("avgQueueTimeMs");
            recommendations.add(
                String.format(
                    "High queue saturation detected (avg: %.1f ms) - consider increasing executor pool sizes",
                    avgQueueMs instanceof Number ? ((Number) avgQueueMs).doubleValue() : 0.0));
          } else if ("MODERATE_QUEUE_SATURATION".equals(queueAssessment)) {
            recommendations.add("Moderate queue saturation - monitor executor capacity");
          }
        }

        // Warn if Datadog profiler but no queue events
        String eventType = null;
        if (threadsRes.containsKey("utilization")) {
          // Try to detect if Datadog profiler is being used
          // This is a heuristic - we check if we have any Datadog-specific data
          if (threadsSat != null && !threadsSat.containsKey("queueSaturation")) {
            // Check if we might be using Datadog profiler
            // For now, we skip this warning as we can't reliably detect profiler type
            // without additional context
          }
        }
      }
    }

    if (recommendations.isEmpty()) {
      recommendations.add("No significant bottlenecks detected - system appears healthy");
    }

    insights.put("recommendations", recommendations);
    insights.put("bottlenecks", bottlenecks);

    return insights;
  }

  private Map<String, Object> generateUseSummary(Map<String, Object> resourceMetrics) {
    Map<String, Object> summary = new LinkedHashMap<>();

    // Find worst resource
    String worstResource = null;
    String worstMetric = null;
    double worstValue = 0;

    for (Map.Entry<String, Object> entry : resourceMetrics.entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> resource = (Map<String, Object>) entry.getValue();
      if (resource.containsKey("error")) continue;

      // Check saturation
      @SuppressWarnings("unchecked")
      Map<String, Object> saturation = (Map<String, Object>) resource.get("saturation");
      if (saturation != null && saturation.get("value") instanceof Number) {
        double value = ((Number) saturation.get("value")).doubleValue();
        if (value > worstValue) {
          worstValue = value;
          worstResource = entry.getKey();
          worstMetric = "saturation";
        }
      }
    }

    if (worstResource != null) {
      summary.put("worstResource", worstResource);
      summary.put("worstMetric", worstMetric);
      summary.put("overallAssessment", worstValue > 50 ? "NEEDS_ATTENTION" : "ACCEPTABLE");
    } else {
      summary.put("overallAssessment", "HEALTHY");
    }

    return summary;
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

  public CallToolResult handleJfrTsa(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String sessionId = (String) args.get("sessionId");
    Long startTimeNs = args.get("startTime") instanceof Number n ? n.longValue() : null;
    Long endTimeNs = args.get("endTime") instanceof Number n ? n.longValue() : null;
    int topThreads = args.get("topThreads") instanceof Number n ? n.intValue() : 10;
    int minSamples = args.get("minSamples") instanceof Number n ? n.intValue() : 5;
    boolean correlateBlocking = args.get("correlateBlocking") instanceof Boolean b ? b : true;
    boolean includeInsights = args.get("includeInsights") instanceof Boolean b ? b : true;

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);
      String timeFilter = buildTimeFilter(startTimeNs, endTimeNs);

      // Detect execution event type
      String eventType = detectExecutionEventType(sessionInfo);
      if (eventType == null) {
        return errorResult("No execution sample events found in recording");
      }

      // Get all execution samples
      sendProgress(exchange, progressToken, 0, 3, "Querying execution samples...");
      JfrPath.Query parsed = queryParser.parse("events/" + eventType + timeFilter);
      Map<String, ThreadStateMetrics> threadMetrics = new ConcurrentHashMap<>();
      Map<String, Long> globalStateCount = new ConcurrentHashMap<>();
      LongAdder totalSamplesArr = new LongAdder();

      evaluator.consume(
          sessionInfo.session(),
          parsed,
          event -> {
            totalSamplesArr.increment();
            String threadId = extractThreadId(event);
            String threadName = extractThreadName(event);
            String state = extractState(event);
            ThreadStateMetrics metrics =
                threadMetrics.computeIfAbsent(
                    threadId, k -> new ThreadStateMetrics(threadId, threadName));
            metrics.totalSamples.increment();
            metrics.stateCount.merge(state, 1L, Long::sum);
            globalStateCount.merge(state, 1L, Long::sum);
          });

      if (totalSamplesArr.sum() == 0) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", "TSA");
        result.put("message", "No execution samples in time window");
        return successResult(result);
      }

      // Filter by minSamples
      threadMetrics.values().removeIf(m -> m.totalSamples.sum() < minSamples);

      long totalSamples = totalSamplesArr.sum();

      // Correlate with blocking events if requested
      sendProgress(exchange, progressToken, 1, 3, "Analyzing thread states...");
      Map<String, MonitorCorrelation> correlations = new HashMap<>();
      Map<String, QueueCorrelation> queueCorrelations = new HashMap<>();
      if (correlateBlocking) {
        sendProgress(exchange, progressToken, 2, 3, "Correlating blocking events...");
        correlations = correlateWithBlockingEvents(sessionInfo, timeFilter);
        queueCorrelations = correlateWithQueueEvents(sessionInfo, timeFilter);
      }

      // Build result
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("method", "TSA");
      result.put("recordingPath", sessionInfo.recordingPath().toString());
      if (startTimeNs != null || endTimeNs != null) {
        Map<String, Object> timeWindow = new LinkedHashMap<>();
        if (startTimeNs != null) timeWindow.put("startTime", startTimeNs);
        if (endTimeNs != null) timeWindow.put("endTime", endTimeNs);
        result.put("timeWindow", timeWindow);
      }
      result.put("totalSamples", totalSamples);
      result.put("totalThreads", threadMetrics.size());

      // Global state distribution
      Map<String, Object> stateDistribution = new LinkedHashMap<>();
      for (Map.Entry<String, Long> entry : globalStateCount.entrySet()) {
        Map<String, Object> stateInfo = new LinkedHashMap<>();
        stateInfo.put("samples", entry.getValue());
        stateInfo.put("percentage", Math.round(entry.getValue() * 1000.0 / totalSamples) / 10.0);
        stateDistribution.put(entry.getKey(), stateInfo);
      }
      result.put("stateDistribution", stateDistribution);

      // Top threads by state
      Map<String, Object> topThreadsByState =
          buildTopThreadsByState(threadMetrics, globalStateCount, topThreads);
      result.put("topThreadsByState", topThreadsByState);

      // Thread profiles
      List<Map<String, Object>> threadProfiles =
          buildThreadProfiles(threadMetrics, totalSamples, correlations, queueCorrelations);
      result.put("threadProfiles", threadProfiles);

      // Correlations
      if (!correlations.isEmpty() || !queueCorrelations.isEmpty()) {
        Map<String, Object> allCorrelations = new LinkedHashMap<>();
        if (!correlations.isEmpty()) {
          allCorrelations.putAll(buildCorrelationsOutput(correlations));
        }
        if (!queueCorrelations.isEmpty()) {
          allCorrelations.putAll(buildQueueCorrelationsOutput(queueCorrelations));
        }
        result.put("correlations", allCorrelations);
      }

      // Insights
      if (includeInsights) {
        result.put(
            "insights",
            generateTsaInsights(
                threadMetrics, globalStateCount, totalSamples, correlations, queueCorrelations));
      }

      sendProgress(exchange, progressToken, 3, 3, "Done");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      LOG.warn("TSA analysis error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to perform TSA analysis: {}", e.getMessage(), e);
      return errorResult("Failed to perform TSA analysis: " + e.getMessage());
    }
  }

  private Map<String, MonitorCorrelation> correlateWithBlockingEvents(
      SessionRegistry.SessionInfo sessionInfo, String timeFilter) {
    Map<String, MonitorCorrelation> correlations = new ConcurrentHashMap<>();

    try {
      JfrPath.Query parsed = queryParser.parse("events/jdk.JavaMonitorEnter" + timeFilter);
      evaluator.consume(
          sessionInfo.session(),
          parsed,
          event -> {
            Object classObj = Values.get(event, "monitorClass", "name");
            if (classObj == null) {
              classObj = Values.get(event, "monitorClass");
            }
            String monitorClass = classObj != null ? String.valueOf(classObj) : "unknown";
            MonitorCorrelation corr =
                correlations.computeIfAbsent(monitorClass, MonitorCorrelation::new);
            corr.samples.increment();
            Object durationObj = Values.get(event, "duration");
            if (durationObj instanceof Number) {
              corr.totalDurationNs.add(((Number) durationObj).longValue());
            }
            corr.threads.add(extractThreadId(event));
          });
    } catch (Exception e) {
      LOG.debug("Failed to correlate blocking events: {}", e.getMessage());
    }

    return correlations;
  }

  private Map<String, QueueCorrelation> correlateWithQueueEvents(
      SessionRegistry.SessionInfo sessionInfo, String timeFilter) {
    Map<String, QueueCorrelation> correlations = new ConcurrentHashMap<>();

    try {
      String queueEventType = detectQueueTimeEventType(sessionInfo);
      if (queueEventType == null) return correlations;

      JfrPath.Query parsed = queryParser.parse("events/" + queueEventType + timeFilter);
      evaluator.consume(
          sessionInfo.session(),
          parsed,
          event -> {
            Object schedulerObj = Values.get(event, "scheduler", "name");
            if (schedulerObj == null) schedulerObj = Values.get(event, "scheduler");
            String scheduler =
                extractSimpleClassName(
                    schedulerObj != null ? String.valueOf(schedulerObj) : "unknown");

            Object queueTypeObj = Values.get(event, "queueType", "name");
            if (queueTypeObj == null) queueTypeObj = Values.get(event, "queueType");
            String queueType =
                extractSimpleClassName(
                    queueTypeObj != null ? String.valueOf(queueTypeObj) : "unknown");

            String threadId = extractThreadId(event);
            QueueCorrelation corr =
                correlations.computeIfAbsent(
                    scheduler, k -> new QueueCorrelation(scheduler, queueType));

            Object durationObj = Values.get(event, "duration");
            if (durationObj instanceof Number) {
              corr.addSample(((Number) durationObj).longValue(), threadId);
            } else {
              corr.samples.increment();
              corr.threads.add(threadId);
            }
          });

    } catch (Exception e) {
      LOG.debug("Failed to correlate queue events: {}", e.getMessage());
    }

    return correlations;
  }

  private Map<String, Object> buildTopThreadsByState(
      Map<String, ThreadStateMetrics> threadMetrics, Map<String, Long> globalStateCount, int topN) {
    Map<String, Object> topThreadsByState = new LinkedHashMap<>();

    for (String state : globalStateCount.keySet()) {
      List<Map<String, Object>> topThreads =
          threadMetrics.values().stream()
              .filter(m -> m.stateCount.containsKey(state))
              .sorted(
                  (a, b) ->
                      Long.compare(
                          b.stateCount.getOrDefault(state, 0L),
                          a.stateCount.getOrDefault(state, 0L)))
              .limit(topN)
              .map(
                  m -> {
                    Map<String, Object> thread = new LinkedHashMap<>();
                    thread.put("threadId", m.threadId);
                    thread.put("threadName", m.threadName);
                    long stateSamples = m.stateCount.get(state);
                    thread.put("samples", stateSamples);
                    thread.put(
                        "percentage",
                        Math.round(stateSamples * 1000.0 / globalStateCount.get(state)) / 10.0);
                    thread.put(
                        "percentOfTotal",
                        Math.round(stateSamples * 1000.0 / m.totalSamples.sum()) / 10.0);
                    return thread;
                  })
              .toList();

      if (!topThreads.isEmpty()) {
        topThreadsByState.put(state, topThreads);
      }
    }

    return topThreadsByState;
  }

  private List<Map<String, Object>> buildThreadProfiles(
      Map<String, ThreadStateMetrics> threadMetrics,
      long totalSamples,
      Map<String, MonitorCorrelation> correlations,
      Map<String, QueueCorrelation> queueCorrelations) {
    return threadMetrics.values().stream()
        .sorted((a, b) -> Long.compare(b.totalSamples.sum(), a.totalSamples.sum()))
        .limit(20) // Top 20 threads by sample count
        .map(
            m -> {
              Map<String, Object> profile = new LinkedHashMap<>();
              profile.put("threadId", m.threadId);
              profile.put("threadName", m.threadName);
              profile.put("totalSamples", m.totalSamples.sum());
              profile.put(
                  "percentOfRecording",
                  Math.round(m.totalSamples.sum() * 1000.0 / totalSamples) / 10.0);

              // State breakdown
              Map<String, Object> stateBreakdown = new LinkedHashMap<>();
              for (Map.Entry<String, Long> entry : m.stateCount.entrySet()) {
                Map<String, Object> stateInfo = new LinkedHashMap<>();
                stateInfo.put("samples", entry.getValue());
                stateInfo.put(
                    "pct", Math.round(entry.getValue() * 1000.0 / m.totalSamples.sum()) / 10.0);
                stateBreakdown.put(entry.getKey(), stateInfo);
              }
              profile.put("stateBreakdown", stateBreakdown);

              // Assessment
              profile.put("assessment", assessThreadBehavior(m.stateCount, m.totalSamples.sum()));

              // Add queue correlation info if available
              if (queueCorrelations != null && !queueCorrelations.isEmpty()) {
                List<String> queuedOnExecutors =
                    queueCorrelations.entrySet().stream()
                        .filter(e -> e.getValue().threads.contains(m.threadId))
                        .map(Map.Entry::getKey)
                        .toList();
                if (!queuedOnExecutors.isEmpty()) {
                  profile.put("queuedOn", queuedOnExecutors);
                }
              }

              return profile;
            })
        .toList();
  }

  private Map<String, Object> buildCorrelationsOutput(
      Map<String, MonitorCorrelation> correlations) {
    Map<String, Object> output = new LinkedHashMap<>();

    Map<String, Object> blockedOn = new LinkedHashMap<>();
    correlations.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue().samples.sum(), a.getValue().samples.sum()))
        .limit(10)
        .forEach(
            e -> {
              MonitorCorrelation corr = e.getValue();
              Map<String, Object> info = new LinkedHashMap<>();
              info.put("samples", corr.samples.sum());
              info.put("threads", corr.threads.size());
              if (corr.totalDurationNs.sum() > 0) {
                double avgMs =
                    (corr.totalDurationNs.sum() / (double) corr.samples.sum()) / 1_000_000.0;
                info.put("avgBlockTimeMs", Math.round(avgMs * 10) / 10.0);
              }
              info.put("monitorClass", e.getKey());
              blockedOn.put(e.getKey(), info);
            });

    if (!blockedOn.isEmpty()) {
      output.put("blockedOn", blockedOn);
    }

    return output;
  }

  private Map<String, Object> buildQueueCorrelationsOutput(
      Map<String, QueueCorrelation> queueCorrelations) {
    Map<String, Object> output = new LinkedHashMap<>();

    Map<String, Object> queuedOn = new LinkedHashMap<>();
    queueCorrelations.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue().samples.sum(), a.getValue().samples.sum()))
        .limit(10)
        .forEach(
            e -> {
              QueueCorrelation corr = e.getValue();
              Map<String, Object> info = new LinkedHashMap<>();
              info.put("queueType", corr.queueType);
              info.put("samples", corr.samples.sum());
              info.put("threads", corr.threads.size());
              if (corr.totalDurationNs.sum() > 0 && corr.samples.sum() > 0) {
                info.put("avgQueueTimeMs", Math.round(corr.getAvgDurationMs() * 10) / 10.0);
                info.put(
                    "maxQueueTimeMs",
                    Math.round(corr.maxDurationNs.get() / 1_000_000.0 * 10) / 10.0);
              }
              queuedOn.put(e.getKey(), info);
            });

    if (!queuedOn.isEmpty()) {
      output.put("queuedOn", queuedOn);
    }

    return output;
  }

  private Map<String, Object> generateTsaInsights(
      Map<String, ThreadStateMetrics> threadMetrics,
      Map<String, Long> globalStateCount,
      long totalSamples,
      Map<String, MonitorCorrelation> correlations,
      Map<String, QueueCorrelation> queueCorrelations) {
    Map<String, Object> insights = new LinkedHashMap<>();
    List<String> patterns = new ArrayList<>();
    List<Map<String, Object>> problematicThreads = new ArrayList<>();
    List<String> recommendations = new ArrayList<>();

    // Analyze global state distribution
    for (Map.Entry<String, Long> entry : globalStateCount.entrySet()) {
      double pct = (entry.getValue() * 100.0) / totalSamples;
      String state = entry.getKey();

      if ("RUNNABLE".equals(state)) {
        if (pct > 70) {
          patterns.add(String.format("High CPU utilization (%.1f%% RUNNABLE)", pct));
        } else if (pct < 30) {
          patterns.add(
              String.format("Low CPU utilization (%.1f%% RUNNABLE) - threads mostly waiting", pct));
        } else {
          patterns.add(String.format("Healthy CPU utilization (%.1f%% RUNNABLE)", pct));
        }
      } else if ("WAITING".equals(state) || "TIMED_WAITING".equals(state)) {
        if (pct > 30) {
          patterns.add(
              String.format(
                  "Significant time in %s (%.1f%%) - likely I/O or queue waits", state, pct));
        }
      } else if ("BLOCKED".equals(state)) {
        if (pct > 10) {
          patterns.add(String.format("High lock contention (%.1f%% BLOCKED)", pct));
          recommendations.add(
              "Investigate lock contention - threads spending significant time blocked on monitors");
        }
      }
    }

    // Find problematic threads
    for (ThreadStateMetrics m : threadMetrics.values()) {
      String assessment = assessThreadBehavior(m.stateCount, m.totalSamples.sum());
      if ("LOCK_CONTENTION".equals(assessment)) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("thread", m.threadName);
        long blockedSamples = m.stateCount.getOrDefault("BLOCKED", 0L);
        double blockedPct = (blockedSamples * 100.0) / m.totalSamples.sum();
        problem.put("issue", String.format("%.1f%% of time spent BLOCKED on locks", blockedPct));
        problem.put("recommendation", "Review synchronization strategy for this thread");
        problematicThreads.add(problem);
      }
    }

    // Analyze correlations
    if (!correlations.isEmpty()) {
      MonitorCorrelation topContention =
          correlations.values().stream()
              .max(Comparator.comparingLong(c -> c.samples.sum()))
              .orElse(null);
      if (topContention != null && topContention.samples.sum() > 50) {
        recommendations.add(
            String.format(
                "Monitor class '%s' has high contention (%d events) - consider lock-free alternatives",
                topContention.monitorClass, topContention.samples.sum()));
      }
    }

    // Analyze queue correlations
    if (queueCorrelations != null && !queueCorrelations.isEmpty()) {
      QueueCorrelation maxQueue =
          queueCorrelations.values().stream()
              .max(Comparator.comparingDouble(QueueCorrelation::getAvgDurationMs))
              .orElse(null);

      if (maxQueue != null && maxQueue.getAvgDurationMs() > 50) {
        patterns.add(
            String.format(
                "High executor queue times on %s (avg: %.1f ms)",
                maxQueue.scheduler, maxQueue.getAvgDurationMs()));
        recommendations.add(
            String.format(
                "Consider increasing thread pool size for %s or optimizing task submission rate",
                maxQueue.scheduler));
      }
    }

    if (patterns.isEmpty()) {
      patterns.add("No significant patterns detected");
    }
    if (recommendations.isEmpty()) {
      recommendations.add("Thread state distribution appears healthy");
    }

    insights.put("patterns", patterns);
    if (!problematicThreads.isEmpty()) {
      insights.put("problematicThreads", problematicThreads);
    }
    insights.put("recommendations", recommendations);

    return insights;
  }

  /** Helper class to track per-thread state metrics. */
  private static class ThreadStateMetrics {
    final String threadId;
    final String threadName;
    final LongAdder totalSamples = new LongAdder();
    final Map<String, Long> stateCount = new ConcurrentHashMap<>();

    ThreadStateMetrics(String threadId, String threadName) {
      this.threadId = threadId;
      this.threadName = threadName;
    }
  }

  /** Helper class to track monitor correlation data. */
  private static class MonitorCorrelation {
    final String monitorClass;
    final LongAdder samples = new LongAdder();
    final LongAdder totalDurationNs = new LongAdder();
    final Set<String> threads = ConcurrentHashMap.newKeySet();

    MonitorCorrelation(String monitorClass) {
      this.monitorClass = monitorClass;
    }
  }

  /** Helper class to track queue correlation data. */
  private static class QueueCorrelation {
    final String scheduler;
    final String queueType;
    final LongAdder samples = new LongAdder();
    final LongAdder totalDurationNs = new LongAdder();
    final AtomicLong maxDurationNs = new AtomicLong(0L);
    final Set<String> threads = ConcurrentHashMap.newKeySet();

    QueueCorrelation(String scheduler, String queueType) {
      this.scheduler = scheduler;
      this.queueType = queueType;
    }

    void addSample(long durationNs, String threadId) {
      samples.increment();
      totalDurationNs.add(durationNs);
      maxDurationNs.accumulateAndGet(durationNs, Math::max);
      threads.add(threadId);
    }

    double getAvgDurationMs() {
      long s = samples.sum();
      return s > 0 ? (totalDurationNs.sum() / (double) s) / 1_000_000.0 : 0.0;
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Shared helper methods for USE and TSA analysis
  // ─────────────────────────────────────────────────────────────────────────────

  /** Extract thread state from ExecutionSample event (handles both jdk and datadog formats). */
  private String extractState(Map<String, Object> event) {
    Object state = Values.get(event, "state", "name");
    if (state == null) {
      state = Values.get(event, "state");
    }
    return state != null ? String.valueOf(unwrapValue(state)) : "UNKNOWN";
  }

  /** Extract thread ID from event. */
  private String extractThreadId(Map<String, Object> event) {
    Object tid = Values.get(event, "eventThread", "javaThreadId");
    return tid != null ? String.valueOf(tid) : "unknown";
  }

  /** Extract thread name from event. */
  private String extractThreadName(Map<String, Object> event) {
    Object name = Values.get(event, "eventThread", "javaName");
    if (name == null) {
      name = Values.get(event, "eventThread", "osName");
    }
    return name != null ? String.valueOf(name) : "unknown";
  }

  /** Extract simple class name from fully qualified name. */
  private String extractSimpleClassName(String fullClassName) {
    if (fullClassName == null || fullClassName.isEmpty()) return "unknown";
    int lastDot = fullClassName.lastIndexOf('.');
    int lastDollar = fullClassName.lastIndexOf('$');
    int splitIdx = Math.max(lastDot, lastDollar);
    return splitIdx >= 0 ? fullClassName.substring(splitIdx + 1) : fullClassName;
  }

  /** Build JfrPath time filter for time-window queries. */
  private String buildTimeFilter(Long startNs, Long endNs) {
    if (startNs == null && endNs == null) {
      return "";
    }
    List<String> conditions = new ArrayList<>();
    if (startNs != null) {
      conditions.add("startTime>=" + startNs);
    }
    if (endNs != null) {
      conditions.add("startTime<=" + endNs);
    }
    return "[" + String.join(" and ", conditions) + "]";
  }

  /** Assess CPU utilization level. */
  private String assessCpuUtilization(double pct) {
    if (pct < 30) return "LOW";
    if (pct < 70) return "MODERATE_UTILIZATION";
    if (pct < 90) return "HIGH_UTILIZATION";
    return "SATURATED";
  }

  /** Assess memory pressure based on heap usage and GC time. */
  private String assessMemoryPressure(double heapPct, double gcTimePct) {
    if (heapPct > 90 || gcTimePct > 10) return "HIGH_PRESSURE";
    if (heapPct > 75 || gcTimePct > 5) return "MODERATE_PRESSURE";
    return "HEALTHY";
  }

  /** Assess thread behavior based on state distribution. */
  private String assessThreadBehavior(Map<String, Long> states, long total) {
    if (total == 0) return "NO_SAMPLES";
    double runnablePct = states.getOrDefault("RUNNABLE", 0L) * 100.0 / total;
    double waitingPct =
        (states.getOrDefault("WAITING", 0L) + states.getOrDefault("TIMED_WAITING", 0L))
            * 100.0
            / total;
    double blockedPct = states.getOrDefault("BLOCKED", 0L) * 100.0 / total;

    if (runnablePct > 80) return "CPU_INTENSIVE";
    if (waitingPct > 70) return "IO_WAITING";
    if (blockedPct > 20) return "LOCK_CONTENTION";
    return "BALANCED";
  }

  /** Assess queue saturation level based on average queue time. */
  private String assessQueueSaturation(double avgQueueMs) {
    if (avgQueueMs > 100) return "HIGH_QUEUE_SATURATION";
    if (avgQueueMs > 20) return "MODERATE_QUEUE_SATURATION";
    return "LOW_QUEUE_SATURATION";
  }

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
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_diagnose",
            "Intelligently diagnoses performance issues in a JFR recording by automatically "
                + "running appropriate analysis tools based on recording characteristics. "
                + "Analyzes exception rates, GC pressure, CPU patterns, and suggests next steps. "
                + "Use this as a first step when exploring an unfamiliar recording.",
            schema),
        (exchange, args) -> handleJfrDiagnose(exchange, args.arguments(), progressToken(args)));
  }

  @SuppressWarnings("unchecked")
  public CallToolResult handleJfrDiagnose(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String sessionId = (String) args.get("sessionId");
    Boolean includeAnalysis = args.get("includeAnalysis") instanceof Boolean b ? b : true;

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      Map<String, Object> diagnosis = new LinkedHashMap<>();
      diagnosis.put("recordingPath", sessionInfo.recordingPath().toString());
      diagnosis.put("sessionId", sessionInfo.id());

      // Step 1: Get summary data
      sendProgress(exchange, progressToken, 0, 4, "Running summary...");
      CallToolResult summaryResult = handleJfrSummary(null, args, null);
      if (summaryResult.isError()) {
        return summaryResult;
      }

      // Parse summary JSON
      String summaryJson = ((TextContent) summaryResult.content().get(0)).text();
      Map<String, Object> summary = MAPPER.readValue(summaryJson, Map.class);

      // Extract key metrics
      Long totalEvents = ((Number) summary.get("totalEvents")).longValue();
      Map<String, Object> highlights = (Map<String, Object>) summary.get("highlights");

      List<String> findings = new ArrayList<>();
      List<String> recommendations = new ArrayList<>();
      Map<String, Object> analyses = new LinkedHashMap<>();

      // Step 2: Analyze exception patterns
      sendProgress(exchange, progressToken, 1, 4, "Analyzing exceptions...");
      if (highlights.containsKey("exceptions")) {
        Map<String, Object> exceptionStats = (Map<String, Object>) highlights.get("exceptions");
        Long exceptionCount = ((Number) exceptionStats.get("totalExceptions")).longValue();

        if (exceptionCount > 1000) {
          findings.add(
              String.format("HIGH EXCEPTION RATE: %,d exceptions detected", exceptionCount));

          // Run exception analysis
          CallToolResult exceptionsResult = handleJfrExceptions(null, args, null);
          if (!exceptionsResult.isError() && includeAnalysis) {
            String exceptionsJson = ((TextContent) exceptionsResult.content().get(0)).text();
            analyses.put("exceptions", MAPPER.readValue(exceptionsJson, Map.class));
          }

          recommendations.add(
              "Investigate exception types - high exception rates often indicate misconfiguration "
                  + "or error handling issues");
        } else if (exceptionCount > 100) {
          findings.add(
              String.format("MODERATE EXCEPTION RATE: %,d exceptions detected", exceptionCount));
        }
      }

      // Step 3: Analyze GC pressure
      sendProgress(exchange, progressToken, 2, 4, "Analyzing GC pressure...");
      if (highlights.containsKey("gc")) {
        Map<String, Object> gcStats = (Map<String, Object>) highlights.get("gc");
        if (gcStats.containsKey("totalCollections")) {
          Long gcCount = ((Number) gcStats.get("totalCollections")).longValue();
          Double avgPauseMs = ((Number) gcStats.get("avgPauseMs")).doubleValue();
          Double totalPauseMs = ((Number) gcStats.get("totalPauseMs")).doubleValue();

          if (avgPauseMs > 100 || totalPauseMs > 10000) {
            findings.add(
                String.format(
                    "HIGH GC PRESSURE: %,d collections, %.1fms avg pause, %.1fs total pause",
                    gcCount, avgPauseMs, totalPauseMs / 1000.0));

            recommendations.add(
                "GC pressure indicates memory saturation - consider running jfr_use to analyze "
                    + "memory resource utilization");

            // Detect and recommend appropriate allocation event type
            String allocEventType = detectAllocationEventType(sessionInfo);
            if (allocEventType != null) {
              recommendations.add(
                  String.format(
                      "Run jfr_flamegraph with %s to identify allocation hotspots",
                      allocEventType));
            } else {
              recommendations.add(
                  "Allocation profiling not enabled in this recording - consider enabling "
                      + "for future recordings to identify allocation hotspots");
            }
          } else if (avgPauseMs > 50 || totalPauseMs > 5000) {
            findings.add(
                String.format(
                    "MODERATE GC PRESSURE: %,d collections, %.1fms avg pause",
                    gcCount, avgPauseMs));
          }
        }
      }

      // Step 4: Analyze CPU patterns
      sendProgress(exchange, progressToken, 3, 4, "Analyzing CPU patterns...");
      if (highlights.containsKey("cpu")) {
        Map<String, Object> cpuStats = (Map<String, Object>) highlights.get("cpu");
        Long cpuSamples = ((Number) cpuStats.get("totalSamples")).longValue();

        if (cpuSamples > 5000) {
          findings.add(String.format("CPU INTENSIVE: %,d execution samples captured", cpuSamples));

          // Run hotmethods analysis
          CallToolResult hotmethodsResult = handleJfrHotmethods(null, args, null);
          if (!hotmethodsResult.isError() && includeAnalysis) {
            String hotmethodsJson = ((TextContent) hotmethodsResult.content().get(0)).text();
            analyses.put("hotmethods", MAPPER.readValue(hotmethodsJson, Map.class));
          }

          recommendations.add(
              "Run jfr_flamegraph with execution samples to understand full call stacks");
          recommendations.add(
              "Consider running jfr_tsa (Thread State Analysis) to understand thread behavior");
        }
      }

      // Step 5: Check allocation profiling availability
      String allocEventType = detectAllocationEventType(sessionInfo);
      if (allocEventType != null) {
        findings.add(
            String.format(
                "ALLOCATION PROFILING: %s events available for analysis", allocEventType));
      } else {
        findings.add("ALLOCATION PROFILING: Not enabled in this recording");
        recommendations.add(
            "Consider enabling allocation profiling (JDK: -XX:StartFlightRecording:settings=profile, "
                + "Datadog: included by default) for memory analysis");
      }

      // Step 6: Check for blocking patterns (always recommend USE/TSA for comprehensive view)
      if (totalEvents > 10000) {
        recommendations.add(
            "Run jfr_use (USE Method) for comprehensive resource bottleneck analysis "
                + "(CPU, Memory, Threads, I/O)");
      }

      // Step 7: Build response
      diagnosis.put(
          "findings", findings.isEmpty() ? List.of("No significant issues detected") : findings);
      diagnosis.put("recommendations", recommendations);

      if (includeAnalysis && !analyses.isEmpty()) {
        diagnosis.put("detailedAnalysis", analyses);
      }

      // Add summary for context
      diagnosis.put(
          "summary",
          Map.of(
              "totalEvents", totalEvents,
              "eventTypes", summary.get("totalEventTypes"),
              "highlights", highlights));

      sendProgress(exchange, progressToken, 4, 4, "Done");
      return successResult(diagnosis);

    } catch (Exception e) {
      LOG.error("Failed to diagnose recording: {}", e.getMessage(), e);
      return errorResult("Failed to diagnose recording: " + e.getMessage());
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
        eventType = detectExecutionEventType(sessionInfo);
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

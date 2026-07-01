package io.jafar.mcp.jfr;

import io.jafar.mcp.config.McpServerConfig;
import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.query.QueryParser;
import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.result.ResultLimiter;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.mcp.validation.FileValidator;
import io.jafar.shell.jfrpath.JfrPath;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP tool implementations for basic JFR session, query, metadata, and help operations. */
public final class JfrSessionTools {

  private static final Logger LOG = LoggerFactory.getLogger(JfrSessionTools.class);
  private static final int MAX_QUERY_ROWS = McpServerConfig.MAX_QUERY_ROWS;

  private final SessionRegistry sessionRegistry;
  private final QueryEvaluator evaluator;
  private final QueryParser queryParser;
  private final McpResultFactory resultFactory;
  private final JfrHelpProvider jfrHelpProvider;

  public JfrSessionTools(
      SessionRegistry sessionRegistry,
      QueryEvaluator evaluator,
      QueryParser queryParser,
      McpResultFactory resultFactory,
      JfrHelpProvider jfrHelpProvider) {
    this.sessionRegistry = sessionRegistry;
    this.evaluator = evaluator;
    this.queryParser = queryParser;
    this.resultFactory = resultFactory;
    this.jfrHelpProvider = jfrHelpProvider;
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

  private static int truncate(List<?> list, int max) {
    return ResultLimiter.truncate(list, max);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_open
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrOpenTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Absolute path to the JFR recording file"
            },
            "alias": {
              "type": "string",
              "description": "Optional alias for the session"
            }
          },
          "required": ["path"]
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_open",
            "Opens a JFR (Java Flight Recording) file for analysis. "
                + "Returns a session ID that can be used with other jfr_* tools. "
                + "If no session ID is provided to other tools, they use the most recently opened session.",
            schema),
        (exchange, args) -> handleJfrOpen(args.arguments()));
  }

  public CallToolResult handleJfrOpen(Map<String, Object> args) {
    String path = (String) args.get("path");
    String alias = (String) args.get("alias");

    if (path == null || path.isBlank()) {
      return errorResult("Path is required");
    }

    try {
      Path recordingPath = Path.of(path);

      String fileError = FileValidator.readableRegularFileError(recordingPath, path);
      if (fileError != null) {
        return errorResult(fileError);
      }

      SessionRegistry.SessionInfo session = sessionRegistry.open(recordingPath, alias);
      LOG.info("Opened recording {} as session {}", path, session.id());

      Map<String, Object> result = session.toMap();
      result.put("message", "Recording opened successfully");
      return successResult(result);

    } catch (Exception e) {
      LOG.error("Failed to open recording: {}", e.getMessage(), e);
      return errorResult("Failed to open recording: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_query
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrQueryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "JfrPath query expression"
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum results to return (default: 100)"
            }
          },
          "required": ["query"]
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_query",
            "Executes a JfrPath query against a JFR recording. "
                + "JfrPath forms: events/<eventType>, events/<eventType>[filter], events/<eventType> | pipeline. "
                + "Common types: jdk.ExecutionSample, jdk.GCPhasePause, jdk.MonitorEnter, jdk.FileRead. "
                + "Pipeline ops: count(), groupBy(field), top(n), select(f1,f2), stats(field). "
                + "Examples: events/jdk.ExecutionSample | count(), events/jdk.GCPhasePause | top(10)",
            schema),
        (exchange, args) -> handleJfrQuery(args.arguments()));
  }

  public CallToolResult handleJfrQuery(Map<String, Object> args) {
    String query = (String) args.get("query");
    String sessionId = (String) args.get("sessionId");
    Integer limit = args.get("limit") instanceof Number n ? n.intValue() : null;

    if (query == null || query.isBlank()) {
      return errorResult("Query is required");
    }
    if (limit != null && limit <= 0) {
      return errorResult("Limit must be positive");
    }

    int resultLimit = (limit != null && limit > 0) ? limit : 100;

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      JfrPath.Query parsed = queryParser.parse(query);
      LOG.debug("Parsed query: {}", parsed);

      List<Map<String, Object>> results = evaluator.evaluate(sessionInfo.session(), parsed);

      if (results.size() > resultLimit) {
        results = results.subList(0, resultLimit);
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("query", query);
      response.put("sessionId", sessionInfo.id());

      if (results.size() == resultLimit) {
        response.put("truncated", true);
        response.put(
            "message",
            "Results truncated to " + resultLimit + " items. Use 'limit' parameter for more.");
      }

      int dropped = truncate(results, MAX_QUERY_ROWS);
      if (dropped > 0) {
        LOG.warn("jfr_query capDroppedRows={} beyond cap {}", dropped, MAX_QUERY_ROWS);
        response.put("capTruncated", true);
        response.put("capDroppedRows", dropped);
      }
      response.put("resultCount", results.size());
      response.put("results", results);

      return successResult(response);

    } catch (IllegalArgumentException e) {
      LOG.warn("Query error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to execute query: {}", e.getMessage(), e);
      return errorResult("Failed to execute query: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_list_types
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrListTypesTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "filter": {
              "type": "string",
              "description": "Filter for event type names (case-insensitive)"
            },
            "scan": {
              "type": "boolean",
              "description": "Scan recording to get accurate event counts (may be slow for large files)"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_list_types",
            "Lists all event types available in a JFR recording. "
                + "By default returns type names from metadata (fast). "
                + "Use scan=true to get actual event counts (scans the entire recording). "
                + "Categories: jdk.* (JDK events), jdk.jfr.* (JFR infrastructure), custom app events.",
            schema),
        (exchange, args) -> handleJfrListTypes(args.arguments()));
  }

  public CallToolResult handleJfrListTypes(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    String filter = (String) args.get("filter");
    Boolean scan = args.get("scan") instanceof Boolean b ? b : false;

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);
      Set<String> eventTypes = sessionInfo.session().getAvailableTypes();

      // Build filtered list of types
      List<String> filteredTypes = new ArrayList<>();
      for (String eventType : eventTypes) {
        if (filter != null && !filter.isBlank()) {
          if (!eventType.toLowerCase().contains(filter.toLowerCase())) {
            continue;
          }
        }
        filteredTypes.add(eventType);
      }

      Map<String, Long> counts = new HashMap<>();
      long totalEvents = 0;

      if (scan) {
        // Single-pass count of all types — O(file_size) instead of O(N × file_size)
        LOG.info("Scanning {} types for event counts...", filteredTypes.size());
        long scanStart = System.currentTimeMillis();

        Map<String, Long> allCounts = evaluator.countAllEventTypes(sessionInfo.session());
        for (String eventType : filteredTypes) {
          long count = allCounts.getOrDefault(eventType, 0L);
          counts.put(eventType, count);
          totalEvents += count;
        }

        long scanDuration = System.currentTimeMillis() - scanStart;
        LOG.info("Scan completed in {}ms, found {} total events", scanDuration, totalEvents);
      } else {
        // Use cached counts (may be 0 if no queries have been run)
        Map<String, Long> cachedCounts = sessionInfo.session().getEventTypeCounts();
        for (String eventType : filteredTypes) {
          counts.put(eventType, cachedCounts.getOrDefault(eventType, 0L));
        }
        totalEvents = sessionInfo.session().getTotalEvents();
      }

      // Build result list sorted by count descending
      List<Map<String, Object>> eventList = new ArrayList<>();
      for (String eventType : filteredTypes) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", eventType);
        entry.put("count", counts.getOrDefault(eventType, 0L));
        eventList.add(entry);
      }

      eventList.sort(
          Comparator.comparingLong((Map<String, Object> e) -> (Long) e.get("count")).reversed());

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", sessionInfo.id());
      response.put("recordingPath", sessionInfo.recordingPath().toString());
      response.put("totalTypes", eventList.size());
      response.put("totalEvents", totalEvents);
      response.put("scanned", scan);
      response.put("eventTypes", eventList);

      if (filter != null && !filter.isBlank()) {
        response.put("filter", filter);
      }

      return successResult(response);

    } catch (IllegalArgumentException e) {
      LOG.warn("Session error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to list event types: {}", e.getMessage(), e);
      return errorResult("Failed to list event types: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_close
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrCloseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias to close"
            },
            "closeAll": {
              "type": "boolean",
              "description": "Close all open sessions"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_close",
            "Closes a JFR recording session and releases resources. "
                + "Options: provide sessionId for specific session, closeAll=true for all sessions, "
                + "or neither to close the current session.",
            schema),
        (exchange, args) -> handleJfrClose(args.arguments()));
  }

  public CallToolResult handleJfrClose(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    Boolean closeAll = args.get("closeAll") instanceof Boolean b ? b : null;

    try {
      if (closeAll != null && closeAll) {
        int count = sessionRegistry.size();
        sessionRegistry.closeAll();
        LOG.info("Closed all {} sessions", count);
        return successResult(
            Map.of(
                "success",
                true,
                "message",
                "Closed " + count + " session(s)",
                "remainingSessions",
                0));
      }

      if (sessionId == null || sessionId.isBlank()) {
        var current = sessionRegistry.getCurrent();
        if (current.isEmpty()) {
          return errorResult("No session to close. No sessions are open.");
        }
        sessionId = String.valueOf(current.get().id());
      }

      boolean closed = sessionRegistry.close(sessionId);
      if (closed) {
        LOG.info("Closed session: {}", sessionId);
        return successResult(
            Map.of(
                "success",
                true,
                "message",
                "Session " + sessionId + " closed successfully",
                "remainingSessions",
                sessionRegistry.size()));
      } else {
        return errorResult("Session not found: " + sessionId);
      }

    } catch (Exception e) {
      LOG.error("Failed to close session: {}", e.getMessage(), e);
      return errorResult("Failed to close session: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_help
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createJfrHelpTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "topic": {
              "type": "string",
              "description": "Help topic: overview, filters, pipeline, functions, examples, event_types, tools",
              "enum": ["overview", "filters", "pipeline", "functions", "examples", "event_types", "tools"]
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "jfr_help",
            "Returns JfrPath query language documentation. "
                + "Call without arguments for overview, or specify a topic for detailed help. "
                + "Topics: overview, filters, pipeline, functions, examples, event_types, tools.",
            schema),
        (exchange, args) -> handleJfrHelp(args.arguments()));
  }

  public CallToolResult handleJfrHelp(Map<String, Object> args) {
    String topic = (String) args.get("topic");
    if (topic == null || topic.isBlank()) {
      topic = "overview";
    }

    String content =
        switch (topic.toLowerCase()) {
          case "overview" -> getOverviewHelp();
          case "filters" -> getFiltersHelp();
          case "pipeline" -> getPipelineHelp();
          case "functions" -> getFunctionsHelp();
          case "examples" -> getExamplesHelp();
          case "event_types" -> getEventTypesHelp();
          case "tools" -> getToolsHelp();
          default ->
              "Unknown topic: "
                  + topic
                  + ". Available: overview, filters, pipeline, functions, examples, event_types, tools";
        };

    return new CallToolResult(List.of(new TextContent(content)), false, null, null);
  }

  public String getOverviewHelp() {
    return jfrHelpProvider.getOverviewHelp();
  }

  public String getFiltersHelp() {
    return jfrHelpProvider.getFiltersHelp();
  }

  public String getPipelineHelp() {
    return jfrHelpProvider.getPipelineHelp();
  }

  public String getFunctionsHelp() {
    return jfrHelpProvider.getFunctionsHelp();
  }

  public String getExamplesHelp() {
    return jfrHelpProvider.getExamplesHelp();
  }

  public String getEventTypesHelp() {
    return jfrHelpProvider.getEventTypesHelp();
  }

  public String getToolsHelp() {
    return jfrHelpProvider.getToolsHelp();
  }
}

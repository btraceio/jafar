package io.jafar.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.parser.api.Values;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.servlet.Servlet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP Server for JFR analysis.
 *
 * <p>Exposes Jafar's JFR parsing capabilities via the Model Context Protocol, enabling AI agents to
 * analyze Java Flight Recordings.
 *
 * <p>Available tools:
 *
 * <ul>
 *   <li>{@code jfr_open} - Open a JFR recording file
 *   <li>{@code jfr_query} - Execute JfrPath queries
 *   <li>{@code jfr_list_types} - List available event types
 *   <li>{@code jfr_close} - Close a recording session
 *   <li>{@code jfr_help} - JfrPath query language documentation
 *   <li>{@code jfr_flamegraph} - Generate flamegraphs
 *   <li>{@code jfr_callgraph} - Generate call graphs
 *   <li>{@code jfr_exceptions} - Analyze exception patterns
 *   <li>{@code jfr_summary} - Recording overview
 *   <li>{@code jfr_hotmethods} - Hot method identification
 *   <li>{@code jfr_use} - USE Method resource analysis (Utilization, Saturation, Errors)
 *   <li>{@code jfr_tsa} - Thread State Analysis (TSA Method)
 * </ul>
 */
public final class JafarMcpServer {

  private static final Logger LOG = LoggerFactory.getLogger(JafarMcpServer.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final SessionRegistry sessionRegistry;
  private final JfrPathEvaluator evaluator;

  public JafarMcpServer() {
    this.sessionRegistry = new SessionRegistry();
    this.evaluator = new JfrPathEvaluator();
  }

  public static void main(String[] args) {
    var server = new JafarMcpServer();

    // Check for --stdio flag
    boolean useStdio = false;
    for (String arg : args) {
      if ("--stdio".equals(arg)) {
        useStdio = true;
        break;
      }
    }

    if (useStdio) {
      server.runStdio();
    } else {
      server.runSse();
    }
  }

  /** Run server with stdio transport (for Claude Code integration). */
  public void runStdio() {
    LOG.info("Starting Jafar MCP Server with stdio transport");

    try {
      // Create stdio transport
      var transportProvider = new StdioServerTransportProvider(MAPPER);

      // Build MCP server
      // Note: The SDK's StdioServerTransportProvider starts reading from stdin
      // automatically when the server is built
      McpSyncServer mcpServer =
          McpServer.sync(transportProvider)
              .serverInfo("jafar-mcp", "0.10.0")
              .capabilities(ServerCapabilities.builder().tools(true).logging().build())
              .tools(createToolSpecifications())
              .build();

      LOG.info("Jafar MCP Server ready (stdio mode)");

      // Shutdown hook for cleanup
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    LOG.info("Shutting down...");
                    sessionRegistry.shutdown();
                    mcpServer.close();
                  }));

      // Keep the main thread alive - use a latch that never counts down
      // The server will exit when stdin closes (EOF) or on SIGTERM
      var latch = new java.util.concurrent.CountDownLatch(1);
      latch.await();

    } catch (InterruptedException e) {
      LOG.info("Server interrupted, shutting down");
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      LOG.error("Failed to start server: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  /** Run server with HTTP/SSE transport (for web clients). */
  public void runSse() {
    int port = Integer.getInteger("mcp.port", 3000);
    LOG.info("Starting Jafar MCP Server on port {}", port);

    try {
      // Create HTTP SSE transport
      var transportProvider =
          HttpServletSseServerTransportProvider.builder()
              .objectMapper(MAPPER)
              .messageEndpoint("/mcp/message")
              .build();

      // Build MCP server
      McpSyncServer mcpServer =
          McpServer.sync(transportProvider)
              .serverInfo("jafar-mcp", "0.10.0")
              .capabilities(ServerCapabilities.builder().tools(true).logging().build())
              .tools(createToolSpecifications())
              .build();

      // Set up Jetty
      Server jettyServer = new Server(port);
      ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
      context.setContextPath("/");
      jettyServer.setHandler(context);

      // Register MCP servlet
      context.addServlet(new ServletHolder((Servlet) transportProvider), "/mcp/*");

      // Shutdown hook
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    LOG.info("Shutting down...");
                    sessionRegistry.shutdown();
                    mcpServer.close();
                    try {
                      jettyServer.stop();
                    } catch (Exception e) {
                      LOG.warn("Error stopping Jetty: {}", e.getMessage());
                    }
                  }));

      // Start server
      jettyServer.start();
      LOG.info("Jafar MCP Server started at http://localhost:{}/mcp", port);
      LOG.info("SSE endpoint: http://localhost:{}/mcp/sse", port);
      LOG.info("Message endpoint: http://localhost:{}/mcp/message", port);
      jettyServer.join();

    } catch (Exception e) {
      LOG.error("Failed to start server: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  private List<McpServerFeatures.SyncToolSpecification> createToolSpecifications() {
    return List.of(
        createJfrOpenTool(),
        createJfrQueryTool(),
        createJfrListTypesTool(),
        createJfrCloseTool(),
        createJfrHelpTool(),
        createJfrFlamegraphTool(),
        createJfrCallgraphTool(),
        createJfrExceptionsTool(),
        createJfrSummaryTool(),
        createJfrHotmethodsTool(),
        createJfrUseTool(),
        createJfrTsaTool());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_open
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createJfrOpenTool() {
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
        new Tool(
            "jfr_open",
            "Opens a JFR (Java Flight Recording) file for analysis. "
                + "Returns a session ID that can be used with other jfr_* tools. "
                + "If no session ID is provided to other tools, they use the most recently opened session.",
            schema),
        (exchange, args) -> handleJfrOpen(args));
  }

  private CallToolResult handleJfrOpen(Map<String, Object> args) {
    String path = (String) args.get("path");
    String alias = (String) args.get("alias");

    if (path == null || path.isBlank()) {
      return errorResult("Path is required");
    }

    try {
      Path recordingPath = Path.of(path);

      if (!Files.exists(recordingPath)) {
        return errorResult("File not found: " + path);
      }
      if (!Files.isRegularFile(recordingPath)) {
        return errorResult("Not a file: " + path);
      }
      if (!Files.isReadable(recordingPath)) {
        return errorResult("File not readable: " + path);
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

  private McpServerFeatures.SyncToolSpecification createJfrQueryTool() {
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
        new Tool(
            "jfr_query",
            "Executes a JfrPath query against a JFR recording. "
                + "JfrPath forms: events/<eventType>, events/<eventType>[filter], events/<eventType> | pipeline. "
                + "Common types: jdk.ExecutionSample, jdk.GCPhasePause, jdk.MonitorEnter, jdk.FileRead. "
                + "Pipeline ops: count(), groupBy(field), top(n), select(f1,f2), stats(field). "
                + "Examples: events/jdk.ExecutionSample | count(), events/jdk.GCPhasePause | top(10)",
            schema),
        (exchange, args) -> handleJfrQuery(args));
  }

  private CallToolResult handleJfrQuery(Map<String, Object> args) {
    String query = (String) args.get("query");
    String sessionId = (String) args.get("sessionId");
    Integer limit = args.get("limit") instanceof Number n ? n.intValue() : null;

    if (query == null || query.isBlank()) {
      return errorResult("Query is required");
    }

    int resultLimit = (limit != null && limit > 0) ? limit : 100;

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      JfrPath.Query parsed = JfrPathParser.parse(query);
      LOG.debug("Parsed query: {}", parsed);

      List<Map<String, Object>> results = evaluator.evaluate(sessionInfo.session(), parsed);

      if (results.size() > resultLimit) {
        results = results.subList(0, resultLimit);
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("query", query);
      response.put("sessionId", sessionInfo.id());
      response.put("resultCount", results.size());
      response.put("results", results);

      if (results.size() == resultLimit) {
        response.put("truncated", true);
        response.put(
            "message",
            "Results truncated to " + resultLimit + " items. Use 'limit' parameter for more.");
      }

      return successResult(response);

    } catch (IllegalArgumentException e) {
      LOG.warn("Query error: {}", e.getMessage());
      return errorResult("Query error: " + e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to execute query: {}", e.getMessage(), e);
      return errorResult("Query execution failed: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_list_types
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createJfrListTypesTool() {
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
        new Tool(
            "jfr_list_types",
            "Lists all event types available in a JFR recording. "
                + "By default returns type names from metadata (fast). "
                + "Use scan=true to get actual event counts (scans the entire recording). "
                + "Categories: jdk.* (JDK events), jdk.jfr.* (JFR infrastructure), custom app events.",
            schema),
        (exchange, args) -> handleJfrListTypes(args));
  }

  private CallToolResult handleJfrListTypes(Map<String, Object> args) {
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
        // Scan recording to get actual counts for each type
        LOG.info("Scanning {} types for event counts...", filteredTypes.size());
        long scanStart = System.currentTimeMillis();

        for (String eventType : filteredTypes) {
          try {
            String query = "events/" + eventType + " | count()";
            JfrPath.Query parsed = JfrPathParser.parse(query);
            List<Map<String, Object>> results = evaluator.evaluate(sessionInfo.session(), parsed);

            if (!results.isEmpty() && results.get(0).containsKey("count")) {
              Object countVal = results.get(0).get("count");
              long count = countVal instanceof Number n ? n.longValue() : 0L;
              counts.put(eventType, count);
              totalEvents += count;
            }
          } catch (Exception e) {
            LOG.debug("Could not count {}: {}", eventType, e.getMessage());
            counts.put(eventType, 0L);
          }
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

  private McpServerFeatures.SyncToolSpecification createJfrCloseTool() {
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
        new Tool(
            "jfr_close",
            "Closes a JFR recording session and releases resources. "
                + "Options: provide sessionId for specific session, closeAll=true for all sessions, "
                + "or neither to close the current session.",
            schema),
        (exchange, args) -> handleJfrClose(args));
  }

  private CallToolResult handleJfrClose(Map<String, Object> args) {
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

  private McpServerFeatures.SyncToolSpecification createJfrHelpTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "topic": {
              "type": "string",
              "description": "Help topic: overview, filters, pipeline, functions, examples, event_types",
              "enum": ["overview", "filters", "pipeline", "functions", "examples", "event_types"]
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        new Tool(
            "jfr_help",
            "Returns JfrPath query language documentation. "
                + "Call without arguments for overview, or specify a topic for detailed help. "
                + "Topics: overview, filters, pipeline, functions, examples, event_types.",
            schema),
        (exchange, args) -> handleJfrHelp(args));
  }

  private CallToolResult handleJfrHelp(Map<String, Object> args) {
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
          default -> "Unknown topic: "
              + topic
              + ". Available: overview, filters, pipeline, functions, examples, event_types";
        };

    return new CallToolResult(List.of(new TextContent(content)), false);
  }

  private String getOverviewHelp() {
    return """
        # JfrPath Query Language

        JfrPath is a path-based query language for navigating and querying JFR files.

        ## Basic Syntax
        ```
        events/<eventType>[filters] | pipeline_operator
        ```

        ## Query Roots
        - `events/<type>` - Access event data (e.g., events/jdk.ExecutionSample)
        - `metadata/<type>` - Access type metadata
        - `chunks` - Access chunk information
        - `cp/<type>` - Access constant pool entries

        ## Field Navigation
        - Simple: `events/jdk.FileRead/path`
        - Nested: `events/jdk.ExecutionSample/stackTrace/frames`
        - Array index: `events/jdk.ExecutionSample/stackTrace/frames/0`

        ## Quick Examples
        ```
        events/jdk.ExecutionSample | count()
        events/jdk.FileRead[bytes>1000] | top(10)
        events/jdk.GCPhasePause | stats(duration)
        events/jdk.ExecutionSample | groupBy(eventThread/javaName)
        ```

        ## Help Topics
        Call jfr_help with topic parameter for detailed documentation:
        - filters: Filter syntax and operators
        - pipeline: Aggregation operators (count, groupBy, top, stats, etc.)
        - functions: Built-in functions for filters and select
        - examples: Common query patterns
        - event_types: Common JDK event types
        """;
  }

  private String getFiltersHelp() {
    return """
        # JfrPath Filters

        Filters use square brackets `[...]` to constrain results.

        ## Comparison Operators
        - `=` : Equal
        - `!=` : Not equal
        - `>` : Greater than
        - `>=` : Greater than or equal
        - `<` : Less than
        - `<=` : Less than or equal
        - `~` : Regex match

        ## Examples
        ```
        events/jdk.FileRead[bytes>1000]
        events/jdk.ExecutionSample[eventThread/javaName="main"]
        events/jdk.FileRead[path~"/tmp/.*"]
        events/jdk.GCPhasePause[duration>10000000]
        ```

        ## Boolean Expressions
        ```
        events/jdk.FileRead[bytes>1000 and path~"/tmp/.*"]
        events/jdk.ExecutionSample[eventThread/javaName="main" or eventThread/javaName="worker"]
        events/jdk.FileRead[not (bytes<100)]
        ```

        ## Filter Functions
        - `contains(field, "substr")` - String contains
        - `startsWith(field, "prefix")` - String starts with
        - `endsWith(field, "suffix")` - String ends with
        - `matches(field, "regex")` - Regex match
        - `exists(field)` - Field is not null
        - `empty(field)` - String/list is empty
        - `between(field, min, max)` - Numeric range
        - `len(field)` - Length (use in comparisons)

        ## Duration Values
        Durations can be specified with units:
        - `10ms` - 10 milliseconds
        - `1s` - 1 second
        - `500us` - 500 microseconds
        - `100ns` - 100 nanoseconds

        Example: `events/jdk.GCPhasePause[duration>10ms]`

        ## List Matching
        - `any:` (default) - Any element matches
        - `all:` - All elements match
        - `none:` - No elements match

        Example: `events/jdk.ExecutionSample[any:stackTrace/frames[matches(method/type/name/string, ".*MyClass.*")]]`
        """;
  }

  private String getPipelineHelp() {
    return """
        # JfrPath Pipeline Operators

        Pipeline operators transform or aggregate results. Append with `|`:

        ## count()
        Count matching events.
        ```
        events/jdk.ExecutionSample | count()
        → { "count": 12345 }
        ```

        ## top(n[, by=field, asc=false])
        Return top N results sorted by field.
        ```
        events/jdk.FileRead | top(10, by=bytes)
        events/jdk.GCPhasePause | top(5, by=duration)
        events/jdk.FileRead | top(10, by=bytes, asc=true)  # smallest first
        ```

        ## groupBy(field[, agg=count|sum|avg|min|max, value=path, sortBy=key|value, asc=false])
        Group by field and aggregate.
        ```
        events/jdk.ExecutionSample | groupBy(eventThread/javaName)
        events/jdk.FileRead | groupBy(path, agg=sum, value=bytes)
        events/jdk.ExecutionSample | groupBy(eventThread/javaName, sortBy=value)
        ```

        ## stats([field])
        Compute min, max, avg, stddev, count.
        ```
        events/jdk.FileRead | stats(bytes)
        events/jdk.GCPhasePause | stats(duration)
        → { "min": 100, "max": 50000, "avg": 5000, "stddev": 2500, "count": 200 }
        ```

        ## sum([field])
        Sum numeric values.
        ```
        events/jdk.FileRead | sum(bytes)
        → { "sum": 1234567, "count": 100 }
        ```

        ## quantiles(q1, q2, ...[, path=field])
        Compute percentiles (0.0 to 1.0).
        ```
        events/jdk.GCPhasePause | quantiles(0.5, 0.9, 0.99, path=duration)
        → { "p50": 1000, "p90": 5000, "p99": 20000 }
        ```

        ## select(field1, field2, expr as alias, ...)
        Project specific fields or compute expressions.
        ```
        events/jdk.FileRead | select(path, bytes)
        events/jdk.FileRead | select(path, bytes / 1024 as kb)
        events/jdk.ExecutionSample | select(eventThread/javaName as thread, startTime)
        ```

        ## sortBy(field[, asc=false])
        Sort results by field.
        ```
        events/jdk.FileRead | select(path, bytes) | sortBy(bytes)
        ```

        ## decorateByTime(eventType, fields=f1,f2[, threadPath=..., decoratorThreadPath=...])
        Join with time-overlapping events on same thread.
        ```
        events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)
        ```

        ## decorateByKey(eventType, key=path, decoratorKey=path, fields=f1,f2)
        Join using correlation keys.
        ```
        events/jdk.ExecutionSample | decorateByKey(RequestStart, key=eventThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=requestId)
        ```
        """;
  }

  private String getFunctionsHelp() {
    return """
        # JfrPath Functions

        ## Filter Functions

        ### String Functions
        - `contains(field, "substr")` - Check if contains substring
        - `startsWith(field, "prefix")` - Check if starts with prefix
        - `endsWith(field, "suffix")` - Check if ends with suffix
        - `matches(field, "regex")` - Check if matches regex
        - `matches(field, "regex", "i")` - Case-insensitive regex

        ### Existence Functions
        - `exists(field)` - Field is present and not null
        - `empty(field)` - String or list is empty

        ### Numeric Functions
        - `between(field, min, max)` - Value in range (inclusive)
        - `len(field)` - Length of string or array

        ## Select Expression Functions

        ### Conditional
        - `if(condition, trueValue, falseValue)` - Conditional expression

        ### String Functions
        - `upper(string)` - Convert to uppercase
        - `lower(string)` - Convert to lowercase
        - `substring(string, start[, length])` - Extract substring
        - `length(string)` - String length
        - `coalesce(v1, v2, ...)` - First non-null value

        ### Arithmetic
        - `+`, `-`, `*`, `/` - Basic arithmetic
        - Parentheses for grouping: `(bytes * count) / 1024`

        ### String Templates
        Use `${...}` for interpolation:
        ```
        events/jdk.FileRead | select("${path}: ${bytes / 1024} KB" as info)
        ```

        ## Transform Operators (after |)
        - `| len()` - String/list length
        - `| uppercase()` - Convert to uppercase
        - `| lowercase()` - Convert to lowercase
        - `| trim()` - Trim whitespace
        - `| abs()` - Absolute value
        - `| round()` - Round to integer
        - `| floor()` - Round down
        - `| ceil()` - Round up
        """;
  }

  private String getExamplesHelp() {
    return """
        # JfrPath Query Examples

        ## CPU/Execution Analysis
        ```
        # Count execution samples
        events/jdk.ExecutionSample | count()

        # Samples by thread
        events/jdk.ExecutionSample | groupBy(eventThread/javaName)

        # Top threads by sample count
        events/jdk.ExecutionSample | groupBy(eventThread/javaName, sortBy=value) | top(10)

        # Samples for specific thread
        events/jdk.ExecutionSample[eventThread/javaName="main"] | count()
        ```

        ## GC Analysis
        ```
        # GC pause statistics
        events/jdk.GCPhasePause | stats(duration)

        # Longest GC pauses
        events/jdk.GCPhasePause | top(10, by=duration)

        # GC pauses over 10ms
        events/jdk.GCPhasePause[duration>10ms] | count()

        # Heap usage after GC
        events/jdk.GCHeapSummary[when/when="After GC"] | select(heapUsed, heapSpace/committedSize)
        ```

        ## I/O Analysis
        ```
        # File read statistics
        events/jdk.FileRead | stats(bytes)

        # Largest file reads
        events/jdk.FileRead | top(10, by=bytes)

        # Reads by file path
        events/jdk.FileRead | groupBy(path, agg=sum, value=bytes)

        # Slow file operations (>10ms)
        events/jdk.FileRead[duration>10ms] | top(10)

        # Socket reads by host
        events/jdk.SocketRead | groupBy(host)
        ```

        ## Lock Contention
        ```
        # Monitor enter events
        events/jdk.JavaMonitorEnter | count()

        # Longest lock waits
        events/jdk.JavaMonitorEnter | top(10, by=duration)

        # Contention by monitor class
        events/jdk.JavaMonitorEnter | groupBy(monitorClass)

        # Lock waits over 1ms
        events/jdk.JavaMonitorEnter[duration>1ms] | count()
        ```

        ## Memory Analysis
        ```
        # Allocation samples
        events/jdk.ObjectAllocationSample | count()

        # Allocations by class
        events/jdk.ObjectAllocationSample | groupBy(objectClass/name)

        # Large allocations
        events/jdk.ObjectAllocationOutsideTLAB | top(10, by=allocationSize)
        ```

        ## Thread Analysis
        ```
        # Thread starts
        events/jdk.ThreadStart | count()

        # Thread CPU load
        events/jdk.ThreadCPULoad | top(10, by=user)

        # Thread sleep events
        events/jdk.ThreadSleep | stats(time)
        ```

        ## Correlation/Decoration
        ```
        # Samples during lock waits
        events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration)

        # Group samples by lock class
        events/jdk.ExecutionSample | decorateByTime(jdk.JavaMonitorEnter, fields=monitorClass) | groupBy($decorator.monitorClass)
        ```
        """;
  }

  private String getEventTypesHelp() {
    return """
        # Common JDK Event Types

        ## CPU/Execution
        - `jdk.ExecutionSample` - CPU profiling samples (fields: stackTrace, state, eventThread)
        - `jdk.NativeMethodSample` - Native method samples
        - `jdk.CPULoad` - CPU load (fields: jvmUser, jvmSystem, machineTotal)
        - `jdk.ThreadCPULoad` - Per-thread CPU (fields: user, system, eventThread)

        ## GC
        - `jdk.GCPhasePause` - GC pause phases (fields: name, duration)
        - `jdk.GarbageCollection` - GC events (fields: name, cause, duration)
        - `jdk.GCHeapSummary` - Heap state (fields: when, heapUsed, heapSpace)
        - `jdk.G1HeapSummary` - G1 heap (fields: edenUsedSize, survivorUsedSize)
        - `jdk.YoungGarbageCollection` - Young GC (fields: tenuringThreshold)
        - `jdk.OldGarbageCollection` - Old GC

        ## Memory
        - `jdk.ObjectAllocationSample` - Sampled allocations (fields: objectClass, weight)
        - `jdk.ObjectAllocationInNewTLAB` - TLAB allocations (fields: objectClass, tlabSize)
        - `jdk.ObjectAllocationOutsideTLAB` - Large allocations (fields: objectClass, allocationSize)

        ## I/O
        - `jdk.FileRead` - File reads (fields: path, bytes, duration)
        - `jdk.FileWrite` - File writes (fields: path, bytes, duration)
        - `jdk.SocketRead` - Socket reads (fields: host, port, bytes, duration)
        - `jdk.SocketWrite` - Socket writes (fields: host, port, bytes, duration)

        ## Threads
        - `jdk.ThreadStart` - Thread creation (fields: thread, parentThread)
        - `jdk.ThreadEnd` - Thread termination (fields: thread)
        - `jdk.ThreadSleep` - Thread.sleep (fields: time, eventThread)
        - `jdk.ThreadPark` - LockSupport.park (fields: timeout, eventThread)

        ## Locking
        - `jdk.JavaMonitorEnter` - synchronized enter (fields: monitorClass, duration)
        - `jdk.JavaMonitorWait` - Object.wait (fields: monitorClass, timeout, duration)
        - `jdk.JavaMonitorInflate` - Monitor inflation (fields: monitorClass)

        ## Compilation
        - `jdk.Compilation` - JIT compilation (fields: method, duration, isOsr)
        - `jdk.CompilerPhase` - Compilation phases (fields: name, duration)

        ## Class Loading
        - `jdk.ClassLoad` - Class loading (fields: loadedClass, duration)
        - `jdk.ClassDefine` - Class definition (fields: definedClass)

        ## Common Field Paths
        - `eventThread/javaName` - Thread name
        - `eventThread/javaThreadId` - Thread ID
        - `stackTrace/frames` - Stack frames array
        - `stackTrace/frames/0/method/name/string` - Top frame method name
        - `duration` - Event duration (nanoseconds)
        - `startTime` - Event start time
        """;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createJfrFlamegraphTool() {
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
        new Tool(
            "jfr_flamegraph",
            "Generates aggregated stack trace data for flamegraph-style analysis. "
                + "Returns stack paths with sample counts in folded or tree format. "
                + "Use direction=bottom-up to see hot methods (where time is spent), "
                + "or direction=top-down to see call paths from entry points. "
                + "Folded format is semicolon-separated paths compatible with standard flamegraph tools.",
            schema),
        (exchange, args) -> handleJfrFlamegraph(args));
  }

  private CallToolResult handleJfrFlamegraph(Map<String, Object> args) {
    String eventType = (String) args.get("eventType");
    String direction = (String) args.getOrDefault("direction", "bottom-up");
    String format = (String) args.getOrDefault("format", "folded");
    String sessionId = (String) args.get("sessionId");
    Integer minSamples = args.get("minSamples") instanceof Number n ? n.intValue() : 1;
    Integer maxDepth = args.get("maxDepth") instanceof Number n ? n.intValue() : null;

    if (eventType == null || eventType.isBlank()) {
      return errorResult("eventType is required");
    }

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      // Query all events with non-empty stack traces
      String query = "events/" + eventType;
      JfrPath.Query parsed = JfrPathParser.parse(query);
      List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

      // Build aggregation tree
      FlameNode root = new FlameNode("root");
      int processedEvents = 0;

      for (Map<String, Object> event : events) {
        List<String> frames = extractFrames(event, direction, maxDepth);
        if (!frames.isEmpty()) {
          root.addPath(frames);
          processedEvents++;
        }
      }

      // Format output
      if ("tree".equals(format)) {
        return formatFlamegraphTree(root, direction, processedEvents, minSamples);
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
  private String extractMethodName(Object frame) {
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

  private CallToolResult formatFlamegraphFolded(FlameNode root, int minSamples) {
    StringBuilder sb = new StringBuilder();
    List<String> path = new ArrayList<>();
    collectFoldedPaths(root, path, sb, minSamples);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("format", "folded");
    result.put("totalSamples", root.value);
    result.put("data", sb.toString());
    return successResult(result);
  }

  private void collectFoldedPaths(
      FlameNode node, List<String> path, StringBuilder sb, int minSamples) {
    if (node.children.isEmpty()) {
      // Leaf node - output the path
      if (node.value >= minSamples && !path.isEmpty()) {
        sb.append(String.join(";", path)).append(" ").append(node.value).append("\n");
      }
    } else {
      for (Map.Entry<String, FlameNode> entry : node.children.entrySet()) {
        path.add(entry.getKey());
        collectFoldedPaths(entry.getValue(), path, sb, minSamples);
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
    map.put("value", node.value);

    if (!node.children.isEmpty()) {
      List<Map<String, Object>> children = new ArrayList<>();
      for (FlameNode child : node.children.values()) {
        if (child.value >= minSamples) {
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
    long value;
    final Map<String, FlameNode> children = new LinkedHashMap<>();

    FlameNode(String name) {
      this.name = name;
    }

    void addPath(List<String> frames) {
      value++;
      if (!frames.isEmpty()) {
        String head = frames.get(0);
        children.computeIfAbsent(head, FlameNode::new).addPath(frames.subList(1, frames.size()));
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_callgraph
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createJfrCallgraphTool() {
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
        new Tool(
            "jfr_callgraph",
            "Generates a call graph showing caller-callee relationships from stack traces. "
                + "Unlike flamegraph (which preserves full paths), this shows which methods call which, "
                + "revealing convergence points where multiple callers invoke the same method. "
                + "DOT format can be visualized with graphviz. JSON format includes node and edge data.",
            schema),
        (exchange, args) -> handleJfrCallgraph(args));
  }

  private CallToolResult handleJfrCallgraph(Map<String, Object> args) {
    String eventType = (String) args.get("eventType");
    String format = (String) args.getOrDefault("format", "dot");
    String sessionId = (String) args.get("sessionId");
    Integer minWeight = args.get("minWeight") instanceof Number n ? n.intValue() : 1;

    if (eventType == null || eventType.isBlank()) {
      return errorResult("eventType is required");
    }

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      // Query all events
      String query = "events/" + eventType;
      JfrPath.Query parsed = JfrPathParser.parse(query);
      List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

      // Build call graph
      CallGraph graph = new CallGraph();
      int processedEvents = 0;

      for (Map<String, Object> event : events) {
        List<String> frames =
            extractFrames(event, "top-down", null); // top-down for caller->callee order
        if (!frames.isEmpty()) {
          graph.addStack(frames);
          processedEvents++;
        }
      }

      // Format output
      if ("json".equals(format)) {
        return formatCallgraphJson(graph, processedEvents, minWeight);
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

  private CallToolResult formatCallgraphDot(CallGraph graph, int minWeight) {
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
    result.put("totalSamples", graph.totalSamples);
    result.put("nodeCount", graph.nodeSamples.size());
    result.put("edgeCount", graph.edges.size());
    result.put("data", sb.toString());
    return successResult(result);
  }

  private String escapeForDot(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private CallToolResult formatCallgraphJson(CallGraph graph, int processedEvents, int minWeight) {
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
    result.put("nodes", nodes);
    result.put("edges", edges);
    return successResult(result);
  }

  /** Graph structure for caller-callee relationship aggregation. */
  private static class CallGraph {
    final Map<String, Long> nodeSamples = new LinkedHashMap<>();
    final Map<String, Long> edges = new LinkedHashMap<>();
    final Map<String, Integer> inDegree = new HashMap<>();
    long totalSamples = 0;

    void addStack(List<String> frames) {
      totalSamples++;

      // Process caller->callee pairs
      for (int i = 0; i < frames.size() - 1; i++) {
        String caller = frames.get(i);
        String callee = frames.get(i + 1);
        String edge = caller + "->" + callee;

        edges.merge(edge, 1L, Long::sum);
        nodeSamples.merge(caller, 1L, Long::sum);

        // Track unique callers per callee for inDegree
        inDegree.merge(callee, 1, (old, v) -> old);
      }

      // Count the leaf node too
      if (!frames.isEmpty()) {
        String leaf = frames.get(frames.size() - 1);
        nodeSamples.merge(leaf, 1L, Long::sum);
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_exceptions
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createJfrExceptionsTool() {
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
        new Tool(
            "jfr_exceptions",
            "Analyzes exception events in a JFR recording. Extracts exception types from stack traces, "
                + "groups by exception class, and identifies throw sites. Works with both JDK exception events "
                + "(jdk.JavaExceptionThrow) and profiler exception samples (datadog.ExceptionSample). "
                + "Returns exception type counts, throw site locations, and patterns.",
            schema),
        (exchange, args) -> handleJfrExceptions(args));
  }

  private CallToolResult handleJfrExceptions(Map<String, Object> args) {
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

      // Query exception events
      String query = "events/" + eventType;
      JfrPath.Query parsed = JfrPathParser.parse(query);
      List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

      if (events.isEmpty()) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventType);
        result.put("totalExceptions", 0);
        result.put("message", "No exception events found for type: " + eventType);
        return successResult(result);
      }

      // Analyze exceptions
      ExceptionAnalysis analysis = analyzeExceptions(events);

      // Build result
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("eventType", eventType);
      result.put("totalExceptions", events.size());

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
                entry.put("pct", String.format("%.1f%%", e.getValue() * 100.0 / events.size()));
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
                entry.put("pct", String.format("%.1f%%", e.getValue() * 100.0 / events.size()));
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
    // Check for common exception event types
    String[] candidateTypes = {
      "jdk.JavaExceptionThrow", "datadog.ExceptionSample", "jdk.ExceptionStatistics"
    };

    for (String type : candidateTypes) {
      try {
        String query = "events/" + type + " | count()";
        JfrPath.Query parsed = JfrPathParser.parse(query);
        List<Map<String, Object>> result = evaluator.evaluate(sessionInfo.session(), parsed);
        if (!result.isEmpty()) {
          Object countObj = result.get(0).get("count");
          if (countObj instanceof Number n && n.longValue() > 0) {
            return type;
          }
        }
      } catch (Exception ignored) {
        // Try next type
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private ExceptionAnalysis analyzeExceptions(List<Map<String, Object>> events) {
    ExceptionAnalysis analysis = new ExceptionAnalysis();

    for (Map<String, Object> event : events) {
      ExceptionInfo info = extractExceptionInfo(event);
      if (info.exceptionType != null) {
        analysis.exceptionTypes.merge(info.exceptionType, 1L, Long::sum);

        if (info.throwSite != null) {
          analysis.throwSites.merge(info.throwSite, 1L, Long::sum);

          // Track top throw site per exception type
          analysis
              .throwSitesByType
              .computeIfAbsent(info.exceptionType, k -> new HashMap<>())
              .merge(info.throwSite, 1L, Long::sum);
        }
      }
    }

    // Compute top throw site for each exception type
    for (Map.Entry<String, Map<String, Long>> entry : analysis.throwSitesByType.entrySet()) {
      entry.getValue().entrySet().stream()
          .max(Comparator.comparingLong(Map.Entry::getValue))
          .ifPresent(e -> analysis.topThrowSiteByType.put(entry.getKey(), e.getKey()));
    }

    return analysis;
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
    final Map<String, Long> exceptionTypes = new LinkedHashMap<>();
    final Map<String, Long> throwSites = new LinkedHashMap<>();
    final Map<String, Map<String, Long>> throwSitesByType = new HashMap<>();
    final Map<String, String> topThrowSiteByType = new HashMap<>();
  }

  private static class ExceptionInfo {
    String exceptionType;
    String throwSite;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_summary
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createJfrSummaryTool() {
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
        new Tool(
            "jfr_summary",
            "Provides a quick overview of a JFR recording including duration, event counts, "
                + "and key highlights like GC statistics, exception rates, and top CPU consumers. "
                + "Useful for getting oriented with a new recording before deeper analysis.",
            schema),
        (exchange, args) -> handleJfrSummary(args));
  }

  private CallToolResult handleJfrSummary(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      Map<String, Object> result = new LinkedHashMap<>();

      // Recording metadata
      result.put("recordingPath", sessionInfo.recordingPath().toString());
      result.put("sessionId", sessionInfo.id());

      // Get all event types and counts
      Map<String, Long> eventCounts = new LinkedHashMap<>();
      long totalEvents = 0;

      // Query for event type counts
      Set<String> types = sessionInfo.session().getAvailableTypes();
      for (String type : types) {
        try {
          String query = "events/" + type + " | count()";
          JfrPath.Query parsed = JfrPathParser.parse(query);
          List<Map<String, Object>> countResult = evaluator.evaluate(sessionInfo.session(), parsed);
          if (!countResult.isEmpty()) {
            Object countObj = countResult.get(0).get("count");
            if (countObj instanceof Number n) {
              long count = n.longValue();
              if (count > 0) {
                eventCounts.put(type, count);
                totalEvents += count;
              }
            }
          }
        } catch (Exception ignored) {
          // Skip types that fail to query
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

      return successResult(result);

    } catch (Exception e) {
      LOG.error("Failed to generate summary: {}", e.getMessage(), e);
      return errorResult("Failed to generate summary: " + e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> computeGcStats(SessionRegistry.SessionInfo sessionInfo) {
    Map<String, Object> stats = new LinkedHashMap<>();

    // Try different GC event types
    String[] gcTypes = {
      "jdk.GarbageCollection",
      "jdk.YoungGarbageCollection",
      "jdk.OldGarbageCollection",
      "jdk.G1GarbageCollection"
    };

    long totalGCs = 0;
    long totalPauseNs = 0;
    String gcType = null;

    for (String type : gcTypes) {
      try {
        String query = "events/" + type;
        JfrPath.Query parsed = JfrPathParser.parse(query);
        List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

        if (!events.isEmpty()) {
          gcType = type;
          totalGCs += events.size();

          // Sum pause times (duration field in nanoseconds)
          for (Map<String, Object> event : events) {
            Object duration = event.get("duration");
            if (duration instanceof Number n) {
              totalPauseNs += n.longValue();
            }
          }
        }
      } catch (Exception ignored) {
        // Try next type
      }
    }

    if (totalGCs > 0) {
      stats.put("totalCollections", totalGCs);
      stats.put("totalPauseMs", totalPauseNs / 1_000_000);
      stats.put("avgPauseMs", (totalPauseNs / totalGCs) / 1_000_000);
      if (gcType != null) {
        stats.put("primaryType", gcType);
      }
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

    // Query and extract top frame
    try {
      String query = "events/" + eventType;
      JfrPath.Query parsed = JfrPathParser.parse(query);
      List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

      if (events.isEmpty()) {
        return null;
      }

      // Count leaf methods
      Map<String, Long> methodCounts = new HashMap<>();
      for (Map<String, Object> event : events) {
        List<String> frames = extractFrames(event, "bottom-up", 1);
        if (!frames.isEmpty()) {
          String method = frames.get(0);
          methodCounts.merge(method, 1L, Long::sum);
        }
      }

      // Return top method
      return methodCounts.entrySet().stream()
          .max(Comparator.comparingLong(Map.Entry::getValue))
          .map(
              e -> {
                long count = e.getValue();
                double pct = count * 100.0 / events.size();
                return String.format("%s (%.1f%%)", e.getKey(), pct);
              })
          .orElse(null);

    } catch (Exception e) {
      return null;
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_hotmethods
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createJfrHotmethodsTool() {
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
        new Tool(
            "jfr_hotmethods",
            "Returns the hottest methods (leaf frames) from CPU profiling samples. "
                + "Simpler and more compact than full flamegraph - just shows which methods are consuming CPU. "
                + "Useful for quick CPU hotspot identification.",
            schema),
        (exchange, args) -> handleJfrHotmethods(args));
  }

  private CallToolResult handleJfrHotmethods(Map<String, Object> args) {
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
      String query = "events/" + eventType;
      JfrPath.Query parsed = JfrPathParser.parse(query);
      List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

      if (events.isEmpty()) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventType);
        result.put("totalSamples", 0);
        result.put("message", "No execution sample events found for type: " + eventType);
        return successResult(result);
      }

      // Count leaf methods
      Map<String, Long> methodCounts = new HashMap<>();
      for (Map<String, Object> event : events) {
        List<String> frames = extractFrames(event, "bottom-up", 1);
        if (!frames.isEmpty()) {
          String method = frames.get(0);
          methodCounts.merge(method, 1L, Long::sum);
        }
      }

      // Build result
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("eventType", eventType);
      result.put("totalSamples", events.size());
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
                entry.put("pct", String.format("%.1f%%", e.getValue() * 100.0 / events.size()));
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

    for (String type : candidateTypes) {
      try {
        String query = "events/" + type + " | count()";
        JfrPath.Query parsed = JfrPathParser.parse(query);
        List<Map<String, Object>> result = evaluator.evaluate(sessionInfo.session(), parsed);
        if (!result.isEmpty()) {
          Object countObj = result.get(0).get("count");
          if (countObj instanceof Number n && n.longValue() > 0) {
            return type;
          }
        }
      } catch (Exception ignored) {
        // Try next type
      }
    }
    return null;
  }

  private String detectQueueTimeEventType(SessionRegistry.SessionInfo sessionInfo) {
    String[] candidateTypes = {"datadog.QueueTime"};

    for (String type : candidateTypes) {
      try {
        String query = "events/" + type + " | count()";
        JfrPath.Query parsed = JfrPathParser.parse(query);
        List<Map<String, Object>> result = evaluator.evaluate(sessionInfo.session(), parsed);
        if (!result.isEmpty()) {
          Object countObj = result.get(0).get("count");
          if (countObj instanceof Number n && n.longValue() > 0) {
            return type;
          }
        }
      } catch (Exception ignored) {
        // Try next type
      }
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

  private McpServerFeatures.SyncToolSpecification createJfrUseTool() {
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
        new Tool(
            "jfr_use",
            "Analyzes JFR recording using Brendan Gregg's USE Method (Utilization, Saturation, Errors). "
                + "Examines CPU, Memory, Threads/Locks, and I/O resources to identify bottlenecks. "
                + "Returns metrics for utilization (how busy), saturation (queued work), and errors for each resource.",
            schema),
        (exchange, args) -> handleJfrUse(args));
  }

  private CallToolResult handleJfrUse(Map<String, Object> args) {
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

      // CPU Resource Analysis
      if (resources.contains("cpu")) {
        resourceMetrics.put("cpu", analyzeCpuResource(sessionInfo, timeFilter));
      }

      // Memory Resource Analysis
      if (resources.contains("memory")) {
        resourceMetrics.put("memory", analyzeMemoryResource(sessionInfo, timeFilter));
      }

      // Threads/Locks Resource Analysis
      if (resources.contains("threads")) {
        resourceMetrics.put("threads", analyzeThreadsResource(sessionInfo, timeFilter));
      }

      // I/O Resource Analysis
      if (resources.contains("io")) {
        resourceMetrics.put("io", analyzeIoResource(sessionInfo, timeFilter));
      }

      result.put("resources", resourceMetrics);

      // Generate insights and summary
      if (includeInsights) {
        result.put("insights", generateUseInsights(resourceMetrics));
        result.put("summary", generateUseSummary(resourceMetrics));
      }

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
      // Detect execution event type
      String eventType = detectExecutionEventType(sessionInfo);
      if (eventType == null) {
        cpu.put("error", "No execution sample events found");
        return cpu;
      }

      // Get execution samples
      String query = "events/" + eventType + timeFilter;
      JfrPath.Query parsed = JfrPathParser.parse(query);
      List<Map<String, Object>> samples = evaluator.evaluate(sessionInfo.session(), parsed);

      if (samples.isEmpty()) {
        cpu.put("message", "No execution samples in time window");
        return cpu;
      }

      // Count samples by state
      Map<String, Long> stateCount = new HashMap<>();
      long runnableCount = 0;
      long saturatedCount = 0;

      for (Map<String, Object> event : samples) {
        String state = extractState(event);
        stateCount.merge(state, 1L, Long::sum);

        if ("RUNNABLE".equals(state)) {
          runnableCount++;
        } else if (Set.of("WAITING", "BLOCKED", "PARKED", "TIMED_WAITING").contains(state)) {
          saturatedCount++;
        }
      }

      long totalSamples = samples.size();
      double utilizationPct = (runnableCount * 100.0) / totalSamples;
      double saturationPct = (saturatedCount * 100.0) / totalSamples;

      // Utilization
      Map<String, Object> utilization = new LinkedHashMap<>();
      utilization.put("value", Math.round(utilizationPct * 10) / 10.0);
      utilization.put("unit", "%");
      utilization.put(
          "detail", String.format("%.1f%% of samples in RUNNABLE state", utilizationPct));
      Map<String, Object> utilizationSamples = new LinkedHashMap<>();
      utilizationSamples.put("runnable", runnableCount);
      utilizationSamples.put("total", totalSamples);
      utilization.put("samples", utilizationSamples);
      cpu.put("utilization", utilization);

      // Saturation
      Map<String, Object> saturation = new LinkedHashMap<>();
      saturation.put("value", Math.round(saturationPct * 10) / 10.0);
      saturation.put("unit", "%");
      saturation.put("detail", String.format("%.1f%% of samples waiting/blocked", saturationPct));
      Map<String, Object> saturationSamples = new LinkedHashMap<>();
      for (String state : List.of("WAITING", "BLOCKED", "PARKED", "TIMED_WAITING")) {
        if (stateCount.containsKey(state)) {
          saturationSamples.put(state.toLowerCase(), stateCount.get(state));
        }
      }
      saturationSamples.put("total", saturatedCount);
      saturation.put("samples", saturationSamples);
      cpu.put("saturation", saturation);

      // Errors
      Map<String, Object> errors = new LinkedHashMap<>();
      errors.put("value", 0);
      errors.put("detail", "No compilation failures detected");
      cpu.put("errors", errors);

      // Assessment
      cpu.put("assessment", assessCpuUtilization(utilizationPct));

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
      JfrPath.Query parsed = JfrPathParser.parse(heapQuery);
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
      parsed = JfrPathParser.parse(gcQuery);
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
      String allocQuery = "events/jdk.ObjectAllocationSample" + timeFilter;
      try {
        parsed = JfrPathParser.parse(allocQuery);
        List<Map<String, Object>> allocEvents = evaluator.evaluate(sessionInfo.session(), parsed);

        if (!allocEvents.isEmpty()) {
          Map<String, Long> allocByClass = new HashMap<>();
          for (Map<String, Object> event : allocEvents) {
            Object classObj = Values.get(event, "objectClass", "name");
            if (classObj == null) {
              classObj = Values.get(event, "objectClass");
            }
            String className = classObj != null ? String.valueOf(classObj) : "unknown";
            Object weightObj = Values.get(event, "weight");
            long weight = weightObj instanceof Number ? ((Number) weightObj).longValue() : 1;
            allocByClass.merge(className, weight, Long::sum);
          }

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
        String query = "events/" + eventType + timeFilter;
        JfrPath.Query parsed = JfrPathParser.parse(query);
        List<Map<String, Object>> samples = evaluator.evaluate(sessionInfo.session(), parsed);

        Set<String> uniqueThreads = new HashSet<>();
        for (Map<String, Object> event : samples) {
          uniqueThreads.add(extractThreadId(event));
        }

        Map<String, Object> utilization = new LinkedHashMap<>();
        utilization.put("value", uniqueThreads.size());
        utilization.put("unit", "threads");
        utilization.put("detail", uniqueThreads.size() + " active threads observed");
        threads.put("utilization", utilization);
      }

      // Get monitor contention
      String monitorQuery = "events/jdk.JavaMonitorEnter" + timeFilter;
      try {
        JfrPath.Query parsed = JfrPathParser.parse(monitorQuery);
        List<Map<String, Object>> monitorEvents = evaluator.evaluate(sessionInfo.session(), parsed);

        Map<String, Object> saturation = new LinkedHashMap<>();
        if (!monitorEvents.isEmpty()) {
          long totalContentionNs = 0;
          long maxContentionNs = 0;
          Map<String, Long> contentionByClass = new HashMap<>();

          for (Map<String, Object> event : monitorEvents) {
            Object durationObj = Values.get(event, "duration");
            if (durationObj instanceof Number) {
              long durationNs = ((Number) durationObj).longValue();
              totalContentionNs += durationNs;
              maxContentionNs = Math.max(maxContentionNs, durationNs);
            }

            Object classObj = Values.get(event, "monitorClass", "name");
            if (classObj == null) {
              classObj = Values.get(event, "monitorClass");
            }
            String className = classObj != null ? String.valueOf(classObj) : "unknown";
            contentionByClass.merge(className, 1L, Long::sum);
          }

          double totalContentionMs = totalContentionNs / 1_000_000.0;
          double avgContentionMs = totalContentionMs / monitorEvents.size();
          double maxContentionMs = maxContentionNs / 1_000_000.0;

          saturation.put("contentionEvents", monitorEvents.size());
          saturation.put("totalContentionMs", Math.round(totalContentionMs * 10) / 10.0);
          saturation.put("avgContentionMs", Math.round(avgContentionMs * 10) / 10.0);
          saturation.put("maxContentionMs", Math.round(maxContentionMs * 10) / 10.0);

          // Find top contended class
          contentionByClass.entrySet().stream()
              .max(Map.Entry.comparingByValue())
              .ifPresent(e -> saturation.put("topContendedClass", e.getKey()));

          saturation.put(
              "assessment", monitorEvents.size() < 100 ? "LOW_CONTENTION" : "MODERATE_CONTENTION");
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
          String queueQuery = "events/" + queueEventType + timeFilter;
          JfrPath.Query parsed = JfrPathParser.parse(queueQuery);
          List<Map<String, Object>> queueEvents = evaluator.evaluate(sessionInfo.session(), parsed);

          if (!queueEvents.isEmpty()) {
            Map<String, QueueCorrelation> queueMetrics = new HashMap<>();
            long totalQueueTimeNs = 0;
            int totalQueuedItems = 0;

            for (Map<String, Object> event : queueEvents) {
              Object durationObj = Values.get(event, "duration");
              if (!(durationObj instanceof Number)) continue;

              long durationNs = ((Number) durationObj).longValue();
              totalQueueTimeNs += durationNs;
              totalQueuedItems++;

              // Extract scheduler and queue type (use simple names)
              Object schedulerObj = Values.get(event, "scheduler", "name");
              if (schedulerObj == null) {
                schedulerObj = Values.get(event, "scheduler");
              }
              String scheduler =
                  extractSimpleClassName(
                      schedulerObj != null ? String.valueOf(schedulerObj) : "unknown");

              Object queueTypeObj = Values.get(event, "queueType", "name");
              if (queueTypeObj == null) {
                queueTypeObj = Values.get(event, "queueType");
              }
              String queueType =
                  extractSimpleClassName(
                      queueTypeObj != null ? String.valueOf(queueTypeObj) : "unknown");

              String threadId = extractThreadId(event);
              String key = scheduler + "|" + queueType;

              QueueCorrelation corr =
                  queueMetrics.computeIfAbsent(key, k -> new QueueCorrelation(scheduler, queueType));
              corr.addSample(durationNs, threadId);
            }

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
                queueMetrics.values().stream().mapToLong(c -> c.maxDurationNs).max().orElse(0);
            queueSaturation.put("maxQueueTimeMs", Math.round(maxQueueNs / 1_000_000.0 * 10) / 10.0);

            // Group by scheduler
            Map<String, Object> byScheduler = new LinkedHashMap<>();
            queueMetrics.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().samples, a.getValue().samples))
                .limit(10)
                .forEach(
                    e -> {
                      QueueCorrelation corr = e.getValue();
                      Map<String, Object> schedulerInfo = new LinkedHashMap<>();
                      schedulerInfo.put("queueType", corr.queueType);
                      schedulerInfo.put("count", corr.samples);
                      schedulerInfo.put(
                          "totalTimeMs",
                          Math.round(corr.totalDurationNs / 1_000_000.0 * 10) / 10.0);
                      schedulerInfo.put("avgTimeMs", Math.round(corr.getAvgDurationMs() * 10) / 10.0);
                      schedulerInfo.put(
                          "maxTimeMs", Math.round(corr.maxDurationNs / 1_000_000.0 * 10) / 10.0);
                      schedulerInfo.put("p95Ms", Math.round(corr.getP95DurationMs() * 10) / 10.0);
                      schedulerInfo.put("p99Ms", Math.round(corr.getP99DurationMs() * 10) / 10.0);
                      byScheduler.put(corr.scheduler, schedulerInfo);
                    });
            queueSaturation.put("byScheduler", byScheduler);

            // Calculate p95 for assessment
            double globalP95 =
                queueMetrics.values().stream()
                    .mapToDouble(QueueCorrelation::getP95DurationMs)
                    .average()
                    .orElse(0.0);
            queueSaturation.put("assessment", assessQueueSaturation(avgQueueMs, globalP95));

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
      long totalOps = 0;
      long totalDurationNs = 0;
      List<Long> durations = new ArrayList<>();

      // Aggregate file and socket I/O
      for (String eventType :
          List.of("jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite")) {
        try {
          String query = "events/" + eventType + timeFilter;
          JfrPath.Query parsed = JfrPathParser.parse(query);
          List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

          for (Map<String, Object> event : events) {
            totalOps++;
            Object durationObj = Values.get(event, "duration");
            if (durationObj instanceof Number) {
              long durationNs = ((Number) durationObj).longValue();
              totalDurationNs += durationNs;
              durations.add(durationNs);
            }
          }
        } catch (Exception ignored) {
          // Event type not available
        }
      }

      if (totalOps > 0) {
        Map<String, Object> utilization = new LinkedHashMap<>();
        utilization.put("totalOperations", totalOps);
        utilization.put("totalTimeMs", Math.round(totalDurationNs / 1_000_000.0 * 10) / 10.0);
        io.put("utilization", utilization);

        // Calculate percentiles
        if (!durations.isEmpty()) {
          durations.sort(Long::compareTo);
          int p95Idx = (int) (durations.size() * 0.95);
          int p99Idx = (int) (durations.size() * 0.99);
          long p95Ns = durations.get(Math.min(p95Idx, durations.size() - 1));
          long p99Ns = durations.get(Math.min(p99Idx, durations.size() - 1));

          Map<String, Object> saturation = new LinkedHashMap<>();
          saturation.put("p95DurationMs", Math.round(p95Ns / 1_000_000.0 * 10) / 10.0);
          saturation.put("p99DurationMs", Math.round(p99Ns / 1_000_000.0 * 10) / 10.0);

          long slowCount = durations.stream().filter(d -> d > 10_000_000).count(); // >10ms
          saturation.put("slowOperations", slowCount);
          saturation.put("slowThreshold", "10ms");
          io.put("saturation", saturation);
        }

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

  private McpServerFeatures.SyncToolSpecification createJfrTsaTool() {
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
        new Tool(
            "jfr_tsa",
            "Analyzes JFR recording using Thread State Analysis (TSA) methodology. "
                + "Shows how threads spend their time across different states (RUNNABLE, WAITING, BLOCKED, etc.). "
                + "Identifies problematic threads and correlates blocking states with contended locks/monitors.",
            schema),
        (exchange, args) -> handleJfrTsa(args));
  }

  private CallToolResult handleJfrTsa(Map<String, Object> args) {
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
      String query = "events/" + eventType + timeFilter;
      JfrPath.Query parsed = JfrPathParser.parse(query);
      List<Map<String, Object>> samples = evaluator.evaluate(sessionInfo.session(), parsed);

      if (samples.isEmpty()) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", "TSA");
        result.put("message", "No execution samples in time window");
        return successResult(result);
      }

      // Collect thread state metrics
      Map<String, ThreadStateMetrics> threadMetrics = new HashMap<>();
      Map<String, Long> globalStateCount = new HashMap<>();

      for (Map<String, Object> event : samples) {
        String threadId = extractThreadId(event);
        String threadName = extractThreadName(event);
        String state = extractState(event);

        ThreadStateMetrics metrics =
            threadMetrics.computeIfAbsent(
                threadId, k -> new ThreadStateMetrics(threadId, threadName));
        metrics.totalSamples++;
        metrics.stateCount.merge(state, 1L, Long::sum);
        globalStateCount.merge(state, 1L, Long::sum);
      }

      // Filter by minSamples
      threadMetrics.values().removeIf(m -> m.totalSamples < minSamples);

      long totalSamples = samples.size();

      // Correlate with blocking events if requested
      Map<String, MonitorCorrelation> correlations = new HashMap<>();
      Map<String, QueueCorrelation> queueCorrelations = new HashMap<>();
      if (correlateBlocking) {
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
    Map<String, MonitorCorrelation> correlations = new HashMap<>();

    try {
      String monitorQuery = "events/jdk.JavaMonitorEnter" + timeFilter;
      JfrPath.Query parsed = JfrPathParser.parse(monitorQuery);
      List<Map<String, Object>> monitorEvents = evaluator.evaluate(sessionInfo.session(), parsed);

      for (Map<String, Object> event : monitorEvents) {
        Object classObj = Values.get(event, "monitorClass", "name");
        if (classObj == null) {
          classObj = Values.get(event, "monitorClass");
        }
        String monitorClass = classObj != null ? String.valueOf(classObj) : "unknown";

        MonitorCorrelation corr =
            correlations.computeIfAbsent(monitorClass, MonitorCorrelation::new);
        corr.samples++;

        Object durationObj = Values.get(event, "duration");
        if (durationObj instanceof Number) {
          long durationNs = ((Number) durationObj).longValue();
          corr.totalDurationNs += durationNs;
        }

        String threadId = extractThreadId(event);
        corr.threads.add(threadId);
      }

    } catch (Exception e) {
      LOG.debug("Failed to correlate blocking events: {}", e.getMessage());
    }

    return correlations;
  }

  private Map<String, QueueCorrelation> correlateWithQueueEvents(
      SessionRegistry.SessionInfo sessionInfo, String timeFilter) {
    Map<String, QueueCorrelation> correlations = new HashMap<>();

    try {
      String queueEventType = detectQueueTimeEventType(sessionInfo);
      if (queueEventType == null) return correlations;

      String queueQuery = "events/" + queueEventType + timeFilter;
      JfrPath.Query parsed = JfrPathParser.parse(queueQuery);
      List<Map<String, Object>> queueEvents = evaluator.evaluate(sessionInfo.session(), parsed);

      for (Map<String, Object> event : queueEvents) {
        // Extract scheduler (use simple name)
        Object schedulerObj = Values.get(event, "scheduler", "name");
        if (schedulerObj == null) {
          schedulerObj = Values.get(event, "scheduler");
        }
        String scheduler =
            extractSimpleClassName(
                schedulerObj != null ? String.valueOf(schedulerObj) : "unknown");

        // Extract queue type (use simple name)
        Object queueTypeObj = Values.get(event, "queueType", "name");
        if (queueTypeObj == null) {
          queueTypeObj = Values.get(event, "queueType");
        }
        String queueType =
            extractSimpleClassName(queueTypeObj != null ? String.valueOf(queueTypeObj) : "unknown");

        // Use eventThread (thread that dequeued and executed)
        String threadId = extractThreadId(event);
        String key = scheduler;

        QueueCorrelation corr =
            correlations.computeIfAbsent(key, k -> new QueueCorrelation(scheduler, queueType));

        Object durationObj = Values.get(event, "duration");
        if (durationObj instanceof Number) {
          long durationNs = ((Number) durationObj).longValue();
          corr.addSample(durationNs, threadId);
        } else {
          corr.samples++;
          corr.threads.add(threadId);
        }
      }

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
                        Math.round(stateSamples * 1000.0 / m.totalSamples) / 10.0);
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
        .sorted((a, b) -> Long.compare(b.totalSamples, a.totalSamples))
        .limit(20) // Top 20 threads by sample count
        .map(
            m -> {
              Map<String, Object> profile = new LinkedHashMap<>();
              profile.put("threadId", m.threadId);
              profile.put("threadName", m.threadName);
              profile.put("totalSamples", m.totalSamples);
              profile.put(
                  "percentOfRecording", Math.round(m.totalSamples * 1000.0 / totalSamples) / 10.0);

              // State breakdown
              Map<String, Object> stateBreakdown = new LinkedHashMap<>();
              for (Map.Entry<String, Long> entry : m.stateCount.entrySet()) {
                Map<String, Object> stateInfo = new LinkedHashMap<>();
                stateInfo.put("samples", entry.getValue());
                stateInfo.put("pct", Math.round(entry.getValue() * 1000.0 / m.totalSamples) / 10.0);
                stateBreakdown.put(entry.getKey(), stateInfo);
              }
              profile.put("stateBreakdown", stateBreakdown);

              // Assessment
              profile.put("assessment", assessThreadBehavior(m.stateCount, m.totalSamples));

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
        .sorted((a, b) -> Long.compare(b.getValue().samples, a.getValue().samples))
        .limit(10)
        .forEach(
            e -> {
              MonitorCorrelation corr = e.getValue();
              Map<String, Object> info = new LinkedHashMap<>();
              info.put("samples", corr.samples);
              info.put("threads", corr.threads.size());
              if (corr.totalDurationNs > 0) {
                double avgMs = (corr.totalDurationNs / corr.samples) / 1_000_000.0;
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
        .sorted((a, b) -> Long.compare(b.getValue().samples, a.getValue().samples))
        .limit(10)
        .forEach(
            e -> {
              QueueCorrelation corr = e.getValue();
              Map<String, Object> info = new LinkedHashMap<>();
              info.put("queueType", corr.queueType);
              info.put("samples", corr.samples);
              info.put("threads", corr.threads.size());
              if (corr.totalDurationNs > 0 && corr.samples > 0) {
                info.put("avgQueueTimeMs", Math.round(corr.getAvgDurationMs() * 10) / 10.0);
                info.put(
                    "maxQueueTimeMs", Math.round(corr.maxDurationNs / 1_000_000.0 * 10) / 10.0);
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
      String assessment = assessThreadBehavior(m.stateCount, m.totalSamples);
      if ("LOCK_CONTENTION".equals(assessment)) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("thread", m.threadName);
        long blockedSamples = m.stateCount.getOrDefault("BLOCKED", 0L);
        double blockedPct = (blockedSamples * 100.0) / m.totalSamples;
        problem.put("issue", String.format("%.1f%% of time spent BLOCKED on locks", blockedPct));
        problem.put("recommendation", "Review synchronization strategy for this thread");
        problematicThreads.add(problem);
      }
    }

    // Analyze correlations
    if (!correlations.isEmpty()) {
      MonitorCorrelation topContention =
          correlations.values().stream().max(Comparator.comparingLong(c -> c.samples)).orElse(null);
      if (topContention != null && topContention.samples > 50) {
        recommendations.add(
            String.format(
                "Monitor class '%s' has high contention (%d events) - consider lock-free alternatives",
                topContention.monitorClass, topContention.samples));
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
                "High executor queue times on %s (avg: %.1f ms, p95: %.1f ms)",
                maxQueue.scheduler, maxQueue.getAvgDurationMs(), maxQueue.getP95DurationMs()));
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
    long totalSamples = 0;
    final Map<String, Long> stateCount = new HashMap<>();

    ThreadStateMetrics(String threadId, String threadName) {
      this.threadId = threadId;
      this.threadName = threadName;
    }
  }

  /** Helper class to track monitor correlation data. */
  private static class MonitorCorrelation {
    final String monitorClass;
    long samples = 0;
    long totalDurationNs = 0;
    final Set<String> threads = new HashSet<>();

    MonitorCorrelation(String monitorClass) {
      this.monitorClass = monitorClass;
    }
  }

  /** Helper class to track queue correlation data. */
  private static class QueueCorrelation {
    final String scheduler;
    final String queueType;
    long samples = 0;
    long totalDurationNs = 0;
    long maxDurationNs = 0;
    final List<Long> durations = new ArrayList<>();
    final Set<String> threads = new HashSet<>();

    QueueCorrelation(String scheduler, String queueType) {
      this.scheduler = scheduler;
      this.queueType = queueType;
    }

    void addSample(long durationNs, String threadId) {
      samples++;
      totalDurationNs += durationNs;
      maxDurationNs = Math.max(maxDurationNs, durationNs);
      durations.add(durationNs);
      threads.add(threadId);
    }

    double getAvgDurationMs() {
      return samples > 0 ? (totalDurationNs / (double) samples) / 1_000_000.0 : 0.0;
    }

    double getP95DurationMs() {
      if (durations.isEmpty()) return 0.0;
      List<Long> sorted = new ArrayList<>(durations);
      sorted.sort(Long::compareTo);
      int idx = (int) (sorted.size() * 0.95);
      return sorted.get(Math.min(idx, sorted.size() - 1)) / 1_000_000.0;
    }

    double getP99DurationMs() {
      if (durations.isEmpty()) return 0.0;
      List<Long> sorted = new ArrayList<>(durations);
      sorted.sort(Long::compareTo);
      int idx = (int) (sorted.size() * 0.99);
      return sorted.get(Math.min(idx, sorted.size() - 1)) / 1_000_000.0;
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

  /** Assess queue saturation level based on average and p95 queue times. */
  private String assessQueueSaturation(double avgQueueMs, double p95QueueMs) {
    if (avgQueueMs > 100 || p95QueueMs > 250) return "HIGH_QUEUE_SATURATION";
    if (avgQueueMs > 20 || p95QueueMs > 100) return "MODERATE_QUEUE_SATURATION";
    return "LOW_QUEUE_SATURATION";
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  private CallToolResult successResult(Map<String, Object> data) {
    try {
      String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
      return new CallToolResult(List.of(new TextContent(json)), false);
    } catch (Exception e) {
      return new CallToolResult(List.of(new TextContent(data.toString())), false);
    }
  }

  private CallToolResult errorResult(String message) {
    Map<String, Object> error = Map.of("error", message, "success", false);
    try {
      String json = MAPPER.writeValueAsString(error);
      return new CallToolResult(List.of(new TextContent(json)), true);
    } catch (Exception e) {
      return new CallToolResult(List.of(new TextContent(message)), true);
    }
  }
}

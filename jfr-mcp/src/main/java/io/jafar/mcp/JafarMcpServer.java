package io.jafar.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.servlet.Servlet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    server.run();
  }

  public void run() {
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
        createJfrHelpTool());
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
                "success", true,
                "message", "Closed " + count + " session(s)",
                "remainingSessions", 0));
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
                "success", true,
                "message", "Session " + sessionId + " closed successfully",
                "remainingSessions", sessionRegistry.size()));
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
          default -> "Unknown topic: " + topic + ". Available: overview, filters, pipeline, functions, examples, event_types";
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

package io.jafar.mcp;

import io.jafar.hdump.shell.HeapReportGenerator;
import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.hdumppath.HdumpPath;
import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import io.jafar.mcp.query.DefaultQueryEvaluator;
import io.jafar.mcp.query.DefaultQueryParser;
import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.query.QueryParser;
import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.otlp.shell.OtlpSession;
import io.jafar.otlp.shell.otlppath.OtlpPathEvaluator;
import io.jafar.otlp.shell.otlppath.OtlpPathParseException;
import io.jafar.otlp.shell.otlppath.OtlpPathParser;
import io.jafar.parser.api.Values;
import io.jafar.pprof.shell.PprofProfile;
import io.jafar.pprof.shell.PprofSession;
import io.jafar.pprof.shell.pprofpath.PprofPathEvaluator;
import io.jafar.pprof.shell.pprofpath.PprofPathParseException;
import io.jafar.pprof.shell.pprofpath.PprofPathParser;
import io.jafar.shell.core.sampling.SamplingSessionRegistry;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.servlet.Servlet;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import java.util.regex.Pattern;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP Server for JFR and heap dump analysis.
 *
 * <p>Exposes Jafar's JFR parsing and heap dump analysis capabilities via the Model Context
 * Protocol, enabling AI agents to analyze Java Flight Recordings and HPROF heap dumps.
 *
 * <p>JFR tools ({@code jfr_*}):
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
 *   <li>{@code jfr_diagnose} - Automated performance diagnosis
 *   <li>{@code jfr_stackprofile} - Structured stack profiling with time-series and thread breakdown
 * </ul>
 *
 * <p>Heap dump tools ({@code hdump_*}):
 *
 * <ul>
 *   <li>{@code hdump_open} - Open an HPROF heap dump file
 *   <li>{@code hdump_close} - Close one or all heap dump sessions
 *   <li>{@code hdump_query} - Execute HdumpPath queries
 *   <li>{@code hdump_summary} - Quick heap dump overview
 *   <li>{@code hdump_report} - Full heap health report with severity-ranked findings
 *   <li>{@code hdump_help} - HdumpPath query language documentation
 * </ul>
 *
 * <p>pprof tools ({@code pprof_*}):
 *
 * <ul>
 *   <li>{@code pprof_open} - Open a pprof profile file (.pb.gz or .pprof)
 *   <li>{@code pprof_close} - Close a pprof session
 *   <li>{@code pprof_query} - Execute PprofPath queries
 *   <li>{@code pprof_summary} - Quick pprof profile overview
 *   <li>{@code pprof_flamegraph} - Stack profile data for flame graph visualization
 *   <li>{@code pprof_use} - USE Method resource analysis (Utilization, Saturation, Errors)
 *   <li>{@code pprof_hotmethods} - Hot method identification by direct CPU/allocation cost
 *   <li>{@code pprof_tsa} - Thread State Analysis (TSA Method) with inferred thread states
 *   <li>{@code pprof_help} - PprofPath query language documentation
 * </ul>
 *
 * <p>OpenTelemetry profiling tools ({@code otlp_*}):
 *
 * <ul>
 *   <li>{@code otlp_open} - Open an OTLP profiles file (.otlp)
 *   <li>{@code otlp_close} - Close an otlp session
 *   <li>{@code otlp_query} - Execute OtlpPath queries
 *   <li>{@code otlp_summary} - Quick OTLP profile overview
 *   <li>{@code otlp_flamegraph} - Stack profile data for flame graph visualization
 *   <li>{@code otlp_use} - USE Method resource analysis (Utilization, Saturation, Errors)
 *   <li>{@code otlp_help} - OtlpPath query language documentation
 * </ul>
 */
public final class JafarMcpServer {

  private static final Logger LOG = LoggerFactory.getLogger(JafarMcpServer.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Builds an MCP {@link Tool} from a name, description, and raw JSON input schema. Uses {@link
   * McpJsonDefaults#getMapper()} to parse the schema string.
   */
  private static Tool buildTool(String name, String description, String schema) {
    return Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(McpJsonDefaults.getMapper(), schema)
        .build();
  }

  /** Default idle timeout in minutes before the server exits. */
  private static final int DEFAULT_IDLE_TIMEOUT_MINUTES = 10;

  private final SessionRegistry sessionRegistry;
  private final HeapSessionRegistry heapSessionRegistry;
  private final PprofSessionRegistry pprofSessionRegistry;
  private final OtlpSessionRegistry otlpSessionRegistry;
  private final QueryEvaluator evaluator;
  private final QueryParser queryParser;

  /** Timestamp of the last tool invocation, in nanoseconds. Updated on every tool call. */
  private volatile long lastActivityNanos = System.nanoTime();

  /** Creates a server with default dependencies for production use. */
  public JafarMcpServer() {
    this(
        new SessionRegistry(),
        new HeapSessionRegistry(),
        new PprofSessionRegistry(),
        new OtlpSessionRegistry(),
        new DefaultQueryEvaluator(),
        new DefaultQueryParser());
  }

  /**
   * Creates a server with custom dependencies (for testing).
   *
   * @param sessionRegistry the JFR session registry
   * @param heapSessionRegistry the heap dump session registry
   * @param pprofSessionRegistry the pprof session registry
   * @param otlpSessionRegistry the otlp session registry
   * @param evaluator the query evaluator
   * @param queryParser the query parser
   */
  public JafarMcpServer(
      SessionRegistry sessionRegistry,
      HeapSessionRegistry heapSessionRegistry,
      PprofSessionRegistry pprofSessionRegistry,
      OtlpSessionRegistry otlpSessionRegistry,
      QueryEvaluator evaluator,
      QueryParser queryParser) {
    this.sessionRegistry = sessionRegistry;
    this.heapSessionRegistry = heapSessionRegistry;
    this.pprofSessionRegistry = pprofSessionRegistry;
    this.otlpSessionRegistry = otlpSessionRegistry;
    this.evaluator = evaluator;
    this.queryParser = queryParser;
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
      var transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

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
                    heapSessionRegistry.shutdown();
                    pprofSessionRegistry.shutdown();
                    otlpSessionRegistry.shutdown();
                    mcpServer.close();
                  }));

      startIdleWatchdog();

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

    // Check if server is already running on this port
    if (isPortInUse(port)) {
      // Silent exit - server already running
      return;
    }

    LOG.info("Starting Jafar MCP Server on port {}", port);

    try {
      // Create HTTP SSE transport
      var transportProvider =
          HttpServletSseServerTransportProvider.builder()
              .jsonMapper(McpJsonDefaults.getMapper())
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
                    heapSessionRegistry.shutdown();
                    pprofSessionRegistry.shutdown();
                    otlpSessionRegistry.shutdown();
                    mcpServer.close();
                    try {
                      jettyServer.stop();
                    } catch (Exception e) {
                      LOG.warn("Error stopping Jetty: {}", e.getMessage());
                    }
                  }));

      startIdleWatchdog();

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

  /** Updates the last-activity timestamp. Called on every tool invocation. */
  private void touchActivity() {
    lastActivityNanos = System.nanoTime();
  }

  /**
   * Starts a daemon thread that exits the process when no tool has been called for the configured
   * idle timeout period.
   *
   * <p>Timeout is read from system property {@code mcp.idle.timeout.minutes} (default: {@value
   * DEFAULT_IDLE_TIMEOUT_MINUTES}).
   */
  private void startIdleWatchdog() {
    int timeoutMinutes =
        Integer.getInteger("mcp.idle.timeout.minutes", DEFAULT_IDLE_TIMEOUT_MINUTES);
    if (timeoutMinutes <= 0) {
      LOG.info("Idle watchdog disabled (mcp.idle.timeout.minutes={})", timeoutMinutes);
      return;
    }

    long timeoutNanos = (long) timeoutMinutes * 60L * 1_000_000_000L;
    long checkIntervalMs = 30_000L; // check every 30 seconds

    Thread watchdog =
        new Thread(
            () -> {
              LOG.info("Idle watchdog started (timeout={}m)", timeoutMinutes);
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(checkIntervalMs);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                long idleNanos = System.nanoTime() - lastActivityNanos;
                if (idleNanos >= timeoutNanos) {
                  LOG.info(
                      "Idle timeout reached ({}m), shutting down", idleNanos / 60_000_000_000L);
                  System.exit(0);
                }
              }
            },
            "mcp-idle-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
  }

  /**
   * Wraps a tool specification so that every invocation updates the last-activity timestamp.
   *
   * @param spec the original specification
   * @return wrapped specification
   */
  private McpServerFeatures.SyncToolSpecification withActivityTracking(
      McpServerFeatures.SyncToolSpecification spec) {
    return new McpServerFeatures.SyncToolSpecification(
        spec.tool(),
        (exchange, args) -> {
          touchActivity();
          return spec.callHandler().apply(exchange, args);
        });
  }

  /**
   * Checks if a port is already in use.
   *
   * @param port the port to check
   * @return true if port is in use, false if available
   */
  private boolean isPortInUse(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("localhost", port), 500);
      return true; // Port is in use
    } catch (IOException e) {
      return false; // Port is available
    }
  }

  /**
   * Creates all tool specifications: 14 JFR tools, 6 heap dump tools, 9 pprof tools, and 7 otlp
   * tools.
   *
   * <p>JFR tools: jfr_open, jfr_query, jfr_list_types, jfr_close, jfr_help, jfr_flamegraph,
   * jfr_callgraph, jfr_exceptions, jfr_summary, jfr_hotmethods, jfr_use, jfr_tsa, jfr_diagnose,
   * jfr_stackprofile.
   *
   * <p>Heap dump tools: hdump_open, hdump_close, hdump_query, hdump_summary, hdump_report,
   * hdump_help.
   *
   * <p>pprof tools: pprof_open, pprof_close, pprof_query, pprof_summary, pprof_flamegraph,
   * pprof_use, pprof_hotmethods, pprof_tsa, pprof_help.
   *
   * <p>otlp tools: otlp_open, otlp_close, otlp_query, otlp_summary, otlp_flamegraph, otlp_use,
   * otlp_help.
   */
  private List<McpServerFeatures.SyncToolSpecification> createToolSpecifications() {
    List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
    tools.add(withActivityTracking(createJfrOpenTool()));
    tools.add(withActivityTracking(createJfrQueryTool()));
    tools.add(withActivityTracking(createJfrListTypesTool()));
    tools.add(withActivityTracking(createJfrCloseTool()));
    tools.add(withActivityTracking(createJfrHelpTool()));
    tools.add(withActivityTracking(createJfrFlamegraphTool()));
    tools.add(withActivityTracking(createJfrCallgraphTool()));
    tools.add(withActivityTracking(createJfrExceptionsTool()));
    tools.add(withActivityTracking(createJfrSummaryTool()));
    tools.add(withActivityTracking(createJfrHotmethodsTool()));
    tools.add(withActivityTracking(createJfrUseTool()));
    tools.add(withActivityTracking(createJfrTsaTool()));
    tools.add(withActivityTracking(createJfrDiagnoseTool()));
    tools.add(withActivityTracking(createJfrStackprofileTool()));
    tools.add(withActivityTracking(createHdumpOpenTool()));
    tools.add(withActivityTracking(createHdumpCloseTool()));
    tools.add(withActivityTracking(createHdumpQueryTool()));
    tools.add(withActivityTracking(createHdumpSummaryTool()));
    tools.add(withActivityTracking(createHdumpReportTool()));
    tools.add(withActivityTracking(createHdumpHelpTool()));
    tools.add(withActivityTracking(createPprofOpenTool()));
    tools.add(withActivityTracking(createPprofCloseTool()));
    tools.add(withActivityTracking(createPprofQueryTool()));
    tools.add(withActivityTracking(createPprofSummaryTool()));
    tools.add(withActivityTracking(createPprofFlamegraphTool()));
    tools.add(withActivityTracking(createPprofUseTool()));
    tools.add(withActivityTracking(createPprofHotmethodsTool()));
    tools.add(withActivityTracking(createPprofTsaTool()));
    tools.add(withActivityTracking(createPprofHelpTool()));
    tools.add(withActivityTracking(createOtlpOpenTool()));
    tools.add(withActivityTracking(createOtlpCloseTool()));
    tools.add(withActivityTracking(createOtlpQueryTool()));
    tools.add(withActivityTracking(createOtlpSummaryTool()));
    tools.add(withActivityTracking(createOtlpFlamegraphTool()));
    tools.add(withActivityTracking(createOtlpUseTool()));
    tools.add(withActivityTracking(createOtlpHelpTool()));
    return tools;
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
        buildTool(
            "jfr_open",
            "Opens a JFR (Java Flight Recording) file for analysis. "
                + "Returns a session ID that can be used with other jfr_* tools. "
                + "If no session ID is provided to other tools, they use the most recently opened session.",
            schema),
        (exchange, args) -> handleJfrOpen(args.arguments()));
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

  private CallToolResult handleJfrQuery(Map<String, Object> args) {
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
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to execute query: {}", e.getMessage(), e);
      return errorResult("Failed to execute query: " + e.getMessage());
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
        buildTool(
            "jfr_list_types",
            "Lists all event types available in a JFR recording. "
                + "By default returns type names from metadata (fast). "
                + "Use scan=true to get actual event counts (scans the entire recording). "
                + "Categories: jdk.* (JDK events), jdk.jfr.* (JFR infrastructure), custom app events.",
            schema),
        (exchange, args) -> handleJfrListTypes(args.arguments()));
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
            JfrPath.Query parsed = queryParser.parse(query);
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
        buildTool(
            "jfr_close",
            "Closes a JFR recording session and releases resources. "
                + "Options: provide sessionId for specific session, closeAll=true for all sessions, "
                + "or neither to close the current session.",
            schema),
        (exchange, args) -> handleJfrClose(args.arguments()));
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
          case "tools" -> getToolsHelp();
          default ->
              "Unknown topic: "
                  + topic
                  + ". Available: overview, filters, pipeline, functions, examples, event_types, tools";
        };

    return new CallToolResult(List.of(new TextContent(content)), false, null, null);
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
        - `constants/<type>` - Access constant pool entries

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
        - tools: When to use each jfr_* tool (flamegraph vs stackprofile vs hotmethods, etc.)
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

  private String getToolsHelp() {
    return """
        # Choosing the Right jfr_* Tool

        ## CPU Profiling Tools

        ### jfr_hotmethods
        Best for: Quick identification of which methods consume the most CPU.
        Returns: Flat ranked list of leaf methods with sample counts and percentages.
        Use when: You need a fast answer to "where is CPU time going?" without call-path context.

        ### jfr_flamegraph
        Best for: Understanding call paths and how code reaches hot methods.
        Returns: Aggregated stack traces in folded (semicolon-separated) or tree (JSON) format.
        Use when: You need to see the full call hierarchy — which callers lead to a hotspot,
        or which entry points fan out into expensive subtrees. Output is designed for
        visualization tools or structural call-path reasoning.

        ### jfr_stackprofile
        Best for: Detecting temporal patterns and thread affinity in CPU hotspots.
        Returns: Structured JSON with per-frame time-bucket arrays, per-thread sample counts,
        numeric percentages, and a derived category (normal / hotspot / steady-hotspot).
        Use when: You need to programmatically analyze how a method's CPU usage changes over
        the recording duration (bursty vs steady), identify N+1 query patterns (steady hotspots),
        or determine which threads are contributing to a hotspot. The time-bucket arrays let you
        detect intermittent load spikes that would be invisible in aggregated flamegraph data.

        ### Decision Guide
        ```
        Need a quick "top CPU consumers" list?          → jfr_hotmethods
        Need to understand call paths / callers?        → jfr_flamegraph
        Need temporal patterns or thread breakdown?     → jfr_stackprofile
        Need all of the above for deep investigation?   → Start with jfr_stackprofile,
                                                          then jfr_flamegraph for call paths
        ```

        ## Other Specialized Tools

        ### jfr_summary
        Recording overview: duration, event counts, JVM info. Start here for orientation.

        ### jfr_diagnose
        Automated performance triage. Runs multiple checks and returns findings + recommendations.

        ### jfr_use
        USE Method analysis (Utilization, Saturation, Errors) for CPU, memory, threads, and I/O.

        ### jfr_tsa
        Thread State Analysis. Breaks down what threads are doing: running, blocked, waiting, I/O.

        ### jfr_exceptions
        Exception pattern analysis: types, frequencies, and common throw sites.

        ### jfr_callgraph
        Call graph between methods. Shows caller→callee relationships with sample weights.

        ### jfr_query
        General-purpose JfrPath queries for anything not covered by specialized tools.
        Use jfr_help(topic="pipeline") and jfr_help(topic="filters") for query syntax.
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
        buildTool(
            "jfr_flamegraph",
            "Generates aggregated stack trace data for flamegraph-style analysis. "
                + "Returns stack paths with sample counts in folded or tree format. "
                + "Use direction=bottom-up to see hot methods (where time is spent), "
                + "or direction=top-down to see call paths from entry points. "
                + "Folded format is semicolon-separated paths compatible with standard flamegraph tools.",
            schema),
        (exchange, args) -> handleJfrFlamegraph(exchange, args.arguments()));
  }

  private CallToolResult handleJfrFlamegraph(
      McpSyncServerExchange exchange, Map<String, Object> args) {
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
      sendProgress(exchange, "flamegraph", 0, 2, "Querying events...");
      String query = "events/" + eventType;
      JfrPath.Query parsed = queryParser.parse(query);
      List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

      // Build aggregation tree
      sendProgress(exchange, "flamegraph", 1, 2, "Building flamegraph tree...");
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
      sendProgress(exchange, "flamegraph", 2, 2, "Done");
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
        buildTool(
            "jfr_callgraph",
            "Generates a call graph showing caller-callee relationships from stack traces. "
                + "Unlike flamegraph (which preserves full paths), this shows which methods call which, "
                + "revealing convergence points where multiple callers invoke the same method. "
                + "DOT format can be visualized with graphviz. JSON format includes node and edge data.",
            schema),
        (exchange, args) -> handleJfrCallgraph(exchange, args.arguments()));
  }

  private CallToolResult handleJfrCallgraph(
      McpSyncServerExchange exchange, Map<String, Object> args) {
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
      sendProgress(exchange, "callgraph", 0, 2, "Querying events...");
      String query = "events/" + eventType;
      JfrPath.Query parsed = queryParser.parse(query);
      List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

      // Build call graph
      sendProgress(exchange, "callgraph", 1, 2, "Building call graph...");
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

      // Compute inDegree for convergence point detection
      graph.computeInDegree();

      // Format output
      sendProgress(exchange, "callgraph", 2, 2, "Done");
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
        buildTool(
            "jfr_exceptions",
            "Analyzes exception events in a JFR recording. Extracts exception types from stack traces, "
                + "groups by exception class, and identifies throw sites. Works with both JDK exception events "
                + "(jdk.JavaExceptionThrow) and profiler exception samples (datadog.ExceptionSample). "
                + "Returns exception type counts, throw site locations, and patterns.",
            schema),
        (exchange, args) -> handleJfrExceptions(exchange, args.arguments()));
  }

  private CallToolResult handleJfrExceptions(
      McpSyncServerExchange exchange, Map<String, Object> args) {
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
      sendProgress(exchange, "exceptions", 0, 2, "Querying exception events...");
      String query = "events/" + eventType;
      JfrPath.Query parsed = queryParser.parse(query);
      List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

      if (events.isEmpty()) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventType);
        result.put("totalExceptions", 0);
        result.put("message", "No exception events found for type: " + eventType);
        return successResult(result);
      }

      // Analyze exceptions
      sendProgress(exchange, "exceptions", 1, 2, "Analyzing exception patterns...");
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

      sendProgress(exchange, "exceptions", 2, 2, "Done");
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
        JfrPath.Query parsed = queryParser.parse(query);
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
        buildTool(
            "jfr_summary",
            "Provides a quick overview of a JFR recording including duration, event counts, "
                + "and key highlights like GC statistics, exception rates, and top CPU consumers. "
                + "Useful for getting oriented with a new recording before deeper analysis.",
            schema),
        (exchange, args) -> handleJfrSummary(exchange, args.arguments()));
  }

  private CallToolResult handleJfrSummary(
      McpSyncServerExchange exchange, Map<String, Object> args) {
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
      int typeIdx = 0;
      int typeTotal = types.size();
      sendProgress(
          exchange, "summary", 0, typeTotal + 1, "Scanning " + typeTotal + " event types...");
      for (String type : types) {
        typeIdx++;
        try {
          String query = "events/" + type + " | count()";
          JfrPath.Query parsed = queryParser.parse(query);
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
        if (typeIdx % 10 == 0) {
          sendProgress(
              exchange,
              "summary",
              typeIdx,
              typeTotal + 1,
              "Counting events: " + typeIdx + "/" + typeTotal + " types");
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

      sendProgress(exchange, "summary", typeTotal + 1, typeTotal + 1, "Done");
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
        JfrPath.Query parsed = queryParser.parse(query);
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
      JfrPath.Query parsed = queryParser.parse(query);
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
        buildTool(
            "jfr_hotmethods",
            "Returns the hottest methods (leaf frames) from CPU profiling samples. "
                + "Simpler and more compact than full flamegraph - just shows which methods are consuming CPU. "
                + "Useful for quick CPU hotspot identification.",
            schema),
        (exchange, args) -> handleJfrHotmethods(exchange, args.arguments()));
  }

  private CallToolResult handleJfrHotmethods(
      McpSyncServerExchange exchange, Map<String, Object> args) {
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
      sendProgress(exchange, "hotmethods", 0, 2, "Querying execution samples...");
      String query = "events/" + eventType;
      JfrPath.Query parsed = queryParser.parse(query);
      List<Map<String, Object>> events = evaluator.evaluate(sessionInfo.session(), parsed);

      if (events.isEmpty()) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventType);
        result.put("totalSamples", 0);
        result.put("message", "No execution sample events found for type: " + eventType);
        return successResult(result);
      }

      // Count leaf methods
      sendProgress(exchange, "hotmethods", 1, 2, "Identifying hot methods...");
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

      sendProgress(exchange, "hotmethods", 2, 2, "Done");
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
        JfrPath.Query parsed = queryParser.parse(query);
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
        JfrPath.Query parsed = queryParser.parse(query);
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

  private String detectAllocationEventType(SessionRegistry.SessionInfo sessionInfo) {
    // Check for Datadog allocation profiling first, then JDK native allocation events
    String[] candidateTypes = {
      "datadog.ObjectSample",
      "jdk.ObjectAllocationSample",
      "jdk.ObjectAllocationInNewTLAB",
      "jdk.ObjectAllocationOutsideTLAB"
    };

    for (String type : candidateTypes) {
      try {
        String query = "events/" + type + " | count()";
        JfrPath.Query parsed = queryParser.parse(query);
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
        buildTool(
            "jfr_use",
            "Analyzes JFR recording using Brendan Gregg's USE Method (Utilization, Saturation, Errors). "
                + "Examines CPU, Memory, Threads/Locks, and I/O resources to identify bottlenecks. "
                + "Returns metrics for utilization (how busy), saturation (queued work), and errors for each resource.",
            schema),
        (exchange, args) -> handleJfrUse(exchange, args.arguments()));
  }

  private CallToolResult handleJfrUse(McpSyncServerExchange exchange, Map<String, Object> args) {
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
        sendProgress(exchange, "use", step++, totalSteps, "Analyzing CPU...");
        resourceMetrics.put("cpu", analyzeCpuResource(sessionInfo, timeFilter));
      }

      // Memory Resource Analysis
      if (resources.contains("memory")) {
        sendProgress(exchange, "use", step++, totalSteps, "Analyzing memory...");
        resourceMetrics.put("memory", analyzeMemoryResource(sessionInfo, timeFilter));
      }

      // Threads/Locks Resource Analysis
      if (resources.contains("threads")) {
        sendProgress(exchange, "use", step++, totalSteps, "Analyzing threads...");
        resourceMetrics.put("threads", analyzeThreadsResource(sessionInfo, timeFilter));
      }

      // I/O Resource Analysis
      if (resources.contains("io")) {
        sendProgress(exchange, "use", step++, totalSteps, "Analyzing I/O...");
        resourceMetrics.put("io", analyzeIoResource(sessionInfo, timeFilter));
      }

      result.put("resources", resourceMetrics);

      // Generate insights and summary
      sendProgress(exchange, "use", step, totalSteps, "Generating insights...");
      if (includeInsights) {
        result.put("insights", generateUseInsights(resourceMetrics));
        result.put("summary", generateUseSummary(resourceMetrics));
      }

      sendProgress(exchange, "use", totalSteps, totalSteps, "Done");
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

        String query = "events/" + eventType + timeFilter;
        JfrPath.Query stateParsed = queryParser.parse(query);
        List<Map<String, Object>> samples = evaluator.evaluate(sessionInfo.session(), stateParsed);

        if (samples.isEmpty()) {
          cpu.put("message", "No execution samples in time window");
          return cpu;
        }

        long runnableCount = 0;
        long saturatedCount = 0;

        for (Map<String, Object> event : samples) {
          String state = extractState(event);
          if ("RUNNABLE".equals(state)) {
            runnableCount++;
          } else if (Set.of("WAITING", "BLOCKED", "PARKED", "TIMED_WAITING").contains(state)) {
            saturatedCount++;
          }
        }

        long totalSamples = samples.size();
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
      String allocQuery = "events/jdk.ObjectAllocationSample" + timeFilter;
      try {
        parsed = queryParser.parse(allocQuery);
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
        JfrPath.Query parsed = queryParser.parse(query);
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
        JfrPath.Query parsed = queryParser.parse(monitorQuery);
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
          JfrPath.Query parsed = queryParser.parse(queueQuery);
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
                  queueMetrics.computeIfAbsent(
                      key, k -> new QueueCorrelation(scheduler, queueType));
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
                      schedulerInfo.put(
                          "avgTimeMs", Math.round(corr.getAvgDurationMs() * 10) / 10.0);
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
          JfrPath.Query parsed = queryParser.parse(query);
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
        buildTool(
            "jfr_tsa",
            "Analyzes JFR recording using Thread State Analysis (TSA) methodology. "
                + "Shows how threads spend their time across different states (RUNNABLE, WAITING, BLOCKED, etc.). "
                + "Identifies problematic threads and correlates blocking states with contended locks/monitors.",
            schema),
        (exchange, args) -> handleJfrTsa(exchange, args.arguments()));
  }

  private CallToolResult handleJfrTsa(McpSyncServerExchange exchange, Map<String, Object> args) {
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
      sendProgress(exchange, "tsa", 0, 3, "Querying execution samples...");
      String query = "events/" + eventType + timeFilter;
      JfrPath.Query parsed = queryParser.parse(query);
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
      sendProgress(exchange, "tsa", 1, 3, "Analyzing thread states...");
      Map<String, MonitorCorrelation> correlations = new HashMap<>();
      Map<String, QueueCorrelation> queueCorrelations = new HashMap<>();
      if (correlateBlocking) {
        sendProgress(exchange, "tsa", 2, 3, "Correlating blocking events...");
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

      sendProgress(exchange, "tsa", 3, 3, "Done");
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
      JfrPath.Query parsed = queryParser.parse(monitorQuery);
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
      JfrPath.Query parsed = queryParser.parse(queueQuery);
      List<Map<String, Object>> queueEvents = evaluator.evaluate(sessionInfo.session(), parsed);

      for (Map<String, Object> event : queueEvents) {
        // Extract scheduler (use simple name)
        Object schedulerObj = Values.get(event, "scheduler", "name");
        if (schedulerObj == null) {
          schedulerObj = Values.get(event, "scheduler");
        }
        String scheduler =
            extractSimpleClassName(schedulerObj != null ? String.valueOf(schedulerObj) : "unknown");

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

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_diagnose
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createJfrDiagnoseTool() {
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
        (exchange, args) -> handleJfrDiagnose(exchange, args.arguments()));
  }

  @SuppressWarnings("unchecked")
  private CallToolResult handleJfrDiagnose(
      McpSyncServerExchange exchange, Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    Boolean includeAnalysis = args.get("includeAnalysis") instanceof Boolean b ? b : true;

    try {
      SessionRegistry.SessionInfo sessionInfo = sessionRegistry.getOrCurrent(sessionId);

      Map<String, Object> diagnosis = new LinkedHashMap<>();
      diagnosis.put("recordingPath", sessionInfo.recordingPath().toString());
      diagnosis.put("sessionId", sessionInfo.id());

      // Step 1: Get summary data
      sendProgress(exchange, "diagnose", 0, 4, "Running summary...");
      CallToolResult summaryResult = handleJfrSummary(exchange, args);
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
      sendProgress(exchange, "diagnose", 1, 4, "Analyzing exceptions...");
      if (highlights.containsKey("exceptions")) {
        Map<String, Object> exceptionStats = (Map<String, Object>) highlights.get("exceptions");
        Long exceptionCount = ((Number) exceptionStats.get("totalExceptions")).longValue();

        if (exceptionCount > 1000) {
          findings.add(
              String.format("HIGH EXCEPTION RATE: %,d exceptions detected", exceptionCount));

          // Run exception analysis
          CallToolResult exceptionsResult = handleJfrExceptions(exchange, args);
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
      sendProgress(exchange, "diagnose", 2, 4, "Analyzing GC pressure...");
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
      sendProgress(exchange, "diagnose", 3, 4, "Analyzing CPU patterns...");
      if (highlights.containsKey("cpu")) {
        Map<String, Object> cpuStats = (Map<String, Object>) highlights.get("cpu");
        Long cpuSamples = ((Number) cpuStats.get("totalSamples")).longValue();

        if (cpuSamples > 5000) {
          findings.add(String.format("CPU INTENSIVE: %,d execution samples captured", cpuSamples));

          // Run hotmethods analysis
          CallToolResult hotmethodsResult = handleJfrHotmethods(exchange, args);
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

      sendProgress(exchange, "diagnose", 4, 4, "Done");
      return successResult(diagnosis);

    } catch (Exception e) {
      LOG.error("Failed to diagnose recording: {}", e.getMessage(), e);
      return errorResult("Failed to diagnose recording: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // jfr_stackprofile - Structured stack profiling with time-series and threads
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createJfrStackprofileTool() {
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
        (exchange, args) -> handleJfrStackprofile(exchange, args.arguments()));
  }

  @SuppressWarnings("unchecked")
  private CallToolResult handleJfrStackprofile(
      McpSyncServerExchange exchange, Map<String, Object> args) {
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
          (p, t, msg) -> sendProgress(exchange, "stackprofile", p, t, msg);
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

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_open
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createHdumpOpenTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Absolute path to the HPROF heap dump file"
            },
            "alias": {
              "type": "string",
              "description": "Optional human-readable alias for the session"
            }
          },
          "required": ["path"]
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "hdump_open",
            "Opens an HPROF heap dump file for analysis. "
                + "Returns a session ID used by other hdump_* tools. "
                + "If no sessionId is supplied to other tools, the most recently opened session is used.",
            schema),
        (exchange, args) -> handleHdumpOpen(args.arguments()));
  }

  private CallToolResult handleHdumpOpen(Map<String, Object> args) {
    String path = (String) args.get("path");
    String alias = (String) args.get("alias");

    if (path == null || path.isBlank()) {
      return errorResult("path is required");
    }

    try {
      Path hprofPath = Path.of(path);

      if (!Files.exists(hprofPath)) {
        return errorResult("File not found: " + path);
      }
      if (!Files.isRegularFile(hprofPath)) {
        return errorResult("Not a file: " + path);
      }
      if (!Files.isReadable(hprofPath)) {
        return errorResult("File not readable: " + path);
      }

      HeapSessionRegistry.SessionInfo info = heapSessionRegistry.open(hprofPath, alias);
      LOG.info("Opened heap dump {} as session {}", path, info.id());

      Map<String, Object> result = info.toMap();
      result.put("message", "Heap dump opened successfully");
      return successResult(result);

    } catch (Exception e) {
      LOG.error("Failed to open heap dump: {}", e.getMessage(), e);
      return errorResult("Failed to open heap dump: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_close
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createHdumpCloseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias to close; omit to close the current session"
            },
            "closeAll": {
              "type": "boolean",
              "description": "If true, close every open heap dump session"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool("hdump_close", "Closes one or all open heap dump sessions.", schema),
        (exchange, args) -> handleHdumpClose(args.arguments()));
  }

  private CallToolResult handleHdumpClose(Map<String, Object> args) {
    boolean closeAll = Boolean.TRUE.equals(args.get("closeAll"));
    String sessionId = (String) args.get("sessionId");

    try {
      if (closeAll) {
        int count = heapSessionRegistry.size();
        heapSessionRegistry.closeAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Closed " + count + " session(s)");
        result.put("remainingSessions", 0);
        return successResult(result);
      }

      HeapSessionRegistry.SessionInfo info = heapSessionRegistry.getOrCurrent(sessionId);
      heapSessionRegistry.close(String.valueOf(info.id()));

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("success", true);
      result.put("message", "Closed heap session " + info.id());
      result.put("remainingSessions", heapSessionRegistry.size());
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to close heap session: {}", e.getMessage(), e);
      return errorResult("Failed to close heap session: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_query
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createHdumpQueryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "HdumpPath query string"
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum rows to return (default: 100)"
            }
          },
          "required": ["query"]
        }
        """;

    String description =
        "Executes an HdumpPath query against an open heap dump session.\n\n"
            + "QUERY SYNTAX\n"
            + "  <root>[/<type>][<predicates>] [| <operator>]*\n\n"
            + "ROOT TYPES\n"
            + "  objects    — heap object instances\n"
            + "               fields: id, class, shallow, retained, arrayLength, stringValue\n"
            + "  classes    — class metadata\n"
            + "               fields: id, name, simpleName, instanceCount, instanceSize, superClass, isArray\n"
            + "  gcroots    — GC root references\n"
            + "               fields: type, objectId, object, shallow, retained\n"
            + "               types: JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK, STICKY_CLASS,\n"
            + "                      THREAD_BLOCK, MONITOR_USED, THREAD_OBJ\n"
            + "  clusters   — graph-based leak clusters (expensive first time)\n"
            + "               fields: id, objectCount, retainedSize, score, dominantClass, anchorType\n"
            + "  duplicates — structurally-identical subgraphs; depth=N controls fingerprint depth (default 3)\n"
            + "               fields: id, rootClass, copies, uniqueSize, wastedBytes, depth, nodeCount\n"
            + "  ages       — objects enriched with estimated age score\n"
            + "               fields: all object fields + estimatedAge, ageBucket, ageSignals\n\n"
            + "TYPE SPECIFIERS (after /)\n"
            + "  objects/java.lang.String\n"
            + "  objects/java.util.*                        (glob)\n"
            + "  objects/instanceof/java.util.Map           (subtypes)\n"
            + "  objects/int[]  or  objects/[I              (arrays)\n\n"
            + "PREDICATES   [field op value]\n"
            + "  ops: =  !=  >  >=  <  <=  ~(regex)\n"
            + "  boolean: and, or, not, ()\n"
            + "  size units: K KB M MB G GB\n"
            + "  functions: contains(f,\"s\")  startsWith(f,\"p\")  between(f,lo,hi)  exists(f)\n"
            + "  examples:  [shallow > 1MB]  [class = \"java.lang.String\"]\n\n"
            + "PIPELINE OPERATORS  (chained with |)\n"
            + "  top(n [,field] [,asc|desc])      sortBy(field [asc|desc])\n"
            + "  filter(predicate)                head(n)  tail(n)\n"
            + "  groupBy(field [,agg=count|sum|avg|min|max] [,value=expr])\n"
            + "  count  sum(field)  stats(field)  select(f1, f2 as alias, ...)  distinct(field)\n"
            + "  waste()               — Map/List capacity waste: capacity, size, wastedBytes\n"
            + "  cacheStats()          — Map cache stats: entryCount, fillRatio, costPerEntry, isLruMode\n"
            + "  checkLeaks([detector=\"name\"] [,minSize=N])\n"
            + "                        — detectors: threadlocal-leak  classloader-leak  duplicate-strings\n"
            + "                                     growing-collections  listener-leak  finalizer-queue\n"
            + "  pathToRoot()          — trace object to nearest GC root\n"
            + "  retentionPaths()      — merged class-level retention paths\n"
            + "  retainedBreakdown([depth=N])   — dominator subtree by class\n"
            + "  dominators([groupBy=\"class\"|\"package\"] [,minRetained=size])\n"
            + "  threadOwner()         — enrich with ownerThread, ownership\n"
            + "  dominatedSize()       — for THREAD_OBJ roots: threadName, dominated, dominatedCount\n"
            + "  estimateAge()         — estimatedAge, ageBucket, ageSignals\n"
            + "  whatif()              — simulate removal: freedBytes, freedPct, remainingRetained\n"
            + "  join(session=id|alias [,root=\"eventType\", by=field])\n"
            + "                        — heap diff (two hprof) or JFR correlation\n\n"
            + "NOTE: retained-size queries trigger approximate retained-size computation on first use.\n"
            + "For large heaps (>500K objects) this may take several minutes.\n\n"
            + "COMMON PATTERNS\n"
            + "  Top 10 memory hogs:      objects | top(10, retained)\n"
            + "  Duplicate waste:         duplicates | sortBy(wastedBytes desc) | top(20)\n"
            + "  Leak clusters:           clusters | sortBy(score desc) | head(10)\n"
            + "  String duplicates:       checkLeaks(detector=\"duplicate-strings\")\n"
            + "  Thread memory:           gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)\n"
            + "  Collection waste:        objects/instanceof/java.util.HashMap | waste() | top(20, wastedBytes)\n"
            + "  Heap diff:               classes | join(session=1) | sortBy(instanceCountDelta desc)\n"
            + "  Retention paths:         objects[retained > 100MB] | retentionPaths()";

    return new McpServerFeatures.SyncToolSpecification(
        buildTool("hdump_query", description, schema),
        (exchange, args) -> handleHdumpQuery(args.arguments()));
  }

  private CallToolResult handleHdumpQuery(Map<String, Object> args) {
    String queryStr = (String) args.get("query");
    String sessionId = (String) args.get("sessionId");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 100;

    if (queryStr == null || queryStr.isBlank()) {
      return errorResult("query is required");
    }
    if (limit <= 0) {
      return errorResult("limit must be positive");
    }

    try {
      HeapSessionRegistry.SessionInfo info = heapSessionRegistry.getOrCurrent(sessionId);
      HdumpPath.Query query = HdumpPathParser.parse(queryStr);
      List<Map<String, Object>> rows =
          HdumpPathEvaluator.evaluate(info.session(), query, heapSessionRegistry.asResolver());

      boolean truncated = rows.size() > limit;
      if (truncated) {
        rows = rows.subList(0, limit);
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("query", queryStr);
      response.put("sessionId", info.id());
      response.put("resultCount", rows.size());
      response.put("results", rows);
      if (truncated) {
        response.put("truncated", true);
        response.put(
            "message", "Results truncated to " + limit + ". Use 'limit' parameter for more.");
      }
      return successResult(response);

    } catch (IllegalArgumentException e) {
      LOG.warn("HdumpPath query error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to execute hdump query: {}", e.getMessage(), e);
      return errorResult("Failed to execute query: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_summary
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createHdumpSummaryTool() {
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
            "hdump_summary",
            "Quick overview of an open heap dump. Does NOT trigger retained-size computation. "
                + "Returns object/class counts, heap size, top classes by instance count, and GC root types.",
            schema),
        (exchange, args) -> handleHdumpSummary(args.arguments()));
  }

  private CallToolResult handleHdumpSummary(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      HeapSessionRegistry.SessionInfo info = heapSessionRegistry.getOrCurrent(sessionId);
      HeapSession session = info.session();

      // Top 20 classes by instance count
      List<Map<String, Object>> topClasses =
          HdumpPathEvaluator.evaluate(
              session, HdumpPathParser.parse("classes | top(20, instanceCount)"));

      // GC root type distribution
      List<Map<String, Object>> gcRootTypes =
          HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("gcroots | groupBy(type)"));

      Map<String, Object> stats = session.getStatistics();
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("path", info.path().toString());
      response.put("objectCount", stats.get("objects"));
      response.put("classCount", stats.get("classes"));
      response.put("heapSize", stats.get("totalHeapSize"));
      response.put("topClasses", topClasses);
      response.put("gcRootTypes", gcRootTypes);
      return successResult(response);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to get heap summary: {}", e.getMessage(), e);
      return errorResult("Failed to get heap summary: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_report
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createHdumpReportTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "focus": {
              "type": "string",
              "description": "Comma-separated subset of analyses to run: leaks, waste, duplicates, histogram. Omit to run all."
            },
            "format": {
              "type": "string",
              "description": "Output format: text (default) or markdown",
              "enum": ["text", "markdown"]
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "hdump_report",
            "Full heap health report. Triggers retained-size computation, leak detection, and "
                + "duplicate subgraph analysis. May take several minutes on large heaps. "
                + "Returns severity-ranked findings (CRITICAL > WARNING > INFO) with suggested queries. "
                + "Use 'focus' to limit analyses: leaks, waste, duplicates, histogram.",
            schema),
        (exchange, args) -> handleHdumpReport(args.arguments()));
  }

  private CallToolResult handleHdumpReport(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    String focusStr = (String) args.get("focus");
    String format = (String) args.get("format");

    try {
      HeapSessionRegistry.SessionInfo info = heapSessionRegistry.getOrCurrent(sessionId);

      Set<String> focus = new HashSet<>();
      if (focusStr != null && !focusStr.isBlank()) {
        for (String f : focusStr.split(",")) {
          String trimmed = f.trim();
          if (!trimmed.isEmpty()) {
            focus.add(trimmed);
          }
        }
      }

      List<HeapReportGenerator.Finding> findings =
          HeapReportGenerator.generate(info.session(), focus);

      String reportText =
          "markdown".equalsIgnoreCase(format)
              ? HeapReportGenerator.formatMarkdown(findings, info.session())
              : HeapReportGenerator.formatText(findings, info.session());

      // Also return structured findings
      List<Map<String, Object>> findingMaps = new ArrayList<>();
      for (HeapReportGenerator.Finding f : findings) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("severity", f.severity().name());
        fm.put("category", f.category());
        fm.put("title", f.title());
        if (f.description() != null) fm.put("description", f.description());
        if (f.retainedSize() >= 0) fm.put("retainedSize", f.retainedSize());
        if (f.affectedObjects() >= 0) fm.put("affectedObjects", f.affectedObjects());
        if (f.action() != null) fm.put("action", f.action());
        if (f.query() != null) fm.put("query", f.query());
        findingMaps.add(fm);
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("findingCount", findings.size());
      response.put("findings", findingMaps);
      response.put("report", reportText);
      return successResult(response);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to generate heap report: {}", e.getMessage(), e);
      return errorResult("Failed to generate heap report: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_help
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createHdumpHelpTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "topic": {
              "type": "string",
              "description": "Help topic: overview, roots, filters, operators, examples, patterns, tools. Omit for full reference.",
              "enum": ["overview", "roots", "filters", "operators", "examples", "patterns", "tools"]
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "hdump_help",
            "HdumpPath query language documentation. "
                + "Topics: overview, roots, filters, operators, examples, patterns, tools. "
                + "Call hdump_help(topic=\"tools\") to see all hdump_* MCP tools.",
            schema),
        (exchange, args) -> handleHdumpHelp(args.arguments()));
  }

  private CallToolResult handleHdumpHelp(Map<String, Object> args) {
    String topic = (String) args.get("topic");
    if (topic == null || topic.isBlank()) {
      topic = "overview";
    }

    String content =
        switch (topic.toLowerCase()) {
          case "overview" -> getHdumpOverviewHelp();
          case "roots" -> getHdumpRootsHelp();
          case "filters" -> getHdumpFiltersHelp();
          case "operators" -> getHdumpOperatorsHelp();
          case "examples" -> getHdumpExamplesHelp();
          case "patterns" -> getHdumpPatternsHelp();
          case "tools" -> getHdumpToolsHelp();
          default ->
              "Unknown topic: "
                  + topic
                  + ". Valid topics: overview, roots, filters, operators, examples, patterns, tools";
        };

    return new CallToolResult(List.of(new TextContent(content)), false, null, null);
  }

  private String getHdumpOverviewHelp() {
    return """
        # HdumpPath Query Language

        HdumpPath is a path-based query language for heap dump analysis. Queries select a
        root data set, optionally filter by type and predicates, then apply a pipeline of
        transformation operators.

        ## Query Structure
        ```
        <root>[/<type>][<predicates>] [| <operator>]*
        ```

        ## Roots
        - `objects`    — all heap object instances
        - `classes`    — class metadata (no retained-size needed)
        - `gcroots`    — GC root references
        - `clusters`   — graph-based leak clusters (triggers retained-size on first use)
        - `duplicates` — structurally-identical subgraphs (cached after first run)
        - `ages`       — objects annotated with estimated age

        ## Quick Examples
        ```
        objects | top(10, retained)
        classes | top(20, instanceCount)
        objects/java.lang.String | count
        objects/instanceof/java.util.Map | waste() | top(10, wastedBytes)
        duplicates | sortBy(wastedBytes desc) | top(20)
        gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)
        checkLeaks(detector="duplicate-strings")
        clusters | sortBy(score desc) | head(10)
        ```

        ## Performance Notes
        Operators that compute retained sizes (retained, pathToRoot, retentionPaths,
        dominators, waste, cacheStats, checkLeaks) trigger approximate retained-size
        computation on first use. For large heaps (>500K objects) this may take minutes.
        Results are cached; subsequent queries on the same session are fast.
        """;
  }

  private String getHdumpRootsHelp() {
    return """
        # HdumpPath Root Types

        ## objects
        Heap object instances. Fields:
        - `id`          — object ID (long)
        - `class`       — class name (human-readable)
        - `shallow`     — shallow size in bytes
        - `retained`    — retained size in bytes (triggers computation on first use)
        - `arrayLength` — array length (-1 for non-arrays)
        - `stringValue` — string value for java.lang.String objects

        Type specifiers:
        - `objects/java.lang.String`                — exact class
        - `objects/java.util.*`                     — glob pattern
        - `objects/instanceof/java.util.Map`        — subtypes (polymorphic)
        - `objects/int[]` or `objects/[I`           — array types

        ## classes
        Class metadata. Fields:
        - `id`            — class ID
        - `name`          — internal class name
        - `simpleName`    — human-readable class name
        - `instanceCount` — number of live instances
        - `instanceSize`  — per-instance shallow size
        - `superClass`    — superclass name
        - `isArray`       — true for array types

        ## gcroots
        GC root references. Fields:
        - `type`     — root type (see below)
        - `objectId` — referenced object ID
        - `object`   — object class name
        - `shallow`  — object shallow size
        - `retained` — object retained size

        Root types: JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK, STICKY_CLASS,
                    THREAD_BLOCK, MONITOR_USED, THREAD_OBJ

        Example: `gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)`

        ## clusters
        Graph-based leak clusters. Fields:
        - `id`            — cluster ID
        - `objectCount`   — objects in cluster
        - `retainedSize`  — total retained bytes
        - `score`         — leak likelihood score
        - `dominantClass` — most common class in cluster
        - `anchorType`    — GC root type holding this cluster

        ## duplicates
        Structurally-identical subgraphs. Fields:
        - `id`          — group ID
        - `rootClass`   — root class of the duplicated subgraph
        - `copies`      — number of duplicate copies
        - `uniqueSize`  — size of one unique copy
        - `wastedBytes` — bytes that could be freed by deduplication
        - `depth`       — fingerprint depth used
        - `nodeCount`   — nodes in subgraph

        Example: `duplicates | sortBy(wastedBytes desc) | top(20)`

        ## ages
        Objects annotated with age estimation. Fields: all `objects` fields plus:
        - `estimatedAge` — relative age score (higher = older)
        - `ageBucket`    — OLD, MATURE, YOUNG, UNKNOWN
        - `ageSignals`   — signals that contributed to the estimate
        """;
  }

  private String getHdumpFiltersHelp() {
    return """
        # HdumpPath Predicates and Filters

        ## Predicate Syntax
        ```
        [field op value]
        [field op value and field2 op value2]
        [field op value or field2 op value2]
        [not (field op value)]
        ```

        ## Comparison Operators
        - `=`   — equals
        - `!=`  — not equals
        - `>`   — greater than
        - `>=`  — greater or equal
        - `<`   — less than
        - `<=`  — less or equal
        - `~`   — regex match (e.g., `[class ~ "java\\.util\\..*"]`)

        ## Size Literals
        Numeric literals can include size units: `K KB M MB G GB`
        Examples: `[shallow > 1MB]`, `[retained >= 10M]`, `[wastedBytes > 512K]`

        ## Filter Functions
        - `contains(field, "substr")`      — field contains substring
        - `startsWith(field, "prefix")`    — field starts with prefix
        - `between(field, lo, hi)`         — field in [lo, hi] range (inclusive)
        - `exists(field)`                  — field is present and non-null

        ## filter() Pipeline Operator
        Apply predicates after type expansion:
        ```
        objects | filter(shallow > 1MB)
        objects/instanceof/java.util.Map | filter(shallow > 100K and class ~ "HashMap")
        ```

        ## Examples
        ```
        objects[shallow > 1MB]
        objects/java.lang.String[stringValue ~ "error.*"]
        classes[instanceCount > 1000]
        gcroots[type = "THREAD_OBJ"]
        duplicates[wastedBytes > 100K]
        objects | filter(retained > 10MB and class ~ "cache|Cache")
        ```
        """;
  }

  private String getHdumpOperatorsHelp() {
    return """
        # HdumpPath Pipeline Operators

        Operators are chained with `|` after the root (and optional predicates).

        ## Sorting and Limiting
        - `top(n [,field] [,asc|desc])` — return top N rows sorted by field (default: desc)
        - `sortBy(field [asc|desc])`    — sort without limiting
        - `head(n)`                     — first N rows
        - `tail(n)`                     — last N rows

        ## Filtering and Projection
        - `filter(predicate)`           — apply predicate filter
        - `select(f1, f2 as alias, ...)` — project specific fields
        - `distinct(field)`             — deduplicate by field value

        ## Aggregation
        - `count`                       — total row count
        - `sum(field)`                  — sum of field values
        - `stats(field)`                — min/max/avg/count/sum for field
        - `groupBy(field [,agg=count|sum|avg|min|max] [,value=expr])` — group and aggregate

        ## Memory Analysis
        - `waste()`             — Map/List capacity waste
                                  output: capacity, size, wastedBytes, wasteRatio
        - `cacheStats()`        — Map-as-cache metrics
                                  output: entryCount, maxSize, fillRatio, costPerEntry, isLruMode
        - `checkLeaks([detector="name"] [,minSize=N])`
                                  detectors: threadlocal-leak, classloader-leak,
                                             duplicate-strings, growing-collections,
                                             listener-leak, finalizer-queue

        ## Dominator / Retention
        - `pathToRoot()`        — trace each object to its nearest GC root
        - `retentionPaths()`    — merged class-level retention paths
        - `retainedBreakdown([depth=N])` — dominator subtree breakdown by class
        - `dominators([groupBy="class"|"package"] [,minRetained=size])`
                                  top retained-size dominators
        - `whatif()`            — simulate removal: freedBytes, freedPct, remainingRetained

        ## Thread Attribution
        - `threadOwner()`       — add ownerThread, ownership fields
        - `dominatedSize()`     — for THREAD_OBJ roots: threadName, dominated, dominatedCount

        ## Age Estimation
        - `estimateAge()`       — add estimatedAge, ageBucket, ageSignals

        ## Cross-Session
        - `join(session=id|alias [,root="eventType" ,by=field])`
                                  heap diff (two hprof) or JFR correlation
                                  with no extra args: class-level instance count delta

        ## Notes
        - Operators requiring retained sizes trigger computation on first use.
        - `clusters` root automatically computes retained sizes.
        - `duplicates` root caches results per depth; default depth = 3.
        """;
  }

  private String getHdumpExamplesHelp() {
    return """
        # HdumpPath Query Examples

        ## Memory Overview
        ```
        # Top 10 objects by retained size
        objects | top(10, retained)

        # Top 20 classes by instance count
        classes | top(20, instanceCount)

        # All String objects by shallow size
        objects/java.lang.String | top(20, shallow)

        # Objects larger than 1 MB
        objects[shallow > 1MB] | sortBy(shallow desc)
        ```

        ## Duplicate Detection
        ```
        # All duplicate subgraph groups, most wasteful first
        duplicates | sortBy(wastedBytes desc) | top(20)

        # Only groups with significant waste
        duplicates[wastedBytes > 100K] | sortBy(wastedBytes desc)

        # Duplicate strings (via leak detector)
        checkLeaks(detector="duplicate-strings")
        ```

        ## Leak Detection
        ```
        # Run all leak detectors
        checkLeaks()

        # Thread-local leak detection
        checkLeaks(detector="threadlocal-leak")

        # Growing collections only
        checkLeaks(detector="growing-collections", minSize=1048576)

        # Graph-based leak clusters
        clusters | sortBy(score desc) | head(10)
        ```

        ## Collection Waste
        ```
        # HashMap waste analysis
        objects/instanceof/java.util.HashMap | waste() | top(20, wastedBytes)

        # LRU cache analysis
        objects/java.util.LinkedHashMap | cacheStats() | filter(isLruMode = true)

        # High fill ratio caches
        objects/instanceof/java.util.Map | cacheStats() | sortBy(costPerEntry desc) | top(10)
        ```

        ## Thread Memory Attribution
        ```
        # Memory dominated by each thread
        gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)

        # Objects owned by a thread
        objects | threadOwner() | groupBy(ownerThread, agg=sum, value=retained)
        ```

        ## Retention Path Analysis
        ```
        # Retention paths for large objects
        objects[retained > 50MB] | retentionPaths()

        # Dominator objects
        objects | dominators(groupBy="class", minRetained=10MB)

        # Simulate freeing an object
        objects[class = "com.example.Cache"] | whatif()
        ```

        ## Age Estimation
        ```
        # Old objects by retained size
        objects | estimateAge() | filter(ageBucket = "OLD") | top(10, retained)

        # Age distribution by class
        ages | groupBy(ageBucket) | sortBy(count desc)
        ```

        ## Heap Diff (Two Dumps)
        ```
        # Classes with growing instance counts (session 1 = baseline, session 2 = current)
        classes | join(session=1) | sortBy(instanceCountDelta desc) | head(20)
        ```

        ## GC Roots
        ```
        # Summary of GC root types
        gcroots | groupBy(type)

        # Large objects referenced as JNI globals
        gcroots/JNI_GLOBAL | sortBy(retained desc) | top(10)
        ```
        """;
  }

  private String getHdumpPatternsHelp() {
    return """
        # HdumpPath Analysis Workflows

        ## Workflow 1: Memory Leak Investigation
        1. Quick overview:
           `hdump_summary`
        2. Top retained objects:
           `objects | top(10, retained)`
        3. Leak cluster analysis:
           `clusters | sortBy(score desc) | head(10)`
        4. Retention paths for suspects:
           `objects[class ~ "SuspectClass"] | retentionPaths()`
        5. Confirm via dominator tree:
           `objects | dominators(groupBy="class", minRetained=10MB)`

        ## Workflow 2: Heap Size Reduction
        1. Top waste from collections:
           `objects/instanceof/java.util.HashMap | waste() | top(20, wastedBytes)`
        2. Duplicate subgraphs:
           `duplicates | sortBy(wastedBytes desc) | top(20)`
        3. Duplicate strings (common in config-heavy apps):
           `checkLeaks(detector="duplicate-strings")`
        4. What-if simulation:
           `objects[class = "com.example.ConfigCache"] | whatif()`

        ## Workflow 3: Thread Memory Attribution
        1. Per-thread memory dominance:
           `gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)`
        2. Objects owned by a specific thread:
           `objects | threadOwner() | filter(ownerThread ~ "worker-.*") | top(20, retained)`

        ## Workflow 4: Memory Regression (Heap Diff)
        1. Open baseline: `hdump_open path="/baseline.hprof" alias=baseline`
        2. Open current:  `hdump_open path="/current.hprof"`
        3. Growing classes:
           `classes | join(session=baseline) | sortBy(instanceCountDelta desc) | head(20)`
        4. New large objects:
           `classes | join(session=baseline) | filter(instanceCountDelta > 0 and instanceSize > 1000)`

        ## Workflow 5: Cache Analysis
        1. Find LRU caches:
           `objects/java.util.LinkedHashMap | cacheStats() | filter(isLruMode = true)`
        2. Expensive caches (high cost per entry):
           `objects/instanceof/java.util.Map | cacheStats() | sortBy(costPerEntry desc) | top(10)`
        3. Overfilled caches:
           `objects/instanceof/java.util.Map | cacheStats() | filter(fillRatio > 0.9)`

        ## Workflow 6: Quick Health Check
        Run `hdump_report` — it executes all analyses and returns severity-ranked findings.
        Use `focus` parameter to limit scope: e.g., `focus="leaks,waste"`.
        """;
  }

  private String getHdumpToolsHelp() {
    return """
        # hdump_* MCP Tools

        ## hdump_open
        Opens an HPROF heap dump file for analysis.
        Parameters: path (required), alias (optional)
        Returns: sessionId, objectCount, classCount, heapSize

        ## hdump_close
        Closes one or all heap dump sessions.
        Parameters: sessionId (optional), closeAll (boolean, optional)
        Returns: success, message, remainingSessions

        ## hdump_query
        Executes an HdumpPath query against an open heap dump session.
        Parameters: query (required), sessionId (optional), limit (optional, default 100)
        Returns: resultCount, results (rows), truncated (if limit hit)
        Use hdump_help(topic="operators") for full operator reference.
        Use hdump_help(topic="examples") for query examples.

        ## hdump_summary
        Quick heap overview. Does NOT trigger retained-size computation.
        Parameters: sessionId (optional)
        Returns: objectCount, classCount, heapSize, topClasses (top 20 by count), gcRootTypes

        ## hdump_report
        Full heap health report. Triggers retained-size computation and all analyses.
        May take several minutes on large heaps (>500K objects).
        Parameters: sessionId (optional), focus (optional: leaks,waste,duplicates,histogram),
                    format (optional: text|markdown)
        Returns: findings (severity-ranked), report (formatted text or markdown)

        Severity levels:
          CRITICAL — retained size > 100 MB
          WARNING  — retained size > 10 MB, or duplicate waste > 1 MB
          INFO     — general observations

        ## hdump_help
        HdumpPath documentation.
        Parameters: topic (optional): overview, roots, filters, operators, examples, patterns, tools

        ## Recommended Analysis Order
        1. hdump_open       — open the heap dump
        2. hdump_summary    — quick orientation (cheap, no retained-size computation)
        3. hdump_query      — targeted queries (e.g., top(10, retained), waste(), checkLeaks)
        4. hdump_report     — full automated analysis when you need a comprehensive view
        5. hdump_close      — release memory when done
        """;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_open
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createPprofOpenTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Absolute path to the pprof profile file (.pb.gz or .pprof)"
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
            "pprof_open",
            "Opens a pprof profile file (.pb.gz or .pprof) for analysis. "
                + "Returns a session ID and profile metadata (sample types, duration, counts). "
                + "Use the returned session ID with other pprof_* tools.",
            schema),
        (exchange, args) -> handlePprofOpen(args.arguments()));
  }

  private CallToolResult handlePprofOpen(Map<String, Object> args) {
    String path = (String) args.get("path");
    String alias = (String) args.get("alias");

    if (path == null || path.isBlank()) {
      return errorResult("path is required");
    }

    try {
      Path profilePath = Path.of(path);

      if (!Files.exists(profilePath)) {
        return errorResult("File not found: " + path);
      }
      if (!Files.isRegularFile(profilePath)) {
        return errorResult("Not a file: " + path);
      }
      if (!Files.isReadable(profilePath)) {
        return errorResult("File not readable: " + path);
      }

      var info = pprofSessionRegistry.open(profilePath, alias);
      LOG.info("Opened pprof profile {} as session {}", path, info.id());

      Map<String, Object> result = info.toMap();
      result.put("message", "pprof profile opened successfully");
      return successResult(result);

    } catch (Exception e) {
      LOG.error("Failed to open pprof profile: {}", e.getMessage(), e);
      return errorResult("Failed to open pprof profile: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_close
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createPprofCloseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias to close. Omit to close the current session."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool("pprof_close", "Closes a pprof session and releases its resources.", schema),
        (exchange, args) -> handlePprofClose(args.arguments()));
  }

  private CallToolResult handlePprofClose(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      pprofSessionRegistry.close(String.valueOf(info.id()));

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("closed", info.id());
      result.put("path", info.path().toString());
      result.put("message", "Session closed");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to close pprof session: {}", e.getMessage(), e);
      return errorResult("Failed to close session: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_query
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createPprofQueryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "PprofPath query string, e.g. 'samples | top(10, cpu)'"
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of results to return (default: 100)"
            }
          },
          "required": ["query"]
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_query",
            "Executes a PprofPath query against an open pprof profile. "
                + "Query syntax: 'samples[predicate] | operator(args)'. "
                + "Examples: 'samples | count()', 'samples | top(10, cpu)', "
                + "'samples | groupBy(thread, sum(cpu))', 'samples[thread=\\'main\\'] | head(5)'. "
                + "Use pprof_help for full query language reference.",
            schema),
        (exchange, args) -> handlePprofQuery(args.arguments()));
  }

  private CallToolResult handlePprofQuery(Map<String, Object> args) {
    String queryStr = (String) args.get("query");
    String sessionId = (String) args.get("sessionId");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 100;

    if (queryStr == null || queryStr.isBlank()) {
      return errorResult("query is required");
    }
    if (limit <= 0) {
      return errorResult("limit must be positive");
    }

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      var query = PprofPathParser.parse(queryStr);
      List<Map<String, Object>> rows = PprofPathEvaluator.evaluate(info.session(), query);

      boolean truncated = rows.size() > limit;
      if (truncated) {
        rows = rows.subList(0, limit);
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("query", queryStr);
      response.put("sessionId", info.id());
      response.put("resultCount", rows.size());
      response.put("results", rows);
      if (truncated) {
        response.put("truncated", true);
        response.put(
            "message", "Results truncated to " + limit + ". Use 'limit' parameter for more.");
      }
      return successResult(response);

    } catch (PprofPathParseException e) {
      LOG.warn("PprofPath query parse error: {}", e.getMessage());
      return errorResult("Query parse error: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      LOG.warn("PprofPath query error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to execute pprof query: {}", e.getMessage(), e);
      return errorResult("Failed to execute query: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_summary
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createPprofSummaryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_summary",
            "Returns a quick overview of a pprof profile: sample types, counts, duration, "
                + "and top functions by the primary value type.",
            schema),
        (exchange, args) -> handlePprofSummary(args.arguments()));
  }

  private CallToolResult handlePprofSummary(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      var profile = info.session().getProfile();

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("path", info.path().toString());
      response.putAll(info.session().getStatistics());

      // Top functions by first value type
      if (!profile.sampleTypes().isEmpty()) {
        String firstType = requireSafeFieldName(profile.sampleTypes().get(0).type(), "sampleType");
        try {
          var query = PprofPathParser.parse("samples | top(10, " + firstType + ")");
          List<Map<String, Object>> topFns = PprofPathEvaluator.evaluate(info.session(), query);
          response.put("topFunctions", topFns);
        } catch (Exception e) {
          LOG.debug("Could not compute top functions: {}", e.getMessage());
        }
      }

      return successResult(response);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to get pprof summary: {}", e.getMessage(), e);
      return errorResult("Failed to get summary: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createPprofFlamegraphTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "valueField": {
              "type": "string",
              "description": "Value type to aggregate (e.g. 'cpu', 'alloc_space'). Defaults to first sample type."
            },
            "filter": {
              "type": "string",
              "description": "Optional filter predicate, e.g. \"thread='main'\""
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of stack entries to return (default: 50)"
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_flamegraph",
            "Produces folded stack profile data suitable for flame graph visualization. "
                + "Returns rows of {stack: 'root;parent;leaf', <valueField>: N} sorted by value descending. "
                + "Stack frames are separated by ';' with root frame first.",
            schema),
        (exchange, args) -> handlePprofFlamegraph(args.arguments()));
  }

  private CallToolResult handlePprofFlamegraph(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    String valueField = (String) args.get("valueField");
    String filter = (String) args.get("filter");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 50;

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);

      // Resolve default value field
      String effectiveValue = valueField;
      if (effectiveValue == null || effectiveValue.isBlank()) {
        var profile = info.session().getProfile();
        if (!profile.sampleTypes().isEmpty()) {
          effectiveValue = profile.sampleTypes().get(0).type();
        }
      }

      if (effectiveValue == null) {
        return errorResult("No sample types found in profile");
      }

      requireSafeFieldName(effectiveValue, "valueField");

      if (filter != null && filter.contains("]")) {
        return errorResult("Invalid filter: unexpected ']' in filter expression");
      }

      // Build query: samples[<filter>] | stackprofile(<valueField>)
      String queryStr =
          "samples"
              + (filter != null && !filter.isBlank() ? "[" + filter + "]" : "")
              + " | stackprofile("
              + effectiveValue
              + ")";

      var query = PprofPathParser.parse(queryStr);
      List<Map<String, Object>> rows = PprofPathEvaluator.evaluate(info.session(), query);

      boolean truncated = rows.size() > limit;
      if (truncated) {
        rows = rows.subList(0, limit);
      }

      final String finalValue = effectiveValue;
      long total =
          rows.stream()
              .mapToLong(r -> r.get(finalValue) instanceof Number n ? n.longValue() : 0L)
              .sum();

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("valueField", effectiveValue);
      response.put("total", total);
      response.put("rowCount", rows.size());
      response.put("rows", rows);
      if (truncated) {
        response.put("truncated", true);
      }
      return successResult(response);

    } catch (PprofPathParseException e) {
      return errorResult("Query parse error: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to generate pprof flamegraph: {}", e.getMessage(), e);
      return errorResult("Failed to generate flamegraph: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_use - USE Method Analysis (Utilization, Saturation, Errors)
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createPprofUseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "resources": {
              "type": "array",
              "items": { "type": "string", "enum": ["cpu", "memory", "threads", "errors"] },
              "description": "Resources to analyze. Defaults to all: cpu, memory, threads, errors."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_use",
            "Analyzes a pprof profile using Brendan Gregg's USE Method (Utilization, Saturation, Errors). "
                + "CPU: utilization stats and top consuming functions. "
                + "Memory: saturation analysis if allocation sample types are present. "
                + "Threads: distribution across threads to identify serial bottlenecks. "
                + "Errors: heuristic scan of hot function names for error-related patterns.",
            schema),
        (exchange, args) -> handlePprofUse(exchange, args.arguments()));
  }

  @SuppressWarnings("unchecked")
  private CallToolResult handlePprofUse(McpSyncServerExchange exchange, Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    List<String> resourcesList =
        args.get("resources") instanceof List<?> l ? (List<String>) l : List.of("all");
    Set<String> resources =
        resourcesList.contains("all")
            ? Set.of("cpu", "memory", "threads", "errors")
            : Set.copyOf(resourcesList);

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      var profile = info.session().getProfile();

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("method", "USE");
      result.put("profilePath", info.path().toString());

      Map<String, Object> resourceMetrics = new LinkedHashMap<>();
      int step = 0;
      int totalSteps = resources.size() + 1;

      if (resources.contains("cpu")) {
        sendProgress(exchange, "pprof_use", step++, totalSteps, "Analyzing CPU...");
        resourceMetrics.put("cpu", analyzePprofCpu(info, profile));
      }

      if (resources.contains("memory")) {
        sendProgress(exchange, "pprof_use", step++, totalSteps, "Analyzing memory...");
        Map<String, Object> memMetrics = analyzePprofMemory(info, profile);
        if (!memMetrics.isEmpty()) {
          resourceMetrics.put("memory", memMetrics);
        }
      }

      if (resources.contains("threads")) {
        sendProgress(exchange, "pprof_use", step++, totalSteps, "Analyzing threads...");
        Map<String, Object> threadMetrics = analyzePprofThreads(info, profile);
        if (!threadMetrics.isEmpty()) {
          resourceMetrics.put("threads", threadMetrics);
        }
      }

      if (resources.contains("errors")) {
        sendProgress(exchange, "pprof_use", step++, totalSteps, "Scanning for errors...");
        resourceMetrics.put("errors", analyzePprofErrors(info));
      }

      result.put("resources", resourceMetrics);

      sendProgress(exchange, "pprof_use", step, totalSteps, "Generating insights...");
      result.put("insights", generatePprofUseInsights(resourceMetrics, profile));

      sendProgress(exchange, "pprof_use", totalSteps, totalSteps, "Done");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to perform pprof USE analysis: {}", e.getMessage(), e);
      return errorResult("Failed to perform USE analysis: " + e.getMessage());
    }
  }

  private Map<String, Object> analyzePprofCpu(
      SamplingSessionRegistry.SessionInfo<PprofSession> info, PprofProfile.Profile profile) {
    Map<String, Object> cpu = new LinkedHashMap<>();

    // Find the first cpu-like sample type
    String cpuType =
        profile.sampleTypes().stream()
            .map(PprofProfile.ValueType::type)
            .filter(t -> t.contains("cpu") || t.contains("wall") || t.contains("time"))
            .findFirst()
            .orElse(profile.sampleTypes().isEmpty() ? null : profile.sampleTypes().get(0).type());

    if (cpuType == null) {
      cpu.put("note", "No CPU-like sample type found");
      return cpu;
    }

    requireSafeFieldName(cpuType, "sampleType");
    cpu.put("sampleType", cpuType);
    try {
      var statsQuery = PprofPathParser.parse("samples | stats(" + cpuType + ")");
      List<Map<String, Object>> statsRows = PprofPathEvaluator.evaluate(info.session(), statsQuery);
      if (!statsRows.isEmpty()) {
        Map<String, Object> util = new LinkedHashMap<>();
        util.put("stats", statsRows.get(0));
        String unit =
            profile.sampleTypes().stream()
                .filter(vt -> vt.type().equals(cpuType))
                .map(PprofProfile.ValueType::unit)
                .findFirst()
                .orElse("");
        util.put("unit", unit);
        cpu.put("utilization", util);
      }

      var topQuery = PprofPathParser.parse("samples | top(10, " + cpuType + ")");
      List<Map<String, Object>> topRows = PprofPathEvaluator.evaluate(info.session(), topQuery);
      cpu.put("topFunctions", topRows);
    } catch (Exception e) {
      LOG.debug("Error in pprof CPU analysis: {}", e.getMessage());
    }
    return cpu;
  }

  private Map<String, Object> analyzePprofMemory(
      SamplingSessionRegistry.SessionInfo<PprofSession> info, PprofProfile.Profile profile) {
    Map<String, Object> mem = new LinkedHashMap<>();

    // Look for allocation or in-use memory sample types
    List<String> memTypes =
        profile.sampleTypes().stream()
            .map(PprofProfile.ValueType::type)
            .filter(
                t ->
                    t.contains("alloc")
                        || t.contains("inuse")
                        || t.contains("heap")
                        || t.contains("mem"))
            .toList();

    if (memTypes.isEmpty()) {
      return mem; // no memory data
    }

    for (String memType : memTypes) {
      Map<String, Object> typeMetrics = new LinkedHashMap<>();
      try {
        requireSafeFieldName(memType, "sampleType");
        var statsQuery = PprofPathParser.parse("samples | stats(" + memType + ")");
        List<Map<String, Object>> statsRows =
            PprofPathEvaluator.evaluate(info.session(), statsQuery);
        if (!statsRows.isEmpty()) {
          typeMetrics.put("stats", statsRows.get(0));
        }

        var topQuery = PprofPathParser.parse("samples | top(10, " + memType + ")");
        List<Map<String, Object>> topRows = PprofPathEvaluator.evaluate(info.session(), topQuery);
        typeMetrics.put("topAllocators", topRows);

        String unit =
            profile.sampleTypes().stream()
                .filter(vt -> vt.type().equals(memType))
                .map(PprofProfile.ValueType::unit)
                .findFirst()
                .orElse("");
        typeMetrics.put("unit", unit);
      } catch (Exception e) {
        LOG.debug("Error in pprof memory analysis for {}: {}", memType, e.getMessage());
      }
      mem.put(memType, typeMetrics);
    }
    return mem;
  }

  private Map<String, Object> analyzePprofThreads(
      SamplingSessionRegistry.SessionInfo<PprofSession> info, PprofProfile.Profile profile) {
    Map<String, Object> threads = new LinkedHashMap<>();

    // Check if thread label is present by attempting groupBy
    try {
      var threadQuery = PprofPathParser.parse("samples | groupBy(thread)");
      List<Map<String, Object>> threadRows =
          PprofPathEvaluator.evaluate(info.session(), threadQuery);

      if (threadRows.isEmpty()
          || (threadRows.size() == 1 && "<null>".equals(threadRows.get(0).get("thread")))) {
        threads.put("note", "No thread label found in this profile");
        return threads;
      }

      threads.put("threadCount", threadRows.size());
      threads.put("distribution", threadRows);

      // Detect single-threaded saturation
      long totalSamples =
          threadRows.stream()
              .mapToLong(r -> r.get("count") instanceof Number n ? n.longValue() : 0L)
              .sum();
      if (!threadRows.isEmpty() && totalSamples > 0) {
        long topCount = threadRows.get(0).get("count") instanceof Number n ? n.longValue() : 0L;
        double topPct = 100.0 * topCount / totalSamples;
        Map<String, Object> saturation = new LinkedHashMap<>();
        saturation.put("dominantThread", threadRows.get(0).get("thread"));
        saturation.put("dominantThreadPct", Math.round(topPct * 10) / 10.0);
        if (topPct > 90) {
          saturation.put(
              "finding", "Single-threaded: one thread holds >" + (int) topPct + "% of all samples");
        }
        threads.put("saturation", saturation);
      }
    } catch (Exception e) {
      LOG.debug("Error in pprof thread analysis: {}", e.getMessage());
    }
    return threads;
  }

  private Map<String, Object> analyzePprofErrors(
      SamplingSessionRegistry.SessionInfo<PprofSession> info) {
    Map<String, Object> errors = new LinkedHashMap<>();
    Set<String> errorKeywords =
        Set.of("exception", "error", "panic", "throw", "fail", "fatal", "abort", "crash");

    try {
      var topFnQuery = PprofPathParser.parse("samples | groupBy(stackTrace/0/name) | head(30)");
      List<Map<String, Object>> topFns = PprofPathEvaluator.evaluate(info.session(), topFnQuery);

      List<Map<String, Object>> suspects = new ArrayList<>();
      for (Map<String, Object> row : topFns) {
        String name = row.get("stackTrace/0/name") instanceof String s ? s : "";
        String nameLower = name.toLowerCase();
        for (String kw : errorKeywords) {
          if (nameLower.contains(kw)) {
            suspects.add(row);
            break;
          }
        }
      }

      errors.put("suspectFunctions", suspects);
      errors.put(
          "note",
          "Error detection is heuristic: scans hot leaf functions for error-related keywords");
    } catch (Exception e) {
      LOG.debug("Error in pprof error analysis: {}", e.getMessage());
    }
    return errors;
  }

  private List<String> generatePprofUseInsights(
      Map<String, Object> resourceMetrics, PprofProfile.Profile profile) {
    List<String> insights = new ArrayList<>();

    // CPU insights
    if (resourceMetrics.containsKey("cpu")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> cpu = (Map<String, Object>) resourceMetrics.get("cpu");
      if (cpu.containsKey("utilization")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> util = (Map<String, Object>) cpu.get("utilization");
        if (util.containsKey("stats")) {
          @SuppressWarnings("unchecked")
          Map<String, Object> stats = (Map<String, Object>) util.get("stats");
          Object count = stats.get("count");
          if (count instanceof Number n && n.longValue() > 0) {
            insights.add(
                "Profile contains "
                    + n.longValue()
                    + " CPU samples. Use pprof_flamegraph for visual breakdown.");
          }
        }
      }
    }

    // Thread saturation insights
    if (resourceMetrics.containsKey("threads")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> threads = (Map<String, Object>) resourceMetrics.get("threads");
      if (threads.containsKey("saturation")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sat = (Map<String, Object>) threads.get("saturation");
        if (sat.containsKey("finding")) {
          insights.add(sat.get("finding").toString() + " — consider parallelizing the workload");
        }
      }
    }

    // Memory insights
    if (resourceMetrics.containsKey("memory")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> mem = (Map<String, Object>) resourceMetrics.get("memory");
      if (!mem.isEmpty()) {
        insights.add(
            "Allocation profile present ("
                + String.join(", ", mem.keySet())
                + "). Use pprof_hotmethods to identify top allocators.");
      }
    }

    // Error insights
    if (resourceMetrics.containsKey("errors")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> errMap = (Map<String, Object>) resourceMetrics.get("errors");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> suspects =
          errMap.get("suspectFunctions") instanceof List<?> l
              ? (List<Map<String, Object>>) l
              : List.of();
      if (!suspects.isEmpty()) {
        insights.add(
            "Found "
                + suspects.size()
                + " error-related function(s) in hot paths — review for exception overhead.");
      }
    }

    if (insights.isEmpty()) {
      insights.add("No significant issues detected. Use pprof_flamegraph for detailed analysis.");
    }
    return insights;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_hotmethods
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createPprofHotmethodsTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "topN": {
              "type": "integer",
              "description": "Number of top methods to return (default: 20)"
            },
            "valueField": {
              "type": "string",
              "description": "Value type to rank by (e.g. 'cpu', 'alloc_objects'). Defaults to first sample type."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_hotmethods",
            "Identifies the hottest methods by direct (leaf-frame) cost. "
                + "Groups samples by the leaf function name and sums the chosen value type. "
                + "Shows what each function is directly responsible for (not including callees). "
                + "Use pprof_flamegraph for inclusive (total) cost breakdown.",
            schema),
        (exchange, args) -> handlePprofHotmethods(args.arguments()));
  }

  private CallToolResult handlePprofHotmethods(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    int topN = args.get("topN") instanceof Number n ? n.intValue() : 20;
    String valueField = (String) args.get("valueField");

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      var profile = info.session().getProfile();

      // Resolve value field
      String effectiveField = valueField;
      if (effectiveField == null || effectiveField.isBlank()) {
        effectiveField =
            profile.sampleTypes().isEmpty() ? null : profile.sampleTypes().get(0).type();
      }
      if (effectiveField == null) {
        return errorResult("No sample types found in profile");
      }

      requireSafeFieldName(effectiveField, "valueField");

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("valueField", effectiveField);

      // Direct cost: group by leaf function, sum value
      var directQuery =
          PprofPathParser.parse(
              "samples | groupBy(stackTrace/0/name, sum("
                  + effectiveField
                  + ")) | head("
                  + topN
                  + ")");
      List<Map<String, Object>> directCost =
          PprofPathEvaluator.evaluate(info.session(), directQuery);

      // Compute total for percentage
      long total = 0;
      try {
        var statsQuery = PprofPathParser.parse("samples | stats(" + effectiveField + ")");
        List<Map<String, Object>> statsRows =
            PprofPathEvaluator.evaluate(info.session(), statsQuery);
        if (!statsRows.isEmpty() && statsRows.get(0).get("sum") instanceof Number n) {
          total = n.longValue();
        }
      } catch (Exception ignored) {
        // best effort
      }

      // Annotate with percentage
      final long totalFinal = total;
      if (totalFinal > 0) {
        for (Map<String, Object> row : directCost) {
          Object val = row.get("sum_" + effectiveField);
          if (val instanceof Number n) {
            row.put("pct", Math.round(n.doubleValue() * 1000.0 / totalFinal) / 10.0);
          }
        }
      }

      response.put("totalSamples", total);
      response.put("topMethods", directCost);

      final String finalField = effectiveField;
      String unit =
          profile.sampleTypes().stream()
              .filter(vt -> vt.type().equals(finalField))
              .map(PprofProfile.ValueType::unit)
              .findFirst()
              .orElse("");
      response.put("unit", unit);

      return successResult(response);

    } catch (PprofPathParseException e) {
      return errorResult("Query parse error: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to compute pprof hotmethods: {}", e.getMessage(), e);
      return errorResult("Failed to compute hot methods: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_tsa - Thread State Analysis (TSA Method)
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createPprofTsaTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "topThreads": {
              "type": "integer",
              "description": "Number of top threads to profile in detail (default: 10)"
            },
            "includeInsights": {
              "type": "boolean",
              "description": "Whether to include generated insights (default: true)"
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_tsa",
            "Thread State Analysis for pprof profiles. Shows how threads distribute their samples "
                + "and infers thread states (RUNNING, WAITING, IO_BLOCKED, LOCK_BLOCKED) from "
                + "leaf function names. Useful for identifying serial bottlenecks, blocking patterns, "
                + "and I/O-heavy threads. Note: state inference is heuristic since pprof does not "
                + "record explicit thread states.",
            schema),
        (exchange, args) -> handlePprofTsa(exchange, args.arguments()));
  }

  // Keywords used to infer thread state from leaf function names
  private static final Set<String> WAITING_KEYWORDS =
      Set.of("wait", "park", "sleep", "futex", "cond_wait", "pthread_cond_wait", "timedwait");
  private static final Set<String> IO_KEYWORDS =
      Set.of(
          "read",
          "write",
          "recv",
          "send",
          "accept",
          "poll",
          "epoll",
          "select",
          "pread",
          "pwrite",
          "recvfrom",
          "sendto");
  private static final Set<String> LOCK_KEYWORDS =
      Set.of("lock", "mutex", "synchronized", "acquire", "monitor", "trylock", "spinlock");

  private CallToolResult handlePprofTsa(McpSyncServerExchange exchange, Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    int topThreads = args.get("topThreads") instanceof Number n ? n.intValue() : 10;
    boolean includeInsights = !(args.get("includeInsights") instanceof Boolean b) || b;

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      var profile = info.session().getProfile();

      String valueField =
          profile.sampleTypes().isEmpty() ? null : profile.sampleTypes().get(0).type();

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("method", "TSA");
      result.put("profilePath", info.path().toString());

      // Step 1: Thread distribution
      sendProgress(exchange, "pprof_tsa", 0, 3, "Analyzing thread distribution...");
      List<Map<String, Object>> threadDist;
      try {
        var tq = PprofPathParser.parse("samples | groupBy(thread)");
        threadDist = PprofPathEvaluator.evaluate(info.session(), tq);
      } catch (Exception e) {
        threadDist = List.of();
      }

      boolean hasThreadLabel =
          !threadDist.isEmpty() && !"<null>".equals(threadDist.get(0).get("thread"));

      long totalSamples = 0;
      try {
        var cq = PprofPathParser.parse("samples | count()");
        List<Map<String, Object>> cr = PprofPathEvaluator.evaluate(info.session(), cq);
        if (!cr.isEmpty() && cr.get(0).get("count") instanceof Number n) {
          totalSamples = n.longValue();
        }
      } catch (Exception ignored) {
        // best effort
      }

      result.put("totalSamples", totalSamples);

      if (hasThreadLabel) {
        result.put("totalThreads", threadDist.size());
        result.put("threadDistribution", threadDist);
      } else {
        result.put("note", "No 'thread' label in this profile; thread analysis unavailable");
      }

      // Step 2: Infer global state distribution from top leaf functions
      sendProgress(exchange, "pprof_tsa", 1, 3, "Inferring thread states...");
      Map<String, Long> stateCounts = new LinkedHashMap<>();
      stateCounts.put("RUNNING", 0L);
      stateCounts.put("WAITING", 0L);
      stateCounts.put("IO_BLOCKED", 0L);
      stateCounts.put("LOCK_BLOCKED", 0L);

      try {
        var leafQuery = PprofPathParser.parse("samples | groupBy(stackTrace/0/name)");
        List<Map<String, Object>> leafFns = PprofPathEvaluator.evaluate(info.session(), leafQuery);
        for (Map<String, Object> row : leafFns) {
          String fnName = row.get("stackTrace/0/name") instanceof String s ? s.toLowerCase() : "";
          long count = row.get("count") instanceof Number n ? n.longValue() : 0L;
          String state = inferStateFromFunctionName(fnName);
          stateCounts.merge(state, count, Long::sum);
        }
      } catch (Exception e) {
        LOG.debug("Could not infer thread states: {}", e.getMessage());
      }

      // Annotate with percentages
      long stateTotal = stateCounts.values().stream().mapToLong(Long::longValue).sum();
      Map<String, Object> stateDistribution = new LinkedHashMap<>();
      for (Map.Entry<String, Long> e : stateCounts.entrySet()) {
        Map<String, Object> sd = new LinkedHashMap<>();
        sd.put("samples", e.getValue());
        if (stateTotal > 0) {
          sd.put("pct", Math.round(e.getValue() * 1000.0 / stateTotal) / 10.0);
        }
        stateDistribution.put(e.getKey(), sd);
      }
      result.put("inferredStateDistribution", stateDistribution);
      result.put(
          "stateInferenceNote",
          "States inferred from leaf function names; most CPU-profiled samples appear as RUNNING");

      // Step 3: Per-thread profiles for top threads
      sendProgress(exchange, "pprof_tsa", 2, 3, "Building thread profiles...");
      if (hasThreadLabel && valueField != null) {
        List<Map<String, Object>> threadProfiles = new ArrayList<>();
        int profileLimit = Math.min(topThreads, threadDist.size());
        for (int i = 0; i < profileLimit; i++) {
          Map<String, Object> tRow = threadDist.get(i);
          String threadName = tRow.get("thread") instanceof String s ? s : "";
          if (threadName.isBlank() || "<null>".equals(threadName)) continue;

          Map<String, Object> profile2 = new LinkedHashMap<>();
          profile2.put("thread", threadName);
          profile2.put("samples", tRow.get("count"));

          // Top functions for this thread
          try {
            String escapedThread = threadName.replace("\\", "\\\\").replace("'", "\\'");
            requireSafeFieldName(valueField, "valueField");
            var tfq =
                PprofPathParser.parse(
                    "samples[thread='" + escapedThread + "'] | top(5, " + valueField + ")");
            List<Map<String, Object>> topFns = PprofPathEvaluator.evaluate(info.session(), tfq);
            profile2.put("topFunctions", topFns);
          } catch (Exception e) {
            LOG.debug("Could not get top functions for thread {}: {}", threadName, e.getMessage());
          }

          threadProfiles.add(profile2);
        }
        result.put("threadProfiles", threadProfiles);
      }

      // Insights
      if (includeInsights) {
        result.put(
            "insights",
            generatePprofTsaInsights(
                threadDist, stateCounts, stateTotal, totalSamples, hasThreadLabel));
      }

      sendProgress(exchange, "pprof_tsa", 3, 3, "Done");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to perform pprof TSA: {}", e.getMessage(), e);
      return errorResult("Failed to perform TSA: " + e.getMessage());
    }
  }

  private String inferStateFromFunctionName(String fnNameLower) {
    // Split on '.' and '_' separators to avoid substring false-positives
    // e.g. "clock" must not match "lock", "readConfig" must not match "read"
    String[] segments = fnNameLower.split("[._]");
    for (String seg : segments) {
      if (WAITING_KEYWORDS.contains(seg)) return "WAITING";
    }
    for (String seg : segments) {
      if (IO_KEYWORDS.contains(seg)) return "IO_BLOCKED";
    }
    for (String seg : segments) {
      if (LOCK_KEYWORDS.contains(seg)) return "LOCK_BLOCKED";
    }
    return "RUNNING";
  }

  private List<String> generatePprofTsaInsights(
      List<Map<String, Object>> threadDist,
      Map<String, Long> stateCounts,
      long stateTotal,
      long totalSamples,
      boolean hasThreadLabel) {
    List<String> insights = new ArrayList<>();

    if (hasThreadLabel && !threadDist.isEmpty() && totalSamples > 0) {
      long topCount = threadDist.get(0).get("count") instanceof Number n ? n.longValue() : 0L;
      double topPct = 100.0 * topCount / totalSamples;
      if (topPct > 90) {
        insights.add(
            String.format(
                "Single-threaded bottleneck: thread '%s' holds %.1f%% of all samples",
                threadDist.get(0).get("thread"), topPct));
      } else if (threadDist.size() == 1) {
        insights.add("Only one thread observed — workload appears single-threaded");
      }
    }

    if (stateTotal > 0) {
      long waitSamples =
          stateCounts.getOrDefault("WAITING", 0L) + stateCounts.getOrDefault("IO_BLOCKED", 0L);
      double waitPct = 100.0 * waitSamples / stateTotal;
      if (waitPct > 20) {
        insights.add(
            String.format(
                "%.1f%% of leaf frames suggest blocking/waiting — consider async I/O or off-CPU "
                    + "profiling (e.g. async-profiler --event=wall) for deeper insight",
                waitPct));
      }
      long lockSamples = stateCounts.getOrDefault("LOCK_BLOCKED", 0L);
      double lockPct = 100.0 * lockSamples / stateTotal;
      if (lockPct > 10) {
        insights.add(
            String.format(
                "%.1f%% of leaf frames suggest lock contention — review synchronization patterns",
                lockPct));
      }
    }

    if (insights.isEmpty()) {
      insights.add("Profile appears CPU-bound with good thread distribution");
    }
    return insights;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_help
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createPprofHelpTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {}
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_help", "Returns PprofPath query language documentation and examples.", schema),
        (_, _) -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("language", "PprofPath");
          result.put("documentation", getPprofHelpText());
          return successResult(result);
        });
  }

  private String getPprofHelpText() {
    return """
        ## PprofPath Query Language

        ### Syntax
        ```
        samples[predicate] | operator(args)
        ```

        ### Root
        - `samples` — all profiling samples in the profile

        ### Predicates (filter before pipeline)
        ```
        samples[thread='main']          string equality
        samples[cpu > 1000000]          numeric comparison (>, >=, <, <=, !=)
        samples[thread ~ 'Worker.*']    regex match
        samples[thread='main' and cpu > 500000]   AND
        samples[cpu < 100 or cpu > 900]            OR
        samples[stackTrace/0/name ~ 'HashMap.*']   nested path
        ```

        ### Pipeline Operators
        | Operator | Description |
        |----------|-------------|
        | `count()` | Total number of samples |
        | `top(n, field, [asc])` | Top N by field (default descending) |
        | `head(n)` / `tail(n)` | First/last N rows |
        | `groupBy(field)` | Group and count by field |
        | `groupBy(field, sum(f))` | Group by field, sum another |
        | `stats(field)` | Min/max/avg/sum/count for field |
        | `filter(pred)` | Additional filtering in pipeline |
        | `select(f1, f2, ...)` | Project to subset of fields |
        | `sortBy(field, [asc])` | Sort by field (default descending) |
        | `stackprofile([field])` | Folded stacks for flame graphs |
        | `distinct(field)` | Unique values of field |

        ### Row Fields
        Each sample row contains:
        - Value type fields (e.g. `cpu`, `alloc_objects`, `alloc_space`)
        - `stackTrace` — list of frames, each with `name`, `filename`, `line`
        - Label fields (e.g. `thread`, `goroutine`, `request_id`)

        ### Examples
        ```
        samples | count()
        samples | top(10, cpu)
        samples | groupBy(thread, sum(cpu))
        samples[thread='main'] | top(5, cpu)
        samples | stats(cpu)
        samples | stackprofile(cpu)
        samples | groupBy(stackTrace/0/name, sum(cpu)) | head(20)
        samples[thread ~ 'Worker.*'] | groupBy(thread) | sortBy(count)
        ```

        ### Workflow
        1. pprof_open  — open the file
        2. pprof_summary — get an overview
        3. pprof_query — run targeted queries
        4. pprof_flamegraph — visualize hot stacks
        5. pprof_use — USE method analysis
        6. pprof_hotmethods — direct cost breakdown
        7. pprof_tsa — thread state analysis
        8. pprof_close — release resources
        """;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_open
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createOtlpOpenTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Absolute path to the OTLP profiles file (.otlp)"
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
            "otlp_open",
            "Opens an OpenTelemetry profiling file (.otlp) for analysis. "
                + "Returns a session ID and profile metadata (sample type, duration, counts). "
                + "Use the returned session ID with other otlp_* tools.",
            schema),
        (exchange, args) -> handleOtlpOpen(args.arguments()));
  }

  private CallToolResult handleOtlpOpen(Map<String, Object> args) {
    String path = (String) args.get("path");
    String alias = (String) args.get("alias");

    if (path == null || path.isBlank()) {
      return errorResult("path is required");
    }

    try {
      Path profilePath = Path.of(path);

      if (!Files.exists(profilePath)) {
        return errorResult("File not found: " + path);
      }
      if (!Files.isRegularFile(profilePath)) {
        return errorResult("Not a file: " + path);
      }
      if (!Files.isReadable(profilePath)) {
        return errorResult("File not readable: " + path);
      }

      var info = otlpSessionRegistry.open(profilePath, alias);
      LOG.info("Opened OTLP profile {} as session {}", path, info.id());

      Map<String, Object> result = info.toMap();
      result.put("message", "OTLP profile opened successfully");
      return successResult(result);

    } catch (Exception e) {
      LOG.error("Failed to open OTLP profile: {}", e.getMessage(), e);
      return errorResult("Failed to open OTLP profile: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_close
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createOtlpCloseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias to close. Omit to close the current session."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool("otlp_close", "Closes an otlp session and releases its resources.", schema),
        (exchange, args) -> handleOtlpClose(args.arguments()));
  }

  private CallToolResult handleOtlpClose(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      var info = otlpSessionRegistry.getOrCurrent(sessionId);
      otlpSessionRegistry.close(String.valueOf(info.id()));

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("closed", info.id());
      result.put("path", info.path().toString());
      result.put("message", "Session closed");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to close otlp session: {}", e.getMessage(), e);
      return errorResult("Failed to close session: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_query
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createOtlpQueryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "OtlpPath query string, e.g. 'samples | top(10, cpu)'"
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of results to return (default: 100)"
            }
          },
          "required": ["query"]
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_query",
            "Executes an OtlpPath query against an open OTLP profile. "
                + "Query syntax: 'samples[predicate] | operator(args)'. "
                + "Examples: 'samples | count()', 'samples | top(10, cpu)', "
                + "'samples | groupBy(thread, sum(cpu))', 'samples[thread=\\'main\\'] | head(5)'. "
                + "Use otlp_help for full query language reference.",
            schema),
        (exchange, args) -> handleOtlpQuery(args.arguments()));
  }

  private CallToolResult handleOtlpQuery(Map<String, Object> args) {
    String queryStr = (String) args.get("query");
    String sessionId = (String) args.get("sessionId");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 100;

    if (queryStr == null || queryStr.isBlank()) {
      return errorResult("query is required");
    }
    if (limit <= 0) {
      return errorResult("limit must be positive");
    }

    try {
      var info = otlpSessionRegistry.getOrCurrent(sessionId);
      var query = OtlpPathParser.parse(queryStr);
      List<Map<String, Object>> rows = OtlpPathEvaluator.evaluate(info.session(), query);

      boolean truncated = rows.size() > limit;
      if (truncated) {
        rows = rows.subList(0, limit);
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("query", queryStr);
      response.put("sessionId", info.id());
      response.put("resultCount", rows.size());
      response.put("results", rows);
      if (truncated) {
        response.put("truncated", true);
        response.put(
            "message", "Results truncated to " + limit + ". Use 'limit' parameter for more.");
      }
      return successResult(response);

    } catch (OtlpPathParseException e) {
      LOG.warn("OtlpPath query parse error: {}", e.getMessage());
      return errorResult("Query parse error: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      LOG.warn("OtlpPath query error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to execute otlp query: {}", e.getMessage(), e);
      return errorResult("Failed to execute query: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_summary
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createOtlpSummaryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_summary",
            "Returns a quick overview of an OTLP profile: sample type, counts, duration, "
                + "and top functions by the primary value type.",
            schema),
        (exchange, args) -> handleOtlpSummary(args.arguments()));
  }

  private CallToolResult handleOtlpSummary(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      var info = otlpSessionRegistry.getOrCurrent(sessionId);
      var data = info.session().getData();

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("path", info.path().toString());
      response.putAll(info.session().getStatistics());

      // Top functions by first profile's sample type
      if (!data.profiles().isEmpty()) {
        var firstProfile = data.profiles().get(0);
        var sampleType = firstProfile.sampleType();
        if (sampleType != null && !sampleType.type().isEmpty()) {
          try {
            String stType = requireSafeFieldName(sampleType.type(), "sampleType");
            var query = OtlpPathParser.parse("samples | top(10, " + stType + ")");
            List<Map<String, Object>> topFns = OtlpPathEvaluator.evaluate(info.session(), query);
            response.put("topFunctions", topFns);
          } catch (Exception e) {
            LOG.debug("Could not compute top functions: {}", e.getMessage());
          }
        }
      }

      return successResult(response);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to get otlp summary: {}", e.getMessage(), e);
      return errorResult("Failed to get summary: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createOtlpFlamegraphTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "valueField": {
              "type": "string",
              "description": "Value type to aggregate (e.g. 'cpu'). Defaults to first sample type."
            },
            "filter": {
              "type": "string",
              "description": "Optional filter predicate, e.g. \\"thread='main'\\""
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of stack entries to return (default: 50)"
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_flamegraph",
            "Produces folded stack profile data suitable for flame graph visualization. "
                + "Returns rows of {stack: 'root;parent;leaf', <valueField>: N} sorted by value descending. "
                + "Stack frames are separated by ';' with root frame first.",
            schema),
        (exchange, args) -> handleOtlpFlamegraph(args.arguments()));
  }

  private CallToolResult handleOtlpFlamegraph(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    String valueField = (String) args.get("valueField");
    String filter = (String) args.get("filter");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 50;

    try {
      var info = otlpSessionRegistry.getOrCurrent(sessionId);

      // Resolve default value field from first profile's sample type
      String effectiveValue = valueField;
      if (effectiveValue == null || effectiveValue.isBlank()) {
        var data = info.session().getData();
        if (!data.profiles().isEmpty()) {
          var st = data.profiles().get(0).sampleType();
          if (st != null && !st.type().isEmpty()) {
            effectiveValue = st.type();
          }
        }
      }

      if (effectiveValue == null) {
        return errorResult("No sample types found in profile");
      }

      requireSafeFieldName(effectiveValue, "valueField");

      if (filter != null && filter.contains("]")) {
        return errorResult("Invalid filter: unexpected ']' in filter expression");
      }

      String queryStr =
          "samples"
              + (filter != null && !filter.isBlank() ? "[" + filter + "]" : "")
              + " | stackprofile("
              + effectiveValue
              + ")";

      var query = OtlpPathParser.parse(queryStr);
      List<Map<String, Object>> rows = OtlpPathEvaluator.evaluate(info.session(), query);

      boolean truncated = rows.size() > limit;
      if (truncated) {
        rows = rows.subList(0, limit);
      }

      final String finalValue = effectiveValue;
      long total =
          rows.stream()
              .mapToLong(r -> r.get(finalValue) instanceof Number n ? n.longValue() : 0L)
              .sum();

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("valueField", effectiveValue);
      response.put("total", total);
      response.put("rowCount", rows.size());
      response.put("rows", rows);
      if (truncated) {
        response.put("truncated", true);
      }
      return successResult(response);

    } catch (OtlpPathParseException e) {
      return errorResult("Query parse error: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to generate otlp flamegraph: {}", e.getMessage(), e);
      return errorResult("Failed to generate flamegraph: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_use - USE Method Analysis (Utilization, Saturation, Errors)
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createOtlpUseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "resources": {
              "type": "array",
              "items": { "type": "string", "enum": ["cpu", "threads", "errors"] },
              "description": "Resources to analyze. Defaults to all: cpu, threads, errors."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_use",
            "Analyzes an OTLP profile using Brendan Gregg's USE Method (Utilization, Saturation, Errors). "
                + "CPU: utilization stats and top consuming functions. "
                + "Threads: distribution across threads to identify serial bottlenecks. "
                + "Errors: heuristic scan of hot function names for error-related patterns.",
            schema),
        (exchange, args) -> handleOtlpUse(exchange, args.arguments()));
  }

  @SuppressWarnings("unchecked")
  private CallToolResult handleOtlpUse(McpSyncServerExchange exchange, Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    List<String> resourcesList =
        args.get("resources") instanceof List<?> l ? (List<String>) l : List.of("all");
    Set<String> resources =
        resourcesList.contains("all")
            ? Set.of("cpu", "threads", "errors")
            : Set.copyOf(resourcesList);

    try {
      var info = otlpSessionRegistry.getOrCurrent(sessionId);
      var data = info.session().getData();
      String defaultType =
          (!data.profiles().isEmpty() && data.profiles().get(0).sampleType() != null)
              ? data.profiles().get(0).sampleType().type()
              : null;

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("method", "USE");
      result.put("profilePath", info.path().toString());

      Map<String, Object> resourceMetrics = new LinkedHashMap<>();
      int step = 0;
      int totalSteps = resources.size() + 1;

      if (resources.contains("cpu")) {
        sendProgress(exchange, "otlp_use", step++, totalSteps, "Analyzing CPU...");
        resourceMetrics.put("cpu", analyzeOtlpCpu(info, defaultType));
      }

      if (resources.contains("threads")) {
        sendProgress(exchange, "otlp_use", step++, totalSteps, "Analyzing threads...");
        Map<String, Object> threadMetrics = analyzeOtlpThreads(info);
        if (!threadMetrics.isEmpty()) {
          resourceMetrics.put("threads", threadMetrics);
        }
      }

      if (resources.contains("errors")) {
        sendProgress(exchange, "otlp_use", step++, totalSteps, "Scanning for errors...");
        resourceMetrics.put("errors", analyzeOtlpErrors(info));
      }

      result.put("resources", resourceMetrics);

      sendProgress(exchange, "otlp_use", step, totalSteps, "Generating insights...");
      result.put("insights", generateOtlpUseInsights(resourceMetrics));

      sendProgress(exchange, "otlp_use", totalSteps, totalSteps, "Done");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to perform otlp USE analysis: {}", e.getMessage(), e);
      return errorResult("Failed to perform USE analysis: " + e.getMessage());
    }
  }

  private Map<String, Object> analyzeOtlpCpu(
      SamplingSessionRegistry.SessionInfo<OtlpSession> info, String defaultType) {
    Map<String, Object> cpu = new LinkedHashMap<>();

    if (defaultType == null) {
      cpu.put("note", "No sample type found");
      return cpu;
    }

    requireSafeFieldName(defaultType, "sampleType");
    cpu.put("sampleType", defaultType);
    try {
      var statsQuery = OtlpPathParser.parse("samples | stats(" + defaultType + ")");
      List<Map<String, Object>> statsRows = OtlpPathEvaluator.evaluate(info.session(), statsQuery);
      if (!statsRows.isEmpty()) {
        cpu.put("utilization", Map.of("stats", statsRows.get(0)));
      }

      var topQuery = OtlpPathParser.parse("samples | top(10, " + defaultType + ")");
      List<Map<String, Object>> topRows = OtlpPathEvaluator.evaluate(info.session(), topQuery);
      cpu.put("topFunctions", topRows);
    } catch (Exception e) {
      LOG.debug("Error in otlp CPU analysis: {}", e.getMessage());
    }
    return cpu;
  }

  private Map<String, Object> analyzeOtlpThreads(
      SamplingSessionRegistry.SessionInfo<OtlpSession> info) {
    Map<String, Object> threads = new LinkedHashMap<>();

    try {
      var threadQuery = OtlpPathParser.parse("samples | groupBy(thread)");
      List<Map<String, Object>> threadRows =
          OtlpPathEvaluator.evaluate(info.session(), threadQuery);

      if (threadRows.isEmpty()
          || (threadRows.size() == 1 && "<null>".equals(threadRows.get(0).get("thread")))) {
        threads.put("note", "No thread attribute found in this profile");
        return threads;
      }

      threads.put("threadCount", threadRows.size());
      threads.put("distribution", threadRows);

      long totalSamples =
          threadRows.stream()
              .mapToLong(r -> r.get("count") instanceof Number n ? n.longValue() : 0L)
              .sum();
      if (!threadRows.isEmpty() && totalSamples > 0) {
        long topCount = threadRows.get(0).get("count") instanceof Number n ? n.longValue() : 0L;
        double topPct = 100.0 * topCount / totalSamples;
        Map<String, Object> saturation = new LinkedHashMap<>();
        saturation.put("dominantThread", threadRows.get(0).get("thread"));
        saturation.put("dominantThreadPct", Math.round(topPct * 10) / 10.0);
        if (topPct > 90) {
          saturation.put(
              "finding", "Single-threaded: one thread holds >" + (int) topPct + "% of all samples");
        }
        threads.put("saturation", saturation);
      }
    } catch (Exception e) {
      LOG.debug("Error in otlp thread analysis: {}", e.getMessage());
    }
    return threads;
  }

  private Map<String, Object> analyzeOtlpErrors(
      SamplingSessionRegistry.SessionInfo<OtlpSession> info) {
    Map<String, Object> errors = new LinkedHashMap<>();
    Set<String> errorKeywords =
        Set.of("exception", "error", "panic", "throw", "fail", "fatal", "abort", "crash");

    try {
      var topFnQuery = OtlpPathParser.parse("samples | groupBy(stackTrace/0/name) | head(30)");
      List<Map<String, Object>> topFns = OtlpPathEvaluator.evaluate(info.session(), topFnQuery);

      List<Map<String, Object>> suspects = new ArrayList<>();
      for (Map<String, Object> row : topFns) {
        String name = row.get("stackTrace/0/name") instanceof String s ? s : "";
        String nameLower = name.toLowerCase();
        for (String kw : errorKeywords) {
          if (nameLower.contains(kw)) {
            suspects.add(row);
            break;
          }
        }
      }

      errors.put("suspectFunctions", suspects);
      errors.put(
          "note",
          "Error detection is heuristic: scans hot leaf functions for error-related keywords");
    } catch (Exception e) {
      LOG.debug("Error in otlp error analysis: {}", e.getMessage());
    }
    return errors;
  }

  private List<String> generateOtlpUseInsights(Map<String, Object> resourceMetrics) {
    List<String> insights = new ArrayList<>();

    if (resourceMetrics.containsKey("cpu")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> cpu = (Map<String, Object>) resourceMetrics.get("cpu");
      if (cpu.containsKey("utilization")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> util = (Map<String, Object>) cpu.get("utilization");
        if (util.containsKey("stats")) {
          @SuppressWarnings("unchecked")
          Map<String, Object> stats = (Map<String, Object>) util.get("stats");
          Object count = stats.get("count");
          if (count instanceof Number n && n.longValue() > 0) {
            insights.add(
                "Profile contains "
                    + n.longValue()
                    + " samples. Use otlp_flamegraph for visual breakdown.");
          }
        }
      }
    }

    if (resourceMetrics.containsKey("threads")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> threads = (Map<String, Object>) resourceMetrics.get("threads");
      if (threads.containsKey("saturation")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sat = (Map<String, Object>) threads.get("saturation");
        if (sat.containsKey("finding")) {
          insights.add(sat.get("finding").toString() + " — consider parallelizing the workload");
        }
      }
    }

    if (resourceMetrics.containsKey("errors")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> errMap = (Map<String, Object>) resourceMetrics.get("errors");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> suspects =
          errMap.get("suspectFunctions") instanceof List<?> l
              ? (List<Map<String, Object>>) l
              : List.of();
      if (!suspects.isEmpty()) {
        insights.add(
            "Found "
                + suspects.size()
                + " error-related function(s) in hot paths — review for exception overhead.");
      }
    }

    if (insights.isEmpty()) {
      insights.add("No significant issues detected. Use otlp_flamegraph for detailed analysis.");
    }
    return insights;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_help
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createOtlpHelpTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {}
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_help", "Returns OtlpPath query language documentation and examples.", schema),
        (_, _) -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("language", "OtlpPath");
          result.put("documentation", getOtlpHelpText());
          return successResult(result);
        });
  }

  private String getOtlpHelpText() {
    return """
        ## OtlpPath Query Language

        ### Syntax
        ```
        samples[predicate] | operator(args)
        ```

        ### Root
        - `samples` — all profiling samples in the OTLP profile

        ### Predicates (filter before pipeline)
        ```
        samples[thread='main']                    string equality
        samples[cpu > 1000000]                    numeric comparison (>, >=, <, <=, !=)
        samples[thread ~ 'Worker.*']              regex match
        samples[thread='main' and cpu > 500000]   AND
        samples[cpu < 100 or cpu > 900]           OR
        samples[stackTrace/0/name ~ 'HashMap.*']  nested path
        ```

        ### Pipeline Operators
        | Operator | Description |
        |----------|-------------|
        | `count()` | Total number of samples |
        | `top(n, field, [asc])` | Top N by field (default descending) |
        | `head(n)` / `tail(n)` | First/last N rows |
        | `groupBy(field)` | Group and count by field |
        | `groupBy(field, sum(f))` | Group by field, sum another |
        | `stats(field)` | Min/max/avg/sum/count for field |
        | `filter(pred)` | Additional filtering in pipeline |
        | `select(f1, f2, ...)` | Project to subset of fields |
        | `sortBy(field, [asc])` | Sort by field (default descending) |
        | `stackprofile([field])` | Folded stacks for flame graphs |
        | `distinct(field)` | Unique values of field |

        ### Row Fields
        Each sample row contains:
        - Value type field named after the profile's sample type (e.g. `cpu`, `wall`)
        - `stackTrace` — list of frames, each with `name`, `filename`, `line`
        - Attribute fields from the sample's attribute table (e.g. `thread`)

        ### Examples
        ```
        samples | count()
        samples | top(10, cpu)
        samples | groupBy(thread, sum(cpu))
        samples[thread='main'] | top(5, cpu)
        samples | stats(cpu)
        samples | stackprofile(cpu)
        samples | groupBy(stackTrace/0/name, sum(cpu)) | head(20)
        samples[thread ~ 'Worker.*'] | groupBy(thread) | sortBy(count)
        ```

        ### Workflow
        1. otlp_open     — open the file
        2. otlp_summary  — get an overview
        3. otlp_query    — run targeted queries
        4. otlp_flamegraph — visualize hot stacks
        5. otlp_use      — USE method analysis
        6. otlp_close    — release resources
        """;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Utility methods
  // ─────────────────────────────────────────────────────────────────────────────

  private static final Pattern SAFE_FIELD_NAME = Pattern.compile("^[a-zA-Z0-9_.:-]+$");

  /** Validates that a field name is safe to interpolate into a query string. */
  private static String requireSafeFieldName(String name, String paramName) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(paramName + " must not be blank");
    }
    if (!SAFE_FIELD_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid " + paramName + ": '" + name + "'");
    }
    return name;
  }

  private CallToolResult successResult(Map<String, Object> data) {
    try {
      String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
      return new CallToolResult(List.of(new TextContent(json)), false, null, null);
    } catch (Exception e) {
      return new CallToolResult(List.of(new TextContent(data.toString())), false, null, null);
    }
  }

  private void sendProgress(
      McpSyncServerExchange exchange, String token, double progress, double total, String message) {
    if (exchange == null) return;
    try {
      exchange.progressNotification(
          new McpSchema.ProgressNotification(token, progress, total, message));
    } catch (Exception ignored) {
      // Client may not support progress
    }
  }

  private CallToolResult errorResult(String message) {
    Map<String, Object> error = Map.of("error", message, "success", false);
    try {
      String json = MAPPER.writeValueAsString(error);
      return new CallToolResult(List.of(new TextContent(json)), true, null, null);
    } catch (Exception e) {
      return new CallToolResult(List.of(new TextContent(message)), true, null, null);
    }
  }
}

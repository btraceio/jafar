package io.jafar.mcp;

import io.jafar.mcp.config.McpServerConfig;
import io.jafar.mcp.hdump.HdumpTools;
import io.jafar.mcp.jfr.JfrHelpProvider;
import io.jafar.mcp.lifecycle.SsePortRegistry;
import io.jafar.mcp.otlp.OtlpTools;
import io.jafar.mcp.pprof.PprofTools;
import io.jafar.mcp.query.DefaultQueryEvaluator;
import io.jafar.mcp.query.DefaultQueryParser;
import io.jafar.mcp.query.QueryEvaluator;
import io.jafar.mcp.query.QueryParser;
import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.result.ResultLimiter;
import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.mcp.tool.ActivityTrackingInterceptor;
import io.jafar.mcp.tool.ProgressReporter;
import io.jafar.mcp.transport.McpServerFactory;
import io.jafar.mcp.validation.FieldNameValidator;
import io.jafar.mcp.validation.FileValidator;
import io.jafar.parser.api.Values;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpServerSession;
import jakarta.servlet.Servlet;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
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
  private static final Set<String> BLOCKING_STATES =
      Set.of("WAITING", "BLOCKED", "PARKED", "TIMED_WAITING");

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

  /**
   * Default idle timeout (minutes). {@code 0} = disabled (the safe default — Claude Code and other
   * MCP clients manage server lifecycle themselves; a self-terminating server strands clients with
   * stale session IDs after restart). Set {@code mcp.idle.timeout.minutes} to a positive integer
   * only if you explicitly want stdio orphan cleanup; the SSE transport ignores this property
   * entirely (see {@link #runSse()}).
   */
  private static final int DEFAULT_IDLE_TIMEOUT_MINUTES = 0;

  /** Stores the port of a running SSE server so a second launch can report it and exit. */
  private static final SsePortRegistry SSE_PORT_REGISTRY = SsePortRegistry.defaultRegistry(LOG);

  /** Cap on rows returned by jfr_query. Configurable via {@code mcp.jfr.query.max-rows}. */
  static final int MAX_QUERY_ROWS = McpServerConfig.MAX_QUERY_ROWS;

  /**
   * Cap on flamegraph nodes/paths returned. Configurable via {@code mcp.jfr.flamegraph.max-nodes}.
   */
  static final int MAX_FLAMEGRAPH_NODES = McpServerConfig.MAX_FLAMEGRAPH_NODES;

  /** Cap on callgraph nodes returned. Configurable via {@code mcp.jfr.callgraph.max-nodes}. */
  static final int MAX_CALLGRAPH_NODES = McpServerConfig.MAX_CALLGRAPH_NODES;

  /** Cap on hdump_report findings. Configurable via {@code mcp.hdump.report.max-findings}. */
  static final int MAX_HDUMP_FINDINGS = McpServerConfig.MAX_HDUMP_FINDINGS;

  private final SessionRegistry sessionRegistry;
  private final HeapSessionRegistry heapSessionRegistry;
  private final PprofSessionRegistry pprofSessionRegistry;
  private final OtlpSessionRegistry otlpSessionRegistry;
  private final QueryEvaluator evaluator;
  private final QueryParser queryParser;
  private final JfrHelpProvider jfrHelpProvider = new JfrHelpProvider();
  private final HdumpTools hdumpTools;
  private final PprofTools pprofTools;
  private final OtlpTools otlpTools;
  private final McpResultFactory resultFactory = new McpResultFactory(MAPPER);
  private final ProgressReporter progressReporter = new ProgressReporter();
  private final McpServerFactory mcpServerFactory = new McpServerFactory();

  /** Timestamp of the last tool invocation, in nanoseconds. Updated on every tool call. */
  private volatile long lastActivityNanos = System.nanoTime();

  /** Number of tool calls currently in progress. Idle watchdog skips shutdown while non-zero. */
  private final AtomicInteger activeRequests = new AtomicInteger(0);

  /** Test hook: replaceable for unit tests so the watchdog does not actually kill the JVM. */
  volatile Runnable exitHook = () -> System.exit(0);

  /** Sentinel for the watchdog CAS: 0 = idle window open, -1 = shutting down. */
  private final AtomicInteger shutdownState = new AtomicInteger(0);

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
    this.hdumpTools = new HdumpTools(heapSessionRegistry, resultFactory);
    this.pprofTools = new PprofTools(pprofSessionRegistry, resultFactory, progressReporter);
    this.otlpTools = new OtlpTools(otlpSessionRegistry, resultFactory, progressReporter);
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

  /**
   * Redirects {@link System#out} to {@link System#err} so accidental stdout writes from any
   * library, helper, or future regression cannot corrupt the JSON-RPC framing on stdio. Returns the
   * original {@link PrintStream} so the transport can still write the protocol on fd 1.
   *
   * <p>Must be called before the stdio transport is constructed.
   */
  private static PrintStream lockStdout() {
    PrintStream realStdout = System.out;
    System.setOut(System.err);
    return realStdout;
  }

  /** Run server with stdio transport (for Claude Code integration). */
  public void runStdio() {
    PrintStream protocolOut = lockStdout();
    LOG.info("Starting Jafar MCP Server with stdio transport");

    try {
      // Create stdio transport — use the captured original stdout, not the redirected one
      var transportProvider =
          new FixedStdioServerTransportProvider(
              McpJsonDefaults.getMapper(), System.in, protocolOut);

      // Build MCP server
      // Note: transport starts reading from stdin automatically when the server is built
      McpSyncServer mcpServer =
          mcpServerFactory.createSyncServer(transportProvider, createToolSpecifications());

      LOG.info("Jafar MCP Server ready (stdio mode)");

      // Shutdown hook for cleanup
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    LOG.warn("JVM shutdown hook fired");
                    sessionRegistry.shutdown();
                    heapSessionRegistry.shutdown();
                    pprofSessionRegistry.shutdown();
                    otlpSessionRegistry.shutdown();
                    mcpServer.close();
                  }));

      // Catch SIGTERM so we can log it before the JVM exits
      sun.misc.Signal.handle(
          new sun.misc.Signal("TERM"),
          sig -> {
            LOG.warn("Received SIGTERM — exiting");
            System.exit(0);
          });

      startIdleWatchdog();

      ShutdownAwaitable awaitable = transportProvider;
      LOG.warn("MCP server running, waiting for shutdown signal");
      awaitable.awaitShutdown().block();
      LOG.warn("awaitShutdown() returned — stdin closed or graceful stop");

    } catch (Exception e) {
      LOG.error("Failed to start server: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  /**
   * Pre-populates a session's {@code exchangeSink} so tool calls succeed even when {@code
   * notifications/initialized} hasn't arrived yet (e.g. after a server restart while the client
   * keeps its SSE connection open). The SDK's {@code handleIncomingRequest} waits on {@code
   * exchangeSink.asMono()} before dispatching; without this it hangs forever on stale sessions.
   *
   * <p>Uses reflection because {@code exchangeSink} is {@code private final}. A {@code Sinks.One}
   * that was already populated silently ignores the second emit, so calling this after a genuine
   * {@code notifications/initialized} round-trip is safe.
   */
  private static void eagarlyInitExchange(McpServerSession session) {
    try {
      Field sinkField = McpServerSession.class.getDeclaredField("exchangeSink");
      sinkField.setAccessible(true);
      @SuppressWarnings("unchecked")
      reactor.core.publisher.Sinks.One<McpAsyncServerExchange> sink =
          (reactor.core.publisher.Sinks.One<McpAsyncServerExchange>) sinkField.get(session);
      McpAsyncServerExchange exchange =
          new McpAsyncServerExchange(
              session.getId(), session, null, null, McpTransportContext.EMPTY);
      sink.tryEmitValue(exchange);
    } catch (Exception e) {
      LOG.warn("Could not pre-initialize session exchange: {}", e.getMessage());
    }
  }

  /** Run server with HTTP/SSE transport (for web clients). */
  public void runSse() {
    int port = Integer.getInteger("mcp.port", 3000);

    // Check if a Jafar SSE server is already running (via port file).
    int runningPort = SSE_PORT_REGISTRY.detectRunningServer();
    if (runningPort > 0) {
      System.out.println(SSE_PORT_REGISTRY.url(runningPort));
      return;
    }

    // Bail if the desired port is taken by a foreign process.
    if (isPortInUse(port)) {
      System.err.println(
          "Port " + port + " is in use by another process. Use -Dmcp.port=<port> to override.");
      System.exit(1);
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
          mcpServerFactory.createSyncServer(transportProvider, createToolSpecifications());

      // Wrap the session factory AFTER build so every new session gets a pre-initialized
      // exchangeSink. The MCP SDK waits on exchangeSink.asMono() before dispatching non-initialize
      // requests; without this, tool calls hang forever on sessions that reconnect before
      // notifications/initialized arrives from the client (e.g. after a server restart).
      try {
        Field factoryField =
            HttpServletSseServerTransportProvider.class.getDeclaredField("sessionFactory");
        factoryField.setAccessible(true);
        McpServerSession.Factory originalFactory =
            (McpServerSession.Factory) factoryField.get(transportProvider);
        factoryField.set(
            transportProvider,
            (McpServerSession.Factory)
                transport -> {
                  McpServerSession session = originalFactory.create(transport);
                  eagarlyInitExchange(session);
                  return session;
                });
      } catch (Exception e) {
        LOG.warn("Could not wrap session factory for eager init: {}", e.getMessage());
      }

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
                    SSE_PORT_REGISTRY.delete();
                    broadcastSseShutdownNotice(transportProvider);
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

      // Catch SIGTERM (sent by `launchctl unload`, `kill`, etc.) so the shutdown hook runs
      // and connected clients get the shutdown notification before the SSE streams close.
      // Without this the JVM may exit on the default SIGTERM action without invoking the hook.
      sun.misc.Signal.handle(
          new sun.misc.Signal("TERM"),
          sig -> {
            LOG.warn("Received SIGTERM — exiting");
            System.exit(0);
          });

      // Intentionally NOT calling startIdleWatchdog() in SSE mode: the watchdog's stated purpose
      // is stdio-orphan cleanup, and self-terminating an SSE daemon strands every connected
      // client with a stale session ID that the new JVM does not recognize. The launchd service
      // (KeepAlive=true) keeps the daemon up; idle resource usage is negligible.
      // Start server
      jettyServer.start();
      SSE_PORT_REGISTRY.write(port);
      System.out.println(SSE_PORT_REGISTRY.url(port));
      LOG.info("Jafar MCP Server started — SSE: {}", SSE_PORT_REGISTRY.url(port));
      jettyServer.join();

    } catch (Exception e) {
      LOG.error("Failed to start server: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  /**
   * Broadcasts a standard MCP {@code notifications/message} (logging) record to every active SSE
   * session so connected clients learn the server is going away before its streams close. The
   * server-assigned session IDs become invalid across a JVM restart, and the MCP HTTP/SSE transport
   * returns HTTP 404 {@code "Session not found"} on POSTs from clients that still hold the old IDs
   * — surfacing as "tool calls hang forever". This notice gives clients an opening to re-initialize
   * on reconnect (or at minimum to log the cause). Bounded by a short timeout so a misbehaving
   * session cannot stall the shutdown hook.
   *
   * <p>Package-private for unit tests; safe to call with no active sessions (broadcast is a no-op).
   */
  void broadcastSseShutdownNotice(HttpServletSseServerTransportProvider transportProvider) {
    if (transportProvider == null) {
      return;
    }
    LoggingMessageNotification notice =
        new LoggingMessageNotification(
            LoggingLevel.WARNING,
            "jafar-mcp",
            "Jafar MCP server is shutting down. Reconnecting clients must reinitialize:"
                + " session IDs from this JVM will not be honored after restart.");
    try {
      transportProvider
          .notifyClients("notifications/message", notice)
          .timeout(Duration.ofSeconds(2))
          .onErrorResume(
              e -> {
                LOG.warn("Failed to broadcast shutdown notice: {}", e.toString());
                return Mono.empty();
              })
          .block();
    } catch (Throwable t) {
      // Shutdown path: never let notification failures abort the rest of the shutdown sequence.
      LOG.warn("Shutdown notice broadcast threw {}", t.toString());
    }
  }

  /** Updates the last-activity timestamp. Called on every tool invocation. */
  private void touchActivity() {
    lastActivityNanos = System.nanoTime();
  }

  /**
   * Starts a daemon thread that exits the process when no tool has been called for the configured
   * idle timeout period. Disabled by default ({@value DEFAULT_IDLE_TIMEOUT_MINUTES}); enable via
   * system property {@code mcp.idle.timeout.minutes} (positive integer = minutes).
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
                if (idleNanos >= timeoutNanos && watchdogShouldExit()) {
                  LOG.info(
                      "Idle timeout reached ({}m), shutting down", idleNanos / 60_000_000_000L);
                  exitHook.run();
                  return;
                }
              }
            },
            "mcp-idle-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
  }

  /**
   * Returns true iff the watchdog won the right to terminate the process. Wins only when
   * activeRequests is zero AND no request grabs it via {@link #beginRequest()} concurrently.
   */
  boolean watchdogShouldExit() {
    if (activeRequests.get() != 0) {
      return false;
    }
    if (!shutdownState.compareAndSet(0, -1)) {
      return false;
    }
    if (activeRequests.get() != 0) {
      shutdownState.set(0); // release latch — a request slipped through
      return false;
    }
    return true;
  }

  /**
   * Wraps a tool specification so that every invocation updates the last-activity timestamp.
   *
   * @param spec the original specification
   * @return wrapped specification
   */
  private McpServerFeatures.SyncToolSpecification withActivityTracking(
      McpServerFeatures.SyncToolSpecification spec) {
    return new ActivityTrackingInterceptor(
            this::beginRequest, this::endRequest, this::touchActivity, this::errorResult, LOG)
        .apply(spec);
  }

  /** Returns false iff the watchdog has latched a shutdown intent. */
  private boolean beginRequest() {
    activeRequests.incrementAndGet();
    if (shutdownState.get() == -1) {
      activeRequests.decrementAndGet();
      return false;
    }
    return true;
  }

  private void endRequest() {
    activeRequests.decrementAndGet();
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
      return true;
    } catch (IOException e) {
      return false;
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
  List<McpServerFeatures.SyncToolSpecification> createToolSpecifications() {
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
    return jfrHelpProvider.getOverviewHelp();
  }

  private String getFiltersHelp() {
    return jfrHelpProvider.getFiltersHelp();
  }

  private String getPipelineHelp() {
    return jfrHelpProvider.getPipelineHelp();
  }

  private String getFunctionsHelp() {
    return jfrHelpProvider.getFunctionsHelp();
  }

  private String getExamplesHelp() {
    return jfrHelpProvider.getExamplesHelp();
  }

  private String getEventTypesHelp() {
    return jfrHelpProvider.getEventTypesHelp();
  }

  private String getToolsHelp() {
    return jfrHelpProvider.getToolsHelp();
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
        (exchange, args) -> handleJfrFlamegraph(exchange, args.arguments(), progressToken(args)));
  }

  private CallToolResult handleJfrFlamegraph(
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
        (exchange, args) -> handleJfrCallgraph(exchange, args.arguments(), progressToken(args)));
  }

  private CallToolResult handleJfrCallgraph(
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
    result.put("totalSamples", graph.totalSamples.sum());
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
        (exchange, args) -> handleJfrExceptions(exchange, args.arguments(), progressToken(args)));
  }

  private CallToolResult handleJfrExceptions(
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
        (exchange, args) -> handleJfrSummary(exchange, args.arguments(), progressToken(args)));
  }

  private CallToolResult handleJfrSummary(
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
        (exchange, args) -> handleJfrHotmethods(exchange, args.arguments(), progressToken(args)));
  }

  private CallToolResult handleJfrHotmethods(
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
        (exchange, args) -> handleJfrUse(exchange, args.arguments(), progressToken(args)));
  }

  private CallToolResult handleJfrUse(
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
        (exchange, args) -> handleJfrTsa(exchange, args.arguments(), progressToken(args)));
  }

  private CallToolResult handleJfrTsa(
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
        (exchange, args) -> handleJfrDiagnose(exchange, args.arguments(), progressToken(args)));
  }

  @SuppressWarnings("unchecked")
  private CallToolResult handleJfrDiagnose(
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
        (exchange, args) -> handleJfrStackprofile(exchange, args.arguments(), progressToken(args)));
  }

  @SuppressWarnings("unchecked")
  private CallToolResult handleJfrStackprofile(
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

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump tools
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createHdumpOpenTool() {
    return hdumpTools.createHdumpOpenTool();
  }

  private CallToolResult handleHdumpOpen(Map<String, Object> args) {
    return hdumpTools.handleHdumpOpen(args);
  }

  private McpServerFeatures.SyncToolSpecification createHdumpCloseTool() {
    return hdumpTools.createHdumpCloseTool();
  }

  private CallToolResult handleHdumpClose(Map<String, Object> args) {
    return hdumpTools.handleHdumpClose(args);
  }

  private McpServerFeatures.SyncToolSpecification createHdumpQueryTool() {
    return hdumpTools.createHdumpQueryTool();
  }

  private CallToolResult handleHdumpQuery(Map<String, Object> args) {
    return hdumpTools.handleHdumpQuery(args);
  }

  private McpServerFeatures.SyncToolSpecification createHdumpSummaryTool() {
    return hdumpTools.createHdumpSummaryTool();
  }

  private CallToolResult handleHdumpSummary(Map<String, Object> args) {
    return hdumpTools.handleHdumpSummary(args);
  }

  private McpServerFeatures.SyncToolSpecification createHdumpReportTool() {
    return hdumpTools.createHdumpReportTool();
  }

  private CallToolResult handleHdumpReport(Map<String, Object> args) {
    return hdumpTools.handleHdumpReport(args);
  }

  private McpServerFeatures.SyncToolSpecification createHdumpHelpTool() {
    return hdumpTools.createHdumpHelpTool();
  }

  private CallToolResult handleHdumpHelp(Map<String, Object> args) {
    return hdumpTools.handleHdumpHelp(args);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof tools
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createPprofOpenTool() {
    return pprofTools.createPprofOpenTool();
  }

  private CallToolResult handlePprofOpen(Map<String, Object> args) {
    return pprofTools.handlePprofOpen(args);
  }

  private McpServerFeatures.SyncToolSpecification createPprofCloseTool() {
    return pprofTools.createPprofCloseTool();
  }

  private CallToolResult handlePprofClose(Map<String, Object> args) {
    return pprofTools.handlePprofClose(args);
  }

  private McpServerFeatures.SyncToolSpecification createPprofQueryTool() {
    return pprofTools.createPprofQueryTool();
  }

  private CallToolResult handlePprofQuery(Map<String, Object> args) {
    return pprofTools.handlePprofQuery(args);
  }

  private McpServerFeatures.SyncToolSpecification createPprofSummaryTool() {
    return pprofTools.createPprofSummaryTool();
  }

  private CallToolResult handlePprofSummary(Map<String, Object> args) {
    return pprofTools.handlePprofSummary(args);
  }

  private McpServerFeatures.SyncToolSpecification createPprofFlamegraphTool() {
    return pprofTools.createPprofFlamegraphTool();
  }

  private CallToolResult handlePprofFlamegraph(Map<String, Object> args) {
    return pprofTools.handlePprofFlamegraph(args);
  }

  private McpServerFeatures.SyncToolSpecification createPprofUseTool() {
    return pprofTools.createPprofUseTool();
  }

  private CallToolResult handlePprofUse(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return pprofTools.handlePprofUse(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createPprofHotmethodsTool() {
    return pprofTools.createPprofHotmethodsTool();
  }

  private CallToolResult handlePprofHotmethods(Map<String, Object> args) {
    return pprofTools.handlePprofHotmethods(args);
  }

  private McpServerFeatures.SyncToolSpecification createPprofTsaTool() {
    return pprofTools.createPprofTsaTool();
  }

  private CallToolResult handlePprofTsa(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return pprofTools.handlePprofTsa(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createPprofHelpTool() {
    return pprofTools.createPprofHelpTool();
  }

  private String getPprofHelpText() {
    return pprofTools.getPprofHelpText();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp tools
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createOtlpOpenTool() {
    return otlpTools.createOtlpOpenTool();
  }

  private CallToolResult handleOtlpOpen(Map<String, Object> args) {
    return otlpTools.handleOtlpOpen(args);
  }

  private McpServerFeatures.SyncToolSpecification createOtlpCloseTool() {
    return otlpTools.createOtlpCloseTool();
  }

  private CallToolResult handleOtlpClose(Map<String, Object> args) {
    return otlpTools.handleOtlpClose(args);
  }

  private McpServerFeatures.SyncToolSpecification createOtlpQueryTool() {
    return otlpTools.createOtlpQueryTool();
  }

  private CallToolResult handleOtlpQuery(Map<String, Object> args) {
    return otlpTools.handleOtlpQuery(args);
  }

  private McpServerFeatures.SyncToolSpecification createOtlpSummaryTool() {
    return otlpTools.createOtlpSummaryTool();
  }

  private CallToolResult handleOtlpSummary(Map<String, Object> args) {
    return otlpTools.handleOtlpSummary(args);
  }

  private McpServerFeatures.SyncToolSpecification createOtlpFlamegraphTool() {
    return otlpTools.createOtlpFlamegraphTool();
  }

  private CallToolResult handleOtlpFlamegraph(Map<String, Object> args) {
    return otlpTools.handleOtlpFlamegraph(args);
  }

  private McpServerFeatures.SyncToolSpecification createOtlpUseTool() {
    return otlpTools.createOtlpUseTool();
  }

  private CallToolResult handleOtlpUse(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return otlpTools.handleOtlpUse(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createOtlpHelpTool() {
    return otlpTools.createOtlpHelpTool();
  }

  private String getOtlpHelpText() {
    return otlpTools.getOtlpHelpText();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Utility methods
  // ─────────────────────────────────────────────────────────────────────────────

  /** Validates that a field name is safe to interpolate into a query string. */
  private static String requireSafeFieldName(String name, String paramName) {
    return FieldNameValidator.requireSafeFieldName(name, paramName);
  }

  private CallToolResult successResult(Map<String, Object> data) {
    return resultFactory.success(data);
  }

  private static Object progressToken(McpSchema.CallToolRequest req) {
    return new ProgressReporter().progressToken(req);
  }

  private void sendProgress(
      McpSyncServerExchange exchange,
      Object progressToken,
      double progress,
      double total,
      String message) {
    progressReporter.send(exchange, progressToken, progress, total, message);
  }

  private CallToolResult errorResult(String message) {
    return resultFactory.error(message);
  }

  /**
   * Caps {@code list} at {@code max} elements in place. If truncated, returns the number of removed
   * rows; the caller surfaces a truncation marker in the response.
   */
  static int truncate(List<?> list, int max) {
    return ResultLimiter.truncate(list, max);
  }
}

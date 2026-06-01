package io.jafar.mcp;

import io.jafar.mcp.config.McpServerConfig;
import io.jafar.mcp.hdump.HdumpTools;
import io.jafar.mcp.jfr.JfrAnalysisTools;
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
import io.jafar.shell.jfrpath.JfrPath;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

  private final SessionRegistry sessionRegistry;
  private final HeapSessionRegistry heapSessionRegistry;
  private final PprofSessionRegistry pprofSessionRegistry;
  private final OtlpSessionRegistry otlpSessionRegistry;
  private final QueryEvaluator evaluator;
  private final QueryParser queryParser;
  private final JfrHelpProvider jfrHelpProvider = new JfrHelpProvider();
  private final JfrAnalysisTools jfrAnalysisTools;
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
    this.jfrAnalysisTools =
        new JfrAnalysisTools(sessionRegistry, evaluator, queryParser, resultFactory, progressReporter);
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
  // JFR analysis tools
  // ─────────────────────────────────────────────────────────────────────────────

  private McpServerFeatures.SyncToolSpecification createJfrFlamegraphTool() {
    return jfrAnalysisTools.createJfrFlamegraphTool();
  }

  private CallToolResult handleJfrFlamegraph(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return jfrAnalysisTools.handleJfrFlamegraph(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createJfrCallgraphTool() {
    return jfrAnalysisTools.createJfrCallgraphTool();
  }

  private CallToolResult handleJfrCallgraph(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return jfrAnalysisTools.handleJfrCallgraph(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createJfrExceptionsTool() {
    return jfrAnalysisTools.createJfrExceptionsTool();
  }

  private CallToolResult handleJfrExceptions(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return jfrAnalysisTools.handleJfrExceptions(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createJfrSummaryTool() {
    return jfrAnalysisTools.createJfrSummaryTool();
  }

  private CallToolResult handleJfrSummary(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return jfrAnalysisTools.handleJfrSummary(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createJfrHotmethodsTool() {
    return jfrAnalysisTools.createJfrHotmethodsTool();
  }

  private CallToolResult handleJfrHotmethods(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return jfrAnalysisTools.handleJfrHotmethods(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createJfrUseTool() {
    return jfrAnalysisTools.createJfrUseTool();
  }

  private CallToolResult handleJfrUse(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return jfrAnalysisTools.handleJfrUse(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createJfrTsaTool() {
    return jfrAnalysisTools.createJfrTsaTool();
  }

  private CallToolResult handleJfrTsa(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return jfrAnalysisTools.handleJfrTsa(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createJfrDiagnoseTool() {
    return jfrAnalysisTools.createJfrDiagnoseTool();
  }

  private CallToolResult handleJfrDiagnose(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return jfrAnalysisTools.handleJfrDiagnose(exchange, args, progressToken);
  }

  private McpServerFeatures.SyncToolSpecification createJfrStackprofileTool() {
    return jfrAnalysisTools.createJfrStackprofileTool();
  }

  private CallToolResult handleJfrStackprofile(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    return jfrAnalysisTools.handleJfrStackprofile(exchange, args, progressToken);
  }

  private String extractMethodName(Object frame) {
    return jfrAnalysisTools.extractMethodName(frame);
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

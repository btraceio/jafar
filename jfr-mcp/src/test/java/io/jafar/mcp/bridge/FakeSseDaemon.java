package io.jafar.mcp.bridge;

import io.jafar.mcp.lifecycle.BearerAuthFilter;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A scripted stand-in for the jfr-mcp SSE daemon, speaking the same legacy SSE protocol: {@code GET
 * /mcp/sse} opens a stream whose first event names the message endpoint (with a fresh {@code
 * sessionId}), and JSON-RPC messages are {@code POST}ed to that endpoint with replies coming back
 * on the stream. Unknown session ids get a 404, exactly as the SDK answers after a restart.
 *
 * <p>Behind the real {@link BearerAuthFilter}, so token handling is exercised over real HTTP.
 */
final class FakeSseDaemon implements AutoCloseable {

  static final String TOKEN = "fake-daemon-token";

  /** One message the daemon received: which session sent it, and what. */
  record Received(String sessionId, JsonNode message) {
    String method() {
      return message.has("method") ? message.get("method").asString() : null;
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final AtomicInteger sessionSeq = new AtomicInteger();
  private final Map<String, AsyncContext> streams = new ConcurrentHashMap<>();
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final List<String> sessionsOpened = new CopyOnWriteArrayList<>();
  private final List<String> authHeaders = new CopyOnWriteArrayList<>();
  private volatile boolean answerInitialize = true;
  private volatile String version = "fake";
  private volatile boolean autostarted;
  private volatile Integer reportedSessions; // null: report the real number of open streams
  private volatile boolean healthPresent = true;
  private volatile boolean shutdownAccepted = true;
  private final AtomicInteger shutdownCalls = new AtomicInteger();
  private Server server;
  private int port;

  FakeSseDaemon start(boolean requireAuth) throws Exception {
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setHost("127.0.0.1");
    connector.setPort(0);
    server.addConnector(connector);
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    server.setHandler(context);
    ServletHolder holder = new ServletHolder(new Servlet());
    holder.setAsyncSupported(true);
    context.addServlet(holder, "/mcp/*");
    if (requireAuth) {
      FilterHolder filter = new FilterHolder(new BearerAuthFilter(TOKEN));
      filter.setAsyncSupported(true);
      context.addFilter(filter, "/mcp/*", EnumSet.of(DispatcherType.REQUEST));
    }
    server.start();
    port = connector.getLocalPort();
    return this;
  }

  int port() {
    return port;
  }

  URI baseUri() {
    return URI.create("http://127.0.0.1:" + port);
  }

  List<Received> received() {
    return received;
  }

  List<String> sessionsOpened() {
    return sessionsOpened;
  }

  List<String> authHeaders() {
    return authHeaders;
  }

  /** What /mcp/health reports, as a real daemon would. */
  FakeSseDaemon reporting(String version, boolean autostarted, Integer activeSessions) {
    this.version = version;
    this.autostarted = autostarted;
    this.reportedSessions = activeSessions;
    return this;
  }

  /** Makes /mcp/health answer 404, like a daemon from before it existed. */
  FakeSseDaemon withoutHealthEndpoint() {
    this.healthPresent = false;
    return this;
  }

  /** Whether POST /mcp/shutdown is honoured (and stops this server) or refused with 409. */
  FakeSseDaemon shutdownAccepted(boolean accepted) {
    this.shutdownAccepted = accepted;
    return this;
  }

  int shutdownCalls() {
    return shutdownCalls.get();
  }

  /** When false, {@code initialize} requests are accepted but never answered. */
  void answerInitialize(boolean answer) {
    this.answerInitialize = answer;
  }

  /** Closes every open stream and forgets the sessions, as a daemon restart would. */
  void dropAllSessions() {
    for (AsyncContext ctx : streams.values()) {
      try {
        ctx.complete();
      } catch (RuntimeException ignored) {
        // stream already gone
      }
    }
    streams.clear();
  }

  boolean hasOpenStream() {
    return !streams.isEmpty();
  }

  @Override
  public void close() throws Exception {
    dropAllSessions();
    server.stop();
  }

  private void emit(String sessionId, String json) {
    AsyncContext ctx = streams.get(sessionId);
    if (ctx == null) {
      return;
    }
    try {
      PrintWriter w = ctx.getResponse().getWriter();
      w.print("event: message\ndata: " + json + "\n\n");
      w.flush();
    } catch (IOException e) {
      streams.remove(sessionId);
    }
  }

  private final class Servlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      authHeaders.add(String.valueOf(req.getHeader("Authorization")));
      if ("/health".equals(req.getPathInfo())) {
        if (!healthPresent) {
          resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
          return;
        }
        resp.setContentType("application/json");
        resp.getWriter()
            .write(
                "{\"version\":\""
                    + version
                    + "\",\"pid\":1,\"activeSessions\":"
                    + (reportedSessions != null ? reportedSessions : streams.size())
                    + ",\"autostarted\":"
                    + autostarted
                    + "}");
        return;
      }
      String sessionId = "s" + sessionSeq.incrementAndGet();
      resp.setContentType("text/event-stream");
      resp.setCharacterEncoding("UTF-8");
      AsyncContext ctx = req.startAsync();
      ctx.setTimeout(0);
      streams.put(sessionId, ctx);
      sessionsOpened.add(sessionId);
      PrintWriter w = resp.getWriter();
      w.print("event: endpoint\ndata: /mcp/message?sessionId=" + sessionId + "\n\n");
      w.flush();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      authHeaders.add(String.valueOf(req.getHeader("Authorization")));
      if ("/shutdown".equals(req.getPathInfo())) {
        shutdownCalls.incrementAndGet();
        if (!shutdownAccepted) {
          resp.setStatus(HttpServletResponse.SC_CONFLICT);
          return;
        }
        resp.setStatus(HttpServletResponse.SC_ACCEPTED);
        Thread stopper =
            new Thread(
                () -> {
                  try {
                    Thread.sleep(100); // let the reply out first, as the real daemon does
                    close();
                  } catch (Exception ignored) {
                    // already stopped
                  }
                });
        stopper.setDaemon(true);
        stopper.start();
        return;
      }
      String sessionId = req.getParameter("sessionId");
      if (sessionId == null || !streams.containsKey(sessionId)) {
        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
        resp.getWriter().write("Session not found");
        return;
      }
      JsonNode message =
          MAPPER.readTree(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      received.add(new Received(sessionId, message));
      resp.setStatus(HttpServletResponse.SC_ACCEPTED);

      if (!message.has("method") || !message.has("id")) {
        return; // notification or a response to a server request: nothing to answer
      }
      String method = message.get("method").asString();
      String id = message.get("id").toString();
      switch (method) {
        case "initialize" -> {
          if (answerInitialize) {
            emit(
                sessionId,
                "{\"jsonrpc\":\"2.0\",\"id\":"
                    + id
                    + ",\"result\":{\"serverInfo\":{\"name\":\"fake\",\"version\":\"0\"}}}");
          }
        }
        case "hang" -> {
          // never answered: lets a test drop the connection with a request in flight
        }
        default ->
            emit(
                sessionId,
                "{\"jsonrpc\":\"2.0\",\"id\":"
                    + id
                    + ",\"result\":{\"echo\":\""
                    + method
                    + "\",\"session\":\""
                    + sessionId
                    + "\"}}");
      }
    }
  }
}

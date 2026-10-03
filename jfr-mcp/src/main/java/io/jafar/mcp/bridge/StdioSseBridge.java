package io.jafar.mcp.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Speaks MCP over stdio to a client and relays every message to a shared SSE daemon, so many
 * short-lived clients can use one long-lived server (and its warm JVM) instead of each starting
 * their own.
 *
 * <p>It is a raw JSON-RPC pass-through: one stdin line becomes one {@code POST} to the daemon's
 * message endpoint, and one SSE {@code message} event becomes one stdout line. The only protocol it
 * understands is what it needs to survive a daemon restart. The daemon forgets a client's session
 * when it goes away, and the SDK then answers with 404, so the bridge remembers the client's {@code
 * initialize} request and {@code notifications/initialized}, and replays them on the new session
 * before sending anything else. The client never sees the seam, except that requests that were in
 * flight get a JSON-RPC error instead of a reply that will never come.
 *
 * <p>Stdout carries protocol frames and nothing else; every diagnostic goes to the diag stream.
 *
 * <p>It uses {@link HttpURLConnection} rather than {@code java.net.http.HttpClient}: building an
 * {@code HttpClient} initialises the TLS stack (about 300ms) even for a plain-http loopback
 * connection, and this runs once per MCP client session.
 */
public final class StdioSseBridge {

  static {
    // HttpURLConnection pools connections and may transparently re-send a POST on a stale pooled
    // one. For a protocol where a message must arrive once, a silent duplicate is worse than a
    // visible failure, and a fresh loopback connection per POST costs next to nothing.
    System.setProperty("http.keepAlive", "false");
    System.setProperty("sun.net.http.retryPost", "false");
  }

  /** Where the daemon is, and the bearer token to present ({@code null} when auth is off). */
  public record DaemonEndpoint(URI base, String token) {}

  /** Finds the daemon, starting it if need be. Called again on every (re)connect. */
  @FunctionalInterface
  public interface DaemonSupplier {
    DaemonEndpoint ensureRunning() throws IOException;
  }

  private static final int INTERNAL_ERROR = -32603;
  private static final int CONNECT_TIMEOUT_MS = 5_000;
  private static final int POST_TIMEOUT_MS = 30_000;
  private static final int DELIVERY_ATTEMPTS = 3;
  private static final long RECONNECT_RETRY_DELAY_MS = 25;

  private final DaemonSupplier daemon;
  private final InputStream stdin;
  private final PrintStream stdout;
  private final PrintStream diag;
  private final Duration handshakeTimeout;
  private final Object stdoutLock = new Object();

  /** Requests sent but not yet answered, by canonical JSON id. */
  private final Map<String, String> inFlight = new ConcurrentHashMap<>();

  private volatile boolean closing;
  private volatile String swallowId;
  private volatile CountDownLatch swallowLatch = new CountDownLatch(0);

  // All guarded by this.
  private Connection connection;
  private String initializeLine;
  private String initializedLine;

  public StdioSseBridge(
      DaemonSupplier daemon,
      InputStream stdin,
      OutputStream stdout,
      PrintStream diag,
      Duration handshakeTimeout) {
    this.daemon = daemon;
    this.stdin = stdin;
    this.stdout = new PrintStream(stdout, false, StandardCharsets.UTF_8);
    this.diag = diag;
    this.handshakeTimeout = handshakeTimeout;
  }

  private final class Connection {
    final DaemonEndpoint endpoint;
    final HttpURLConnection http;
    final InputStream stream;
    volatile URI messageUri;
    volatile boolean dead;

    Connection(DaemonEndpoint endpoint, HttpURLConnection http, InputStream stream) {
      this.endpoint = endpoint;
      this.http = http;
      this.stream = stream;
    }

    void close() {
      dead = true;
      // HttpURLConnection.disconnect() closes the response stream first, and closing a chunked
      // stream waits for the reader thread's blocked read(): done inline it would stall the caller
      // (often while holding the bridge lock) until the daemon next sent something. Nothing the
      // bridge does depends on the socket being closed right now, so do it off-thread.
      Thread closer = new Thread(http::disconnect, "jfr-mcp-bridge-close");
      closer.setDaemon(true);
      closer.start();
    }
  }

  /** Relays until stdin closes. Returns the process exit code. */
  public int run() {
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        if (!line.isBlank()) {
          handleClientLine(line);
        }
      }
      return 0;
    } catch (IOException e) {
      diag.println("jfr-mcp bridge: stdin failed: " + e.getMessage());
      return 1;
    } finally {
      closing = true;
      closeConnection();
    }
  }

  private void handleClientLine(String line) {
    JsonRpc.Frame frame = JsonRpc.parse(line);
    if (frame == null) {
      if (line.stripLeading().startsWith("[")) {
        post(line, null); // a JSON-RPC batch: pass it through untracked
      } else {
        diag.println("jfr-mcp bridge: ignoring a stdin line that is not JSON");
      }
      return;
    }
    String idKey = frame.isRequest() ? frame.idJson() : null;
    if (idKey != null) {
      inFlight.put(idKey, frame.method());
    }

    boolean delivered = post(line, idKey);

    // Cache only what the daemon actually accepted, and only after the first delivery, so a
    // reconnect replays the client's handshake rather than duplicating it on the first connect.
    if (delivered && "initialize".equals(frame.method())) {
      synchronized (this) {
        initializeLine = line;
      }
    } else if (delivered && "notifications/initialized".equals(frame.method())) {
      synchronized (this) {
        initializedLine = line;
      }
    }
  }

  /**
   * Delivers one client message, retrying a bounded number of times if the daemon drops the
   * session.
   */
  private synchronized boolean post(String line, String idKey) {
    IOException last = null;
    for (int attempt = 0; attempt < DELIVERY_ATTEMPTS; attempt++) {
      if (attempt > 0 && idKey != null && !inFlight.containsKey(idKey)) {
        return false; // already failed to the client while we were reconnecting; don't answer twice
      }
      try {
        Connection c = ensureConnected();
        int status = postTo(c, line);
        if (status < 300) {
          return true;
        }
        if (status == 404 || status == 410) {
          dropConnection(c, idKey);
          last = new IOException("the daemon no longer knows this session (HTTP " + status + ")");
          continue;
        }
        fail(idKey, "the daemon rejected the message: HTTP " + status);
        return false;
      } catch (IOException e) {
        last = e;
        if (connection != null) {
          dropConnection(connection, idKey);
        }
        if (attempt + 1 < DELIVERY_ATTEMPTS) {
          try {
            Thread.sleep(RECONNECT_RETRY_DELAY_MS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(idKey, "interrupted while reconnecting to the jfr-mcp daemon");
            return false;
          }
        }
      }
    }
    fail(
        idKey,
        "cannot reach the jfr-mcp daemon: " + (last == null ? "unknown" : last.getMessage()));
    return false;
  }

  private Connection ensureConnected() throws IOException {
    if (connection != null && !connection.dead) {
      return connection;
    }
    connection = connect();
    return connection;
  }

  private Connection connect() throws IOException {
    DaemonEndpoint endpoint = daemon.ensureRunning();
    HttpURLConnection http = open(endpoint, endpoint.base().resolve("/mcp/sse"), "GET");
    http.setRequestProperty("Accept", "text/event-stream");
    http.setReadTimeout(0); // an SSE stream is idle between events
    int status = http.getResponseCode();
    if (status != 200) {
      http.disconnect();
      throw new IOException(
          "the daemon refused the SSE connection: HTTP "
              + status
              + (status == 401 ? " (bearer token rejected)" : ""));
    }

    Connection c = new Connection(endpoint, http, http.getInputStream());
    CompletableFuture<String> announced = new CompletableFuture<>();
    Thread reader = new Thread(() -> readEvents(c, announced), "jfr-mcp-bridge-sse");
    reader.setDaemon(true);
    reader.start();

    try {
      c.messageUri =
          endpoint
              .base()
              .resolve(announced.get(handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS));
      replayHandshake(c);
      return c;
    } catch (TimeoutException e) {
      c.close();
      throw new IOException("the daemon did not announce a message endpoint in time");
    } catch (ExecutionException e) {
      c.close();
      throw new IOException("the daemon's SSE stream failed: " + e.getCause().getMessage());
    } catch (InterruptedException e) {
      c.close();
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while connecting to the daemon", e);
    } catch (IOException e) {
      c.close();
      throw e;
    }
  }

  /** Re-runs the client's handshake on a fresh session, hiding the duplicate reply from it. */
  private void replayHandshake(Connection c) throws IOException, InterruptedException {
    if (initializeLine == null) {
      return;
    }
    JsonRpc.Frame init = JsonRpc.parse(initializeLine);
    if (init == null || init.idJson() == null) {
      return; // cannot happen for a line we cached, but never replay what we cannot match
    }
    swallowId = init.idJson();
    swallowLatch = new CountDownLatch(1);
    int status = postTo(c, initializeLine);
    if (status >= 300) {
      throw new IOException("the daemon rejected the re-initialize: HTTP " + status);
    }
    if (!swallowLatch.await(handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
      swallowId = null;
      throw new IOException(
          "the daemon did not answer the re-initialize within "
              + handshakeTimeout.toMillis()
              + "ms");
    }
    if (initializedLine != null) {
      postTo(c, initializedLine);
    }
  }

  private int postTo(Connection c, String line) throws IOException {
    byte[] body = line.getBytes(StandardCharsets.UTF_8);
    HttpURLConnection http = open(c.endpoint, c.messageUri, "POST");
    http.setReadTimeout(POST_TIMEOUT_MS);
    http.setDoOutput(true);
    http.setFixedLengthStreamingMode(body.length);
    http.setRequestProperty("Content-Type", "application/json");
    try {
      try (OutputStream out = http.getOutputStream()) {
        out.write(body);
      }
      int status = http.getResponseCode();
      try (InputStream reply = status >= 400 ? http.getErrorStream() : http.getInputStream()) {
        if (reply != null) {
          reply.readAllBytes();
        }
      }
      return status;
    } finally {
      http.disconnect();
    }
  }

  private static HttpURLConnection open(DaemonEndpoint endpoint, URI uri, String method)
      throws IOException {
    HttpURLConnection http = (HttpURLConnection) uri.toURL().openConnection();
    http.setRequestMethod(method);
    http.setInstanceFollowRedirects(false);
    http.setConnectTimeout(CONNECT_TIMEOUT_MS);
    if (endpoint.token() != null) {
      http.setRequestProperty("Authorization", "Bearer " + endpoint.token());
    }
    return http;
  }

  // ───────────────────────────── daemon → client ─────────────────────────────

  private void readEvents(Connection c, CompletableFuture<String> announced) {
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(c.stream, StandardCharsets.UTF_8))) {
      String event = null;
      StringBuilder data = new StringBuilder();
      String line;
      while ((line = r.readLine()) != null) {
        if (line.isEmpty()) {
          dispatch(event, data.toString(), announced);
          event = null;
          data.setLength(0);
        } else if (line.startsWith("event:")) {
          event = fieldValue(line, "event:".length());
        } else if (line.startsWith("data:")) {
          if (data.length() > 0) {
            data.append('\n');
          }
          data.append(fieldValue(line, "data:".length()));
        } // ":" comment lines and unknown fields carry nothing for us
      }
    } catch (IOException | RuntimeException e) {
      announced.completeExceptionally(e);
    } finally {
      announced.completeExceptionally(new IOException("the SSE stream ended"));
      onStreamEnded(c);
    }
  }

  private static String fieldValue(String line, int from) {
    return line.startsWith(" ", from) ? line.substring(from + 1) : line.substring(from);
  }

  private void dispatch(String event, String data, CompletableFuture<String> announced) {
    if ("endpoint".equals(event)) {
      announced.complete(data);
    } else if (event == null || "message".equals(event)) {
      onMessage(data);
    }
  }

  private void onMessage(String data) {
    String line = data.indexOf('\n') >= 0 ? JsonRpc.compact(data) : data;
    JsonRpc.Frame frame = JsonRpc.parse(line);
    if (frame == null) {
      diag.println("jfr-mcp bridge: dropping a daemon event that is not a JSON object");
      return;
    }
    if (frame.isResponse()) {
      if (frame.idJson().equals(swallowId)) {
        swallowId = null;
        swallowLatch.countDown();
        return;
      }
      inFlight.remove(frame.idJson());
    }
    writeStdout(line);
  }

  private void onStreamEnded(Connection c) {
    if (c.dead) {
      return; // we closed it ourselves and already dealt with what was in flight
    }
    c.dead = true;
    synchronized (this) {
      if (connection == c) {
        connection = null;
      }
    }
    if (!closing) {
      failInFlight(null, "the connection to the jfr-mcp daemon was lost; retry the request");
    }
  }

  // ───────────────────────────── failure paths ─────────────────────────────

  private void dropConnection(Connection c, String keepKey) {
    c.close();
    if (connection == c) {
      connection = null;
    }
    failInFlight(keepKey, "the jfr-mcp daemon dropped this session; retry the request");
  }

  private void closeConnection() {
    synchronized (this) {
      if (connection != null) {
        connection.close();
        connection = null;
      }
    }
  }

  private void fail(String idKey, String message) {
    if (idKey != null && inFlight.remove(idKey) != null) {
      writeError(idKey, message);
    } else {
      diag.println("jfr-mcp bridge: " + message);
    }
  }

  private void failInFlight(String exceptKey, String message) {
    for (String key : new ArrayList<>(inFlight.keySet())) {
      if (!key.equals(exceptKey) && inFlight.remove(key) != null) {
        writeError(key, message);
      }
    }
  }

  private void writeError(String idKey, String message) {
    diag.println("jfr-mcp bridge: failing request " + idKey + ": " + message);
    writeStdout(JsonRpc.error(idKey, INTERNAL_ERROR, message));
  }

  private void writeStdout(String frame) {
    synchronized (stdoutLock) {
      stdout.print(frame + "\n");
      stdout.flush();
    }
  }
}

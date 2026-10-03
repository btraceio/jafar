package io.jafar.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Drives the bridge over real stdio-shaped pipes against a {@link FakeSseDaemon}. */
class StdioSseBridgeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long WAIT_MS = 10_000;

  private FakeSseDaemon daemon;
  private PipedOutputStream toBridge;
  private final BlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
  private final ByteArrayOutputStream diag = new ByteArrayOutputStream();
  private final AtomicInteger exitCode = new AtomicInteger(-1);
  private Thread bridgeThread;

  private void start(boolean requireAuth, String tokenToSend) throws Exception {
    daemon = new FakeSseDaemon().start(requireAuth);
    toBridge = new PipedOutputStream();
    PipedInputStream bridgeIn = new PipedInputStream(toBridge, 1 << 16);
    LineSink bridgeOut = new LineSink();

    StdioSseBridge bridge =
        new StdioSseBridge(
            () -> new StdioSseBridge.DaemonEndpoint(daemon.baseUri(), tokenToSend),
            bridgeIn,
            bridgeOut,
            new PrintStream(diag, true, StandardCharsets.UTF_8),
            Duration.ofMillis(1500));
    bridgeThread = new Thread(() -> exitCode.set(bridge.run()), "bridge-under-test");
    bridgeThread.setDaemon(true);
    bridgeThread.start();
  }

  /**
   * Collects what the bridge writes to stdout, one line at a time. A pipe is the wrong tool here:
   * {@code PipedInputStream} fails with "Write end dead" once the thread that last wrote to it
   * exits, and the bridge writes from short-lived SSE reader threads.
   */
  private final class LineSink extends OutputStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Override
    public synchronized void write(int b) {
      if (b == '\n') {
        stdoutLines.add(buffer.toString(StandardCharsets.UTF_8));
        buffer.reset();
      } else {
        buffer.write(b);
      }
    }
  }

  @AfterEach
  void stop() throws Exception {
    if (toBridge != null) {
      try {
        toBridge.close();
      } catch (IOException ignored) {
        // already closed by the test
      }
    }
    if (bridgeThread != null) {
      bridgeThread.join(5_000);
    }
    if (daemon != null) {
      daemon.close();
    }
  }

  private void send(String json) throws IOException {
    toBridge.write((json + "\n").getBytes(StandardCharsets.UTF_8));
    toBridge.flush();
  }

  private JsonNode next() throws Exception {
    String line = stdoutLines.poll(WAIT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(
        line,
        "bridge wrote nothing to stdout within "
            + WAIT_MS
            + "ms; its diagnostics: "
            + diag.toString(StandardCharsets.UTF_8));
    return MAPPER.readTree(line);
  }

  private void handshake() throws Exception {
    send(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}");
    assertEquals(1, next().get("id").asInt());
    send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
  }

  private void awaitReceived(String method, int count) throws Exception {
    long deadline = System.currentTimeMillis() + WAIT_MS;
    while (System.currentTimeMillis() < deadline) {
      long n = daemon.received().stream().filter(r -> method.equals(r.method())).count();
      if (n >= count) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("daemon never received " + count + " x " + method);
  }

  @Test
  void relaysARequestAndItsResponse() throws Exception {
    start(true, FakeSseDaemon.TOKEN);
    handshake();

    send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

    JsonNode response = next();
    assertEquals(2, response.get("id").asInt());
    assertEquals("tools/list", response.get("result").get("echo").asString());
  }

  @Test
  void sendsTheBearerTokenOnEveryRequest() throws Exception {
    start(true, FakeSseDaemon.TOKEN);
    handshake();
    send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
    next();

    assertFalse(daemon.authHeaders().isEmpty());
    for (String header : daemon.authHeaders()) {
      assertEquals("Bearer " + FakeSseDaemon.TOKEN, header);
    }
  }

  @Test
  void sendsNoAuthorizationHeaderWhenThereIsNoToken() throws Exception {
    start(false, null);
    handshake();

    for (String header : daemon.authHeaders()) {
      assertEquals("null", header);
    }
  }

  @Test
  void aRejectedTokenBecomesAnErrorResponseNotAHang() throws Exception {
    start(true, "wrong-token");

    send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");

    JsonNode response = next();
    assertEquals(1, response.get("id").asInt());
    assertEquals(-32603, response.get("error").get("code").asInt());
    assertTrue(
        response.get("error").get("message").asString().contains("401"),
        "message should name the cause: " + response);
  }

  @Test
  void reconnectsAndReplaysTheHandshakeAfterTheDaemonDropsTheSession() throws Exception {
    start(true, FakeSseDaemon.TOKEN);
    handshake();
    send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
    assertEquals("s1", next().get("result").get("session").asString());

    daemon.dropAllSessions();
    send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");

    JsonNode response = next();
    assertEquals(3, response.get("id").asInt(), "the replayed initialize must not be echoed");
    assertEquals("s2", response.get("result").get("session").asString());

    List<String> onNewSession = new ArrayList<>();
    for (FakeSseDaemon.Received r : daemon.received()) {
      if (r.sessionId().equals("s2")) {
        onNewSession.add(r.method());
      }
    }
    assertEquals(
        List.of("initialize", "notifications/initialized", "tools/list"),
        onNewSession,
        "new session must be initialized exactly as the client initialized the old one");
  }

  @Test
  void aRequestInFlightWhenTheStreamDropsGetsAnErrorResponse() throws Exception {
    start(true, FakeSseDaemon.TOKEN);
    handshake();
    send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"hang\"}");
    awaitReceived("hang", 1);

    daemon.dropAllSessions();

    JsonNode response = next();
    assertEquals(7, response.get("id").asInt());
    assertEquals(-32603, response.get("error").get("code").asInt());
  }

  @Test
  void aHandshakeReplayTheDaemonNeverAnswersFailsTheRequestInsteadOfHanging() throws Exception {
    start(true, FakeSseDaemon.TOKEN);
    handshake();
    daemon.answerInitialize(false);
    daemon.dropAllSessions();

    send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}");

    JsonNode response = next();
    assertEquals(4, response.get("id").asInt());
    assertNotNull(response.get("error"), "expected an error, got " + response);
  }

  @Test
  void exitsZeroWhenStdinCloses() throws Exception {
    start(true, FakeSseDaemon.TOKEN);
    handshake();

    toBridge.close();
    bridgeThread.join(5_000);

    assertFalse(bridgeThread.isAlive(), "bridge must stop when stdin closes");
    assertEquals(0, exitCode.get());
  }

  @Test
  void stdoutCarriesOnlyJsonRpcFrames() throws Exception {
    start(true, FakeSseDaemon.TOKEN);
    handshake();
    send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
    next();
    daemon.dropAllSessions();
    send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
    next();

    for (String line : stdoutLines) {
      JsonNode frame = MAPPER.readTree(line);
      assertEquals("2.0", frame.get("jsonrpc").asString(), "not a JSON-RPC frame: " + line);
    }
  }
}

package io.jafar.mcp.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Drives {@link ShutdownServlet} over real HTTP behind the daemon's bearer filter. */
class ShutdownServletTest {

  private static final String TOKEN = "test-token-0123456789";

  private Server server;
  private int port;
  private final AtomicInteger exits = new AtomicInteger();

  private void start(boolean autostarted, int activeSessions) throws Exception {
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setHost("127.0.0.1");
    connector.setPort(0);
    server.addConnector(connector);
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    server.setHandler(context);
    context.addServlet(
        new ServletHolder(
            new ShutdownServlet(autostarted, () -> activeSessions, exits::incrementAndGet)),
        "/mcp/shutdown");
    context.addFilter(
        new FilterHolder(new BearerAuthFilter(TOKEN)),
        "/mcp/*",
        EnumSet.of(DispatcherType.REQUEST));
    server.start();
    port = connector.getLocalPort();
  }

  @AfterEach
  void stop() throws Exception {
    if (server != null) {
      server.stop();
    }
  }

  private int request(String method, String authorization) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/shutdown"))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (authorization != null) {
      b.header("Authorization", authorization);
    }
    return HttpClient.newHttpClient()
        .send(b.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void anIdleAutoStartedDaemonStopsOnAuthorisedPost() throws Exception {
    start(true, 0);

    assertEquals(202, request("POST", "Bearer " + TOKEN));

    assertEquals(1, exits.get());
  }

  @Test
  void requestsWithoutTheTokenAreRejectedAndStopNothing() throws Exception {
    start(true, 0);

    assertEquals(401, request("POST", null));
    assertEquals(401, request("POST", "Bearer wrong"));

    assertEquals(0, exits.get());
  }

  @Test
  void aDaemonWithClientsConnectedRefuses() throws Exception {
    start(true, 1);

    assertEquals(409, request("POST", "Bearer " + TOKEN));

    assertEquals(0, exits.get());
  }

  @Test
  void aDaemonThatCannotCountItsClientsRefuses() throws Exception {
    start(true, -1);

    assertEquals(409, request("POST", "Bearer " + TOKEN), "unknown is not the same as idle");

    assertEquals(0, exits.get());
  }

  @Test
  void aDaemonThatWasNotAutoStartedRefusesWhateverTheLoad() throws Exception {
    start(false, 0);

    assertEquals(409, request("POST", "Bearer " + TOKEN));

    assertEquals(0, exits.get(), "a supervised daemon is its supervisor's to stop");
  }

  @Test
  void onlyPostIsAccepted() throws Exception {
    start(true, 0);

    assertTrue(request("GET", "Bearer " + TOKEN) >= 400);
    assertFalse(exits.get() > 0);
  }
}

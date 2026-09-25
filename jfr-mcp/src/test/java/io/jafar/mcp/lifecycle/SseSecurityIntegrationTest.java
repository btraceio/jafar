package io.jafar.mcp.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test covering the two SSE-mode wiring decisions in {@code JafarMcpServer.runSse()}:
 * binding {@link ServerConnector} to an explicit host (loopback by default) instead of Jetty's
 * wildcard-address default, and gating every request behind {@link BearerAuthFilter}.
 *
 * <p>Builds the same Server/connector/filter/servlet wiring at a smaller scale (a trivial echo
 * servlet instead of the real MCP transport) so the test can drive real HTTP requests rather than
 * mock the servlet API.
 */
class SseSecurityIntegrationTest {

  private static final String TOKEN = "test-token-0123456789";

  private Server server;
  private int port;

  @BeforeEach
  void startServer() throws Exception {
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setHost("127.0.0.1");
    connector.setPort(0); // ephemeral
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    server.setHandler(context);
    context.addServlet(new ServletHolder(new EchoServlet()), "/mcp/*");
    context.addFilter(
        new FilterHolder(new BearerAuthFilter(TOKEN)),
        "/mcp/*",
        EnumSet.of(DispatcherType.REQUEST));

    server.start();
    port = connector.getLocalPort();
  }

  @AfterEach
  void stopServer() throws Exception {
    server.stop();
  }

  @Test
  void connectorBindsToLoopbackNotWildcard() {
    ServerConnector connector = (ServerConnector) server.getConnectors()[0];
    assertEquals(
        "127.0.0.1",
        connector.getHost(),
        "connector must be configured to bind to loopback by default, not Jetty's wildcard"
            + " default");
  }

  @Test
  void requestWithoutTokenIsRejected() throws Exception {
    HttpResponse<String> response = send(null);
    assertEquals(401, response.statusCode());
  }

  @Test
  void requestWithWrongTokenIsRejected() throws Exception {
    HttpResponse<String> response = send("not-the-token");
    assertEquals(401, response.statusCode());
  }

  @Test
  void requestWithCorrectTokenReachesTheServlet() throws Exception {
    HttpResponse<String> response = send(TOKEN);
    assertEquals(200, response.statusCode());
    assertEquals("ok", response.body());
  }

  private HttpResponse<String> send(String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/test")).GET();
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Trivial servlet standing in for the real MCP transport servlet. */
  static final class EchoServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      resp.setStatus(200);
      resp.getWriter().write("ok");
    }
  }
}

package io.jafar.mcp.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.DispatcherType;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Drives {@link HealthServlet} over real HTTP, behind the same bearer filter the daemon uses. */
class HealthServletTest {

  private static final String TOKEN = "test-token-0123456789";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Server server;
  private int port;
  private int activeSessions;

  @BeforeEach
  void start() throws Exception {
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setHost("127.0.0.1");
    connector.setPort(0);
    server.addConnector(connector);
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    server.setHandler(context);
    context.addServlet(
        new ServletHolder(new HealthServlet("1.2.3", true, () -> activeSessions)), "/mcp/health");
    context.addFilter(
        new FilterHolder(new BearerAuthFilter(TOKEN)),
        "/mcp/*",
        EnumSet.of(DispatcherType.REQUEST));
    server.start();
    port = connector.getLocalPort();
  }

  @AfterEach
  void stop() throws Exception {
    server.stop();
  }

  private HttpResponse<String> get(String authorization) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/health")).GET();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsRequestsWithoutTheToken() throws Exception {
    assertEquals(401, get(null).statusCode());
    assertEquals(401, get("Bearer wrong").statusCode());
  }

  @Test
  void reportsVersionPidSessionsAndAutostartedWhenAuthorised() throws Exception {
    activeSessions = 3;

    HttpResponse<String> response = get("Bearer " + TOKEN);

    assertEquals(200, response.statusCode());
    assertTrue(
        response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    JsonNode body = MAPPER.readTree(response.body());
    assertEquals("1.2.3", body.get("version").asString());
    assertEquals(ProcessHandle.current().pid(), body.get("pid").asLong());
    assertEquals(3, body.get("activeSessions").asInt());
    assertTrue(body.get("autostarted").asBoolean());
    assertTrue(body.has("startedAt"));
  }

  @Test
  void activeSessionsIsReadOnEveryRequest() throws Exception {
    activeSessions = 1;
    assertEquals(1, MAPPER.readTree(get("Bearer " + TOKEN).body()).get("activeSessions").asInt());

    activeSessions = 0;
    assertEquals(0, MAPPER.readTree(get("Bearer " + TOKEN).body()).get("activeSessions").asInt());
  }
}

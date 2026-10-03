package io.jafar.mcp.lifecycle;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import tools.jackson.databind.ObjectMapper;

/**
 * Reports what a client needs to decide whether to use, replace or leave alone the daemon it found:
 * its version, whether the stdio bridge started it, and how many clients are connected.
 *
 * <p>Registered under the same bearer-token filter as the MCP endpoints, so it exposes nothing an
 * authenticated client could not already learn.
 */
public final class HealthServlet extends HttpServlet {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String version;
  private final boolean autostarted;
  private final IntSupplier activeSessions;
  private final Instant startedAt = Instant.now();

  public HealthServlet(String version, boolean autostarted, IntSupplier activeSessions) {
    this.version = version;
    this.autostarted = autostarted;
    this.activeSessions = activeSessions;
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", version);
    body.put("pid", ProcessHandle.current().pid());
    body.put("startedAt", startedAt.toString());
    body.put("activeSessions", activeSessions.getAsInt());
    body.put("autostarted", autostarted);

    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/json; charset=utf-8");
    response
        .getOutputStream()
        .write(MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
  }
}

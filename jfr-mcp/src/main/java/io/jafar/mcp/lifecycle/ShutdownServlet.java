package io.jafar.mcp.lifecycle;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.IntSupplier;

/**
 * Lets a newer bridge retire an out-of-date daemon it started itself, so the next attach gets the
 * current version instead of silently talking to an old one.
 *
 * <p>It stops the daemon only when that cannot hurt anyone: the daemon was auto-started (a
 * supervised one is its supervisor's to stop, and would simply be restarted) and has no client
 * connected. A daemon that cannot count its clients refuses too, since "unknown" is not "idle".
 * Anything else answers 409 and keeps running, which is also how a client that connected a moment
 * after the bridge looked gets protected.
 *
 * <p>Registered under the same bearer-token filter as the MCP endpoints.
 */
public final class ShutdownServlet extends HttpServlet {

  private final boolean autostarted;
  private final IntSupplier activeSessions;
  private final Runnable exit;

  public ShutdownServlet(boolean autostarted, IntSupplier activeSessions, Runnable exit) {
    this.autostarted = autostarted;
    this.activeSessions = activeSessions;
    this.exit = exit;
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!autostarted) {
      refuse(response, "this daemon was not auto-started, so only its supervisor may stop it");
      return;
    }
    int sessions = activeSessions.getAsInt();
    if (sessions != 0) {
      refuse(
          response,
          sessions < 0
              ? "cannot tell whether clients are connected"
              : sessions + " client(s) are connected");
      return;
    }
    response.setStatus(HttpServletResponse.SC_ACCEPTED);
    response.flushBuffer(); // the reply must be out before the process goes away
    exit.run();
  }

  private static void refuse(HttpServletResponse response, String reason) throws IOException {
    response.setStatus(HttpServletResponse.SC_CONFLICT);
    response.setContentType("text/plain; charset=utf-8");
    response.getWriter().write(reason);
  }
}

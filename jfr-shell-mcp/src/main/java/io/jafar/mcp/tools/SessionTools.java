package io.jafar.mcp.tools;

import io.jafar.mcp.model.SessionInfo;
import io.jafar.mcp.service.JfrSessionService;
import java.util.List;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for JFR session management.
 *
 * <p>Provides tools to open, list, and close JFR recording sessions.
 */
@Component
public class SessionTools {

  private final JfrSessionService sessionService;

  public SessionTools(JfrSessionService sessionService) {
    this.sessionService = sessionService;
  }

  /**
   * Opens a JFR recording file and creates a new analysis session.
   *
   * @param filePath absolute path to JFR file
   * @param alias optional alias for the session
   * @return session information
   */
  @McpTool(
      description =
          "Opens a JFR recording file and creates a new analysis session. Returns session ID,"
              + " event type count, and session details.")
  public SessionInfo jfr_open(
      @McpToolParam(description = "Absolute path to the JFR recording file", required = true)
          String filePath,
      @McpToolParam(description = "Optional human-readable alias for the session", required = false)
          String alias) {
    return sessionService.openSession(filePath, alias);
  }

  /**
   * Lists all active JFR sessions.
   *
   * @return list of session information
   */
  @McpTool(
      description =
          "Lists all active JFR recording sessions with their IDs, aliases, file paths, and event"
              + " counts.")
  public List<SessionInfo> jfr_sessions() {
    return sessionService.listSessions();
  }

  /**
   * Closes a JFR session by ID or alias.
   *
   * @param sessionIdOrAlias session ID (number) or alias (string)
   * @return success message
   */
  @McpTool(description = "Closes an active JFR session by ID or alias. Frees associated resources.")
  public String jfr_close(
      @McpToolParam(description = "Session ID (number) or alias (string) to close", required = true)
          String sessionIdOrAlias) {
    sessionService.closeSession(sessionIdOrAlias);
    return "Session " + sessionIdOrAlias + " closed successfully";
  }
}

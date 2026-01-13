package io.jafar.mcp.service;

import io.jafar.mcp.exception.JfrMcpException;
import io.jafar.mcp.model.SessionInfo;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.SessionManager.SessionRef;
import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Service for managing JFR recording sessions.
 *
 * <p>Wraps SessionManager from jfr-shell module and converts to MCP-friendly DTOs.
 */
@Service
public class JfrSessionService {

  private final SessionManager sessionManager;

  public JfrSessionService(SessionManager sessionManager) {
    this.sessionManager = sessionManager;
  }

  /**
   * Opens a new JFR recording session.
   *
   * @param filePath absolute path to JFR file
   * @param alias optional alias for session
   * @return session information
   * @throws JfrMcpException if file is invalid or cannot be opened
   */
  public SessionInfo openSession(String filePath, String alias) {
    // Validate file path
    if (filePath == null || filePath.isBlank()) {
      throw new JfrMcpException("File path is required");
    }

    File file = new File(filePath);

    // Validate file exists and is readable
    if (!file.exists()) {
      throw new JfrMcpException("File does not exist: " + filePath);
    }

    if (!file.isFile()) {
      throw new JfrMcpException("Path is not a regular file: " + filePath);
    }

    if (!file.canRead()) {
      throw new JfrMcpException("File is not readable: " + filePath);
    }

    try {
      SessionRef ref = sessionManager.open(file.toPath(), alias);
      return toSessionInfo(ref);
    } catch (Exception e) {
      throw new JfrMcpException("Failed to open JFR file: " + e.getMessage(), e);
    }
  }

  /**
   * Lists all active sessions.
   *
   * @return list of session information
   */
  public List<SessionInfo> listSessions() {
    return sessionManager.list().stream().map(this::toSessionInfo).collect(Collectors.toList());
  }

  /**
   * Gets a session by ID or alias.
   *
   * @param sessionIdOrAlias session ID or alias
   * @return session reference
   * @throws JfrMcpException if session not found
   */
  public SessionRef getSession(String sessionIdOrAlias) {
    return sessionManager
        .get(sessionIdOrAlias)
        .orElseThrow(() -> new JfrMcpException("Session not found: " + sessionIdOrAlias));
  }

  /**
   * Closes a session.
   *
   * @param sessionIdOrAlias session ID or alias
   * @throws JfrMcpException if session not found
   */
  public void closeSession(String sessionIdOrAlias) {
    try {
      boolean closed = sessionManager.close(sessionIdOrAlias);
      if (!closed) {
        throw new JfrMcpException("Session not found: " + sessionIdOrAlias);
      }
    } catch (JfrMcpException e) {
      throw e;
    } catch (Exception e) {
      throw new JfrMcpException("Failed to close session: " + e.getMessage(), e);
    }
  }

  /**
   * Converts SessionRef to SessionInfo DTO.
   *
   * @param ref session reference
   * @return session info
   */
  private SessionInfo toSessionInfo(SessionRef ref) {
    int eventTypeCount = ref.session.getAvailableEventTypes().size();
    long totalEvents = estimateTotalEvents(ref);

    return new SessionInfo(
        ref.id,
        ref.alias,
        ref.session.getRecordingPath().toString(),
        eventTypeCount,
        totalEvents,
        true);
  }

  /**
   * Estimates total event count (expensive operation - cache if needed).
   *
   * @param ref session reference
   * @return estimated event count
   */
  private long estimateTotalEvents(SessionRef ref) {
    // For now, return 0 - could implement proper counting if needed
    // This would require iterating all chunks which is expensive
    return 0;
  }
}

package io.jafar.mcp.session;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages JFR recording sessions for MCP tools.
 *
 * <p>Thread-safe registry for opening, tracking, and closing JFR sessions. Sessions are identified
 * by unique integer IDs and optional aliases.
 */
public final class SessionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

  /** Information about an open recording session. */
  public record SessionInfo(
      int id, String alias, Path recordingPath, Instant openedAt, JFRSession session) {
    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("id", id);
      if (alias != null) {
        map.put("alias", alias);
      }
      map.put("path", recordingPath.toString());
      map.put("openedAt", openedAt.toString());

      // Add recording info
      try {
        var eventTypes = session.getAvailableTypes();
        map.put("availableTypes", eventTypes.size());
        map.put("totalEvents", session.getTotalEvents());

        // Calculate duration from recording metadata if available
        var chunkIds = session.getAvailableChunkIds();
        map.put("chunkCount", chunkIds.size());
      } catch (Exception e) {
        LOG.debug("Could not get session details: {}", e.getMessage());
      }
      return map;
    }

    public Duration age() {
      return Duration.between(openedAt, Instant.now());
    }
  }

  private final ParsingContext parsingContext;
  private final AtomicInteger nextId = new AtomicInteger(1);
  private final Map<Integer, SessionInfo> sessionsById = new LinkedHashMap<>();
  private final Map<String, Integer> idsByAlias = new HashMap<>();
  private Integer currentSessionId = null;

  public SessionRegistry() {
    this.parsingContext = ParsingContext.create();
    LOG.info("SessionRegistry created with ParsingContext");
  }

  /** Shuts down the registry, closing all sessions. */
  public void shutdown() {
    LOG.info("Shutting down SessionRegistry, closing {} sessions", size());
    try {
      closeAll();
    } catch (Exception e) {
      LOG.warn("Error during SessionRegistry shutdown: {}", e.getMessage());
    }
  }

  /**
   * Opens a JFR recording file and creates a new session.
   *
   * @param path Path to the JFR recording file
   * @param alias Optional alias for the session
   * @return Information about the opened session
   * @throws Exception if the recording cannot be opened
   */
  public synchronized SessionInfo open(Path path, String alias) throws Exception {
    // Validate alias uniqueness
    if (alias != null && !alias.isBlank()) {
      if (idsByAlias.containsKey(alias)) {
        throw new IllegalArgumentException("Alias already in use: " + alias);
      }
    } else {
      alias = null; // Normalize empty to null
    }

    // Create session directly
    JFRSession session = new JFRSession(path, parsingContext);
    int id = nextId.getAndIncrement();

    SessionInfo info = new SessionInfo(id, alias, path, Instant.now(), session);
    sessionsById.put(id, info);
    if (alias != null) {
      idsByAlias.put(alias, id);
    }
    currentSessionId = id;

    LOG.info("Opened session {} for recording: {}", id, path);
    return info;
  }

  /**
   * Gets a session by ID or alias.
   *
   * @param idOrAlias Session ID (as string) or alias
   * @return The session info, or empty if not found
   */
  public synchronized Optional<SessionInfo> get(String idOrAlias) {
    if (idOrAlias == null || idOrAlias.isBlank()) {
      return Optional.empty();
    }

    // Try parsing as ID
    try {
      int id = Integer.parseInt(idOrAlias);
      return Optional.ofNullable(sessionsById.get(id));
    } catch (NumberFormatException e) {
      // Not a number, try as alias
      Integer id = idsByAlias.get(idOrAlias);
      return id == null ? Optional.empty() : Optional.ofNullable(sessionsById.get(id));
    }
  }

  /**
   * Gets the current (most recently used) session.
   *
   * @return The current session, or empty if none
   */
  public synchronized Optional<SessionInfo> getCurrent() {
    if (currentSessionId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(sessionsById.get(currentSessionId));
  }

  /**
   * Gets a session, defaulting to current if not specified.
   *
   * @param idOrAlias Session ID/alias, or null to use current
   * @return The session info
   * @throws IllegalArgumentException if session not found or no current session
   */
  public synchronized SessionInfo getOrCurrent(String idOrAlias) {
    if (idOrAlias == null || idOrAlias.isBlank()) {
      return getCurrent()
          .orElseThrow(() -> new IllegalArgumentException("No session open. Use jfr_open first."));
    }
    return get(idOrAlias)
        .orElseThrow(() -> new IllegalArgumentException("Session not found: " + idOrAlias));
  }

  /**
   * Lists all open sessions.
   *
   * @return List of session information
   */
  public synchronized List<SessionInfo> list() {
    return new ArrayList<>(sessionsById.values());
  }

  /**
   * Closes a session by ID or alias.
   *
   * @param idOrAlias Session ID or alias
   * @return true if session was closed, false if not found
   * @throws Exception if closing fails
   */
  public synchronized boolean close(String idOrAlias) throws Exception {
    Optional<SessionInfo> info = get(idOrAlias);
    if (info.isEmpty()) {
      return false;
    }

    SessionInfo session = info.get();
    closeSession(session);
    return true;
  }

  /**
   * Closes all open sessions.
   *
   * @throws Exception if closing fails
   */
  public synchronized void closeAll() throws Exception {
    for (SessionInfo info : new ArrayList<>(sessionsById.values())) {
      try {
        closeSession(info);
      } catch (Exception e) {
        LOG.warn("Error closing session {}: {}", info.id(), e.getMessage());
      }
    }
  }

  private void closeSession(SessionInfo info) throws Exception {
    sessionsById.remove(info.id());
    if (info.alias() != null) {
      idsByAlias.remove(info.alias());
    }
    info.session().close();

    // Update current session
    if (currentSessionId != null && currentSessionId == info.id()) {
      currentSessionId = sessionsById.isEmpty() ? null : sessionsById.keySet().iterator().next();
    }

    LOG.info("Closed session {}", info.id());
  }

  /** Returns the number of open sessions. */
  public synchronized int size() {
    return sessionsById.size();
  }

  /** Checks if there are any open sessions. */
  public synchronized boolean isEmpty() {
    return sessionsById.isEmpty();
  }
}

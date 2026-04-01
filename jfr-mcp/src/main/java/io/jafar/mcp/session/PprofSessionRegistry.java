package io.jafar.mcp.session;

import io.jafar.pprof.shell.PprofSession;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages pprof profile sessions for MCP tools.
 *
 * <p>Thread-safe registry for opening, tracking, and closing pprof sessions. Sessions are
 * identified by unique integer IDs and optional aliases.
 */
public final class PprofSessionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(PprofSessionRegistry.class);

  /** Information about an open pprof session. */
  public record SessionInfo(
      int id, String alias, Path path, Instant openedAt, PprofSession session) {

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("id", id);
      if (alias != null) {
        map.put("alias", alias);
      }
      map.put("path", path.toString());
      map.put("openedAt", openedAt.toString());
      try {
        map.putAll(session.getStatistics());
      } catch (Exception e) {
        LOG.debug("Could not get pprof session details: {}", e.getMessage());
      }
      return map;
    }
  }

  private int nextId = 1;
  private final Map<Integer, SessionInfo> sessionsById = new LinkedHashMap<>();
  private final Map<String, Integer> idsByAlias = new HashMap<>();
  private Integer currentSessionId = null;

  /**
   * Opens a pprof file and creates a new session.
   *
   * @param path path to the .pb.gz or .pprof file
   * @param alias optional alias for the session
   * @return information about the opened session
   * @throws IOException if the file cannot be opened
   */
  public synchronized SessionInfo open(Path path, String alias) throws IOException {
    if (alias != null && !alias.isBlank()) {
      if (idsByAlias.containsKey(alias)) {
        throw new IllegalArgumentException("Alias already in use: " + alias);
      }
    } else {
      alias = null;
    }

    PprofSession session = PprofSession.open(path);
    int id = nextId++;

    SessionInfo info = new SessionInfo(id, alias, path, Instant.now(), session);
    sessionsById.put(id, info);
    if (alias != null) {
      idsByAlias.put(alias, id);
    }
    currentSessionId = id;

    LOG.info("Opened pprof session {} for: {}", id, path);
    return info;
  }

  /**
   * Gets a session by ID or alias.
   *
   * @param idOrAlias session ID (as string) or alias
   * @return the session info, or empty if not found
   */
  public synchronized Optional<SessionInfo> get(String idOrAlias) {
    if (idOrAlias == null || idOrAlias.isBlank()) {
      return Optional.empty();
    }
    try {
      int id = Integer.parseInt(idOrAlias);
      return Optional.ofNullable(sessionsById.get(id));
    } catch (NumberFormatException e) {
      Integer id = idsByAlias.get(idOrAlias);
      return id == null ? Optional.empty() : Optional.ofNullable(sessionsById.get(id));
    }
  }

  /**
   * Gets the current (most recently opened) session.
   *
   * @return the current session, or empty if none
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
   * @param idOrAlias session ID/alias, or null to use current
   * @return the session info
   * @throws IllegalArgumentException if session not found or no current session
   */
  public synchronized SessionInfo getOrCurrent(String idOrAlias) {
    if (idOrAlias == null || idOrAlias.isBlank()) {
      return getCurrent()
          .orElseThrow(
              () -> new IllegalArgumentException("No pprof profile open. Use pprof_open first."));
    }
    return get(idOrAlias)
        .orElseThrow(() -> new IllegalArgumentException("Pprof session not found: " + idOrAlias));
  }

  /**
   * Lists all open sessions.
   *
   * @return list of session information
   */
  public synchronized List<SessionInfo> list() {
    return new ArrayList<>(sessionsById.values());
  }

  /**
   * Closes a session by ID or alias.
   *
   * @param idOrAlias session ID or alias
   * @return true if session was closed, false if not found
   */
  public synchronized boolean close(String idOrAlias) {
    Optional<SessionInfo> info = get(idOrAlias);
    if (info.isEmpty()) {
      return false;
    }
    closeSession(info.get());
    return true;
  }

  /** Closes all open sessions. */
  public synchronized void closeAll() {
    for (SessionInfo info : new ArrayList<>(sessionsById.values())) {
      try {
        closeSession(info);
      } catch (Exception e) {
        LOG.warn("Error closing pprof session {}: {}", info.id(), e.getMessage());
      }
    }
  }

  private void closeSession(SessionInfo info) {
    sessionsById.remove(info.id());
    if (info.alias() != null) {
      idsByAlias.remove(info.alias());
    }
    try {
      info.session().close();
    } catch (Exception e) {
      LOG.warn("Error closing pprof session {}: {}", info.id(), e.getMessage());
    }
    if (currentSessionId != null && currentSessionId == info.id()) {
      currentSessionId = sessionsById.isEmpty() ? null : sessionsById.keySet().iterator().next();
    }
    LOG.info("Closed pprof session {}", info.id());
  }

  /** Returns the number of open sessions. */
  public synchronized int size() {
    return sessionsById.size();
  }

  /** Shuts down the registry, closing all sessions. */
  public synchronized void shutdown() {
    LOG.info("Shutting down PprofSessionRegistry, closing {} sessions", size());
    closeAll();
  }
}

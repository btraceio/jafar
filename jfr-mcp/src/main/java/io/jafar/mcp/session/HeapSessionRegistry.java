package io.jafar.mcp.session;

import io.jafar.hdump.shell.HeapSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.SessionResolver;
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
 * Manages heap dump sessions for MCP tools.
 *
 * <p>Thread-safe registry for opening, tracking, and closing heap dump sessions. Sessions are
 * identified by unique integer IDs and optional aliases.
 */
public final class HeapSessionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(HeapSessionRegistry.class);

  /** Information about an open heap dump session. */
  public record SessionInfo(
      int id, String alias, Path path, Instant openedAt, HeapSession session) {

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("id", id);
      if (alias != null) {
        map.put("alias", alias);
      }
      map.put("path", path.toString());
      map.put("openedAt", openedAt.toString());

      try {
        var stats = session.getStatistics();
        map.put("objectCount", stats.get("objects"));
        map.put("classCount", stats.get("classes"));
        map.put("heapSize", stats.get("totalHeapSize"));
      } catch (Exception e) {
        LOG.debug("Could not get heap session details: {}", e.getMessage());
      }
      return map;
    }
  }

  private int nextId = 1;
  private final Map<Integer, SessionInfo> sessionsById = new LinkedHashMap<>();
  private final Map<String, Integer> idsByAlias = new HashMap<>();
  private Integer currentSessionId = null;

  /**
   * Opens a heap dump file and creates a new session.
   *
   * @param path path to the HPROF file
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

    HeapSession session = HeapSession.open(path);
    int id = nextId++;

    SessionInfo info = new SessionInfo(id, alias, path, Instant.now(), session);
    sessionsById.put(id, info);
    if (alias != null) {
      idsByAlias.put(alias, id);
    }
    currentSessionId = id;

    LOG.info("Opened heap session {} for: {}", id, path);
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
              () -> new IllegalArgumentException("No heap dump open. Use hdump_open first."));
    }
    return get(idOrAlias)
        .orElseThrow(() -> new IllegalArgumentException("Heap session not found: " + idOrAlias));
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
        LOG.warn("Error closing heap session {}: {}", info.id(), e.getMessage());
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
    } catch (IOException e) {
      LOG.warn("Error closing heap session {}: {}", info.id(), e.getMessage());
    }
    if (currentSessionId != null && currentSessionId == info.id()) {
      currentSessionId = sessionsById.isEmpty() ? null : sessionsById.keySet().iterator().next();
    }
    LOG.info("Closed heap session {}", info.id());
  }

  /** Returns the number of open sessions. */
  public synchronized int size() {
    return sessionsById.size();
  }

  /**
   * Returns a {@link SessionResolver} backed by this registry.
   *
   * <p>Used by {@code HdumpPathEvaluator} for cross-session operators such as {@code join}.
   *
   * @return session resolver
   */
  public SessionResolver asResolver() {
    return idOrAlias -> get(idOrAlias).map(this::toSessionRef);
  }

  private SessionManager.SessionRef<HeapSession> toSessionRef(SessionInfo info) {
    return new SessionManager.SessionRef<>(info.id(), info.alias(), info.session());
  }

  /** Shuts down the registry, closing all sessions. */
  public void shutdown() {
    LOG.info("Shutting down HeapSessionRegistry, closing {} sessions", size());
    closeAll();
  }
}

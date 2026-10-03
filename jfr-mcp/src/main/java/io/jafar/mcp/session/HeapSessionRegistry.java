package io.jafar.mcp.session;

import io.jafar.hdump.shell.HeapSession;
import io.jafar.shell.core.ScopedCurrentSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.SessionOwnership;
import io.jafar.shell.core.SessionResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private final ScopedCurrentSession current = new ScopedCurrentSession();
  private final SessionOwnership ownership = new SessionOwnership();

  /**
   * Opens a heap dump file and creates a new session, owned by the calling client.
   *
   * <p>The dump is opened outside the registry lock, so one client opening a large heap dump does
   * not stall every other client's lookups.
   *
   * @param path path to the HPROF file
   * @param alias optional alias, unique among the caller's own sessions
   * @return information about the opened session
   * @throws IOException if the file cannot be opened
   */
  public SessionInfo open(Path path, String alias) throws IOException {
    String name = alias == null || alias.isBlank() ? null : alias;
    synchronized (this) {
      ownership.requireAliasFree(name); // fail before parsing a large dump, not after
    }

    HeapSession session = HeapSession.open(path);

    synchronized (this) {
      int id = nextId;
      try {
        ownership.add(id, name); // re-checks: another open may have taken the alias meanwhile
      } catch (IllegalArgumentException lostTheRace) {
        try {
          session.close();
        } catch (IOException e) {
          LOG.warn("Error closing heap session that lost an alias race: {}", e.getMessage());
        }
        throw lostTheRace;
      }
      nextId++;

      SessionInfo info = new SessionInfo(id, name, path, Instant.now(), session);
      sessionsById.put(id, info);
      current.opened(id);

      LOG.info("Opened heap session {} for: {}", id, path);
      return info;
    }
  }

  /**
   * Gets a session by ID or alias, among those the calling client may see: its own and any shared
   * ones.
   *
   * @param idOrAlias session ID (as string) or alias
   * @return the session info, or empty if not found
   */
  public synchronized Optional<SessionInfo> get(String idOrAlias) {
    Integer id = ownership.resolve(idOrAlias);
    return id == null ? Optional.empty() : Optional.ofNullable(sessionsById.get(id));
  }

  /**
   * Gets the current (most recently opened) session.
   *
   * @return the current session, or empty if none
   */
  public synchronized Optional<SessionInfo> getCurrent() {
    Integer currentSessionId = current.current();
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
   * Lists the open sessions the calling client may see: its own and any shared ones.
   *
   * @return list of session information
   */
  public synchronized List<SessionInfo> list() {
    List<SessionInfo> out = new ArrayList<>();
    for (int id : ownership.visibleIds(sessionsById.keySet())) {
      out.add(sessionsById.get(id));
    }
    return out;
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

  /**
   * Closes every session the calling client can see: its own and the shared ones restored after a
   * restart, never another client's. A caller with no scope is the server itself and closes
   * everything.
   *
   * @return how many sessions were closed
   */
  public synchronized int closeAll() {
    return closeIds(ownership.closableByCaller(new ArrayList<>(sessionsById.keySet())));
  }

  private int closeIds(List<Integer> ids) {
    int closed = 0;
    for (int id : ids) {
      SessionInfo info = sessionsById.get(id);
      if (info == null) {
        continue;
      }
      try {
        closeSession(info);
        closed++;
      } catch (Exception e) {
        LOG.warn("Error closing heap session {}: {}", id, e.getMessage());
      }
    }
    return closed;
  }

  private void closeSession(SessionInfo info) {
    sessionsById.remove(info.id());
    ownership.remove(info.id());
    try {
      info.session().close();
    } catch (IOException e) {
      LOG.warn("Error closing heap session {}: {}", info.id(), e.getMessage());
    }
    current.closed(info.id());
    LOG.info("Closed heap session {}", info.id());
  }

  /**
   * Forgets the state of clients whose scope is not in {@code liveScopes}, and closes the sessions
   * they left behind, which nobody can reach any more.
   */
  public synchronized void retainScopes(Set<String> liveScopes) {
    current.retainScopes(liveScopes);
    int released = closeIds(ownership.ownedByScopesOutside(liveScopes));
    if (released > 0) {
      LOG.info("Released {} heap session(s) of disconnected clients", released);
    }
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
    synchronized (this) {
      closeIds(new ArrayList<>(sessionsById.keySet()));
    }
  }
}

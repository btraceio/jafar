package io.jafar.mcp.session;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.ScopedCurrentSession;
import io.jafar.shell.core.SessionOwnership;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private final ScopedCurrentSession current = new ScopedCurrentSession();
  private final SessionOwnership ownership = new SessionOwnership();
  private final SessionOpener opener;

  private final SessionPersistenceStore persistenceStore = new SessionPersistenceStore();

  /** Builds the {@link JFRSession} for a recording; the seam that lets a test make an open slow. */
  @FunctionalInterface
  public interface SessionOpener {
    JFRSession open(Path path, ParsingContext context) throws Exception;
  }

  public SessionRegistry() {
    this(JFRSession::new);
  }

  public SessionRegistry(SessionOpener opener) {
    this.opener = opener;
    this.parsingContext = ParsingContext.create();
    LOG.info("SessionRegistry created with ParsingContext");
    restorePersistedSessions();
  }

  private void restorePersistedSessions() {
    for (SessionPersistenceStore.Entry entry : persistenceStore.load()) {
      try {
        Path path = Path.of(entry.path());
        if (!java.nio.file.Files.exists(path)) {
          LOG.warn("Skipping persisted session {}: file not found: {}", entry.id(), entry.path());
          persistenceStore.remove(entry.id());
          continue;
        }
        JFRSession session = new JFRSession(path, parsingContext);
        // Preserve the original session ID so callers can resume by the same ID.
        int id = entry.id();
        SessionInfo info = new SessionInfo(id, entry.alias(), path, Instant.now(), session);
        sessionsById.put(id, info);
        // Restored sessions belong to no client; they are every client's fallback.
        ownership.addUnowned(id, entry.alias());
        current.restored(id);
        // Ensure nextId is always beyond any restored ID.
        nextId.updateAndGet(cur -> Math.max(cur, id + 1));
        LOG.info("Restored session {} for recording: {}", id, path);
      } catch (Exception e) {
        LOG.warn("Cannot restore session {}: {}", entry.id(), e.getMessage());
        persistenceStore.remove(entry.id());
      }
    }
  }

  /** Shuts down the registry, closing all sessions. */
  public void shutdown() {
    LOG.info("Shutting down SessionRegistry, closing {} sessions", size());
    try {
      closeIds(new ArrayList<>(sessionsById.keySet()));
    } catch (Exception e) {
      LOG.warn("Error during SessionRegistry shutdown: {}", e.getMessage());
    }
  }

  /**
   * Opens a JFR recording file and creates a new session, owned by the calling client.
   *
   * <p>The recording is opened outside the registry lock, so one client opening a large file does
   * not stall every other client's lookups.
   *
   * @param path Path to the JFR recording file
   * @param alias Optional alias, unique among the caller's own sessions
   * @return Information about the opened session
   * @throws Exception if the recording cannot be opened
   */
  public SessionInfo open(Path path, String alias) throws Exception {
    String name = alias == null || alias.isBlank() ? null : alias;
    synchronized (this) {
      ownership.requireAliasFree(name); // fail before parsing a large recording, not after
    }

    JFRSession session = opener.open(path, parsingContext);

    synchronized (this) {
      int id = nextId.get();
      try {
        ownership.add(id, name); // re-checks: another open may have taken the alias meanwhile
      } catch (IllegalArgumentException lostTheRace) {
        try {
          session.close();
        } catch (Exception e) {
          LOG.warn("Error closing session that lost an alias race: {}", e.getMessage());
        }
        throw lostTheRace;
      }
      nextId.incrementAndGet();

      SessionInfo info = new SessionInfo(id, name, path, Instant.now(), session);
      sessionsById.put(id, info);
      current.opened(id);
      persistenceStore.upsert(id, path.toString(), name);

      LOG.info("Opened session {} for recording: {}", id, path);
      return info;
    }
  }

  /**
   * Gets a session by ID or alias, among those the calling client may see: its own and any shared
   * ones.
   *
   * @param idOrAlias Session ID (as string) or alias
   * @return The session info, or empty if not found
   */
  public synchronized Optional<SessionInfo> get(String idOrAlias) {
    Integer id = ownership.resolve(idOrAlias);
    return id == null ? Optional.empty() : Optional.ofNullable(sessionsById.get(id));
  }

  /**
   * Gets the current (most recently used) session.
   *
   * @return The current session, or empty if none
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
   * Lists the open sessions the calling client may see: its own and any shared ones.
   *
   * @return List of session information
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
        LOG.warn("Error closing session {}: {}", id, e.getMessage());
      }
    }
    return closed;
  }

  private void closeSession(SessionInfo info) throws Exception {
    sessionsById.remove(info.id());
    ownership.remove(info.id());
    info.session().close();
    persistenceStore.remove(info.id());

    current.closed(info.id());

    LOG.info("Closed session {}", info.id());
  }

  /**
   * Forgets the state of clients whose scope is not in {@code liveScopes}, and closes the sessions
   * they left behind, which nobody can reach any more.
   */
  public synchronized void retainScopes(Set<String> liveScopes) {
    current.retainScopes(liveScopes);
    int released = closeIds(ownership.ownedByScopesOutside(liveScopes));
    if (released > 0) {
      LOG.info("Released {} session(s) of disconnected clients", released);
    }
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

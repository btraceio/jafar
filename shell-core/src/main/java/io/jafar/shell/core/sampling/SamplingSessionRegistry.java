package io.jafar.shell.core.sampling;

import io.jafar.shell.core.ScopedCurrentSession;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionOwnership;
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
 * Generic thread-safe session registry for profiling formats (pprof, OTLP, etc.).
 *
 * <p>Manages open/close/get/list lifecycle for sessions identified by integer IDs or optional
 * aliases. Subclasses provide format-specific session creation and a display name for log messages.
 *
 * @param <S> the session type (must implement {@link Session})
 */
public abstract class SamplingSessionRegistry<S extends Session> {

  private final Logger log = LoggerFactory.getLogger(getClass());

  /** Information about an open profiling session. */
  public record SessionInfo<S extends Session>(
      int id, String alias, Path path, Instant openedAt, S session) {

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
        // best-effort; not fatal
      }
      return map;
    }
  }

  private int nextId = 1;
  private final Map<Integer, SessionInfo<S>> sessionsById = new LinkedHashMap<>();
  private final ScopedCurrentSession current = new ScopedCurrentSession();
  private final SessionOwnership ownership = new SessionOwnership();

  /**
   * Opens the file at {@code path} and returns a new session. Implemented by each format subclass.
   */
  protected abstract S openSession(Path path) throws IOException;

  /**
   * Returns the human-readable format name used in log and error messages (e.g. "pprof", "otlp").
   */
  protected abstract String formatName();

  /**
   * Opens a file and creates a new tracked session, owned by the calling client.
   *
   * <p>The file is parsed outside the registry lock, so one client opening a large profile does not
   * stall every other client's lookups.
   *
   * @param path path to the profile file
   * @param alias optional alias; null or blank for none. Unique among the caller's own sessions.
   * @return the new session info
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if the caller already uses the alias
   */
  public SessionInfo<S> open(Path path, String alias) throws IOException {
    String name = alias == null || alias.isBlank() ? null : alias;
    synchronized (this) {
      ownership.requireAliasFree(name); // fail before parsing a large file, not after
    }

    S session = openSession(path);

    synchronized (this) {
      int id = nextId;
      try {
        ownership.add(id, name); // re-checks: another open may have taken the alias meanwhile
      } catch (IllegalArgumentException lostTheRace) {
        closeQuietly(session, id);
        throw lostTheRace;
      }
      nextId++;
      SessionInfo<S> info = new SessionInfo<>(id, name, path, Instant.now(), session);
      sessionsById.put(id, info);
      current.opened(id);

      log.info("Opened {} session {} for: {}", formatName(), id, path);
      return info;
    }
  }

  /**
   * Gets a session by numeric ID or alias, among those the calling client may see: its own and any
   * shared ones.
   *
   * @return the session info, or empty if not found
   */
  public synchronized Optional<SessionInfo<S>> get(String idOrAlias) {
    Integer id = ownership.resolve(idOrAlias);
    return id == null ? Optional.empty() : Optional.ofNullable(sessionsById.get(id));
  }

  /**
   * Gets the most recently opened session.
   *
   * @return the current session info, or empty if no sessions are open
   */
  public synchronized Optional<SessionInfo<S>> getCurrent() {
    Integer currentSessionId = current.current();
    if (currentSessionId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(sessionsById.get(currentSessionId));
  }

  /**
   * Gets a session by ID/alias, defaulting to the current session if {@code idOrAlias} is blank.
   *
   * @throws IllegalArgumentException if no matching session is found
   */
  public synchronized SessionInfo<S> getOrCurrent(String idOrAlias) {
    if (idOrAlias == null || idOrAlias.isBlank()) {
      return getCurrent()
          .orElseThrow(
              () ->
                  new IllegalArgumentException(
                      "No "
                          + formatName()
                          + " profile open. Use "
                          + formatName()
                          + "_open first."));
    }
    return get(idOrAlias)
        .orElseThrow(
            () -> new IllegalArgumentException(formatName() + " session not found: " + idOrAlias));
  }

  /** Lists the open sessions the calling client may see: its own and any shared ones. */
  public synchronized List<SessionInfo<S>> list() {
    List<SessionInfo<S>> out = new ArrayList<>();
    for (int id : ownership.visibleIds(sessionsById.keySet())) {
      out.add(sessionsById.get(id));
    }
    return out;
  }

  /**
   * Closes a session by ID or alias.
   *
   * @return true if a session was closed, false if not found
   */
  public synchronized boolean close(String idOrAlias) {
    Optional<SessionInfo<S>> info = get(idOrAlias);
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
      SessionInfo<S> info = sessionsById.get(id);
      if (info == null) {
        continue;
      }
      try {
        closeSession(info);
        closed++;
      } catch (Exception e) {
        log.warn("Error closing {} session {}: {}", formatName(), id, e.getMessage());
      }
    }
    return closed;
  }

  private void closeSession(SessionInfo<S> info) {
    sessionsById.remove(info.id());
    ownership.remove(info.id());
    try {
      info.session().close();
    } catch (Exception e) {
      log.warn("Error closing {} session {}: {}", formatName(), info.id(), e.getMessage());
    }
    current.closed(info.id());
    log.info("Closed {} session {}", formatName(), info.id());
  }

  private void closeQuietly(S session, int id) {
    try {
      session.close();
    } catch (Exception e) {
      log.warn("Error closing {} session {}: {}", formatName(), id, e.getMessage());
    }
  }

  /**
   * Forgets the state of clients whose scope is not in {@code liveScopes}, and closes the sessions
   * they left behind, which nobody can reach any more.
   */
  public synchronized void retainScopes(Set<String> liveScopes) {
    current.retainScopes(liveScopes);
    int released = closeIds(ownership.ownedByScopesOutside(liveScopes));
    if (released > 0) {
      log.info("Released {} {} session(s) of disconnected clients", released, formatName());
    }
  }

  /** Returns the number of open sessions. */
  public synchronized int size() {
    return sessionsById.size();
  }

  /** Closes all sessions; called on server shutdown. */
  public synchronized void shutdown() {
    log.info("Shutting down {} registry, closing {} sessions", formatName(), size());
    closeIds(new ArrayList<>(sessionsById.keySet()));
  }
}

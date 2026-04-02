package io.jafar.shell.core.sampling;

import io.jafar.shell.core.Session;
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
  private final Map<String, Integer> idsByAlias = new HashMap<>();
  private Integer currentSessionId = null;

  /**
   * Opens the file at {@code path} and returns a new session. Implemented by each format subclass.
   */
  protected abstract S openSession(Path path) throws IOException;

  /**
   * Returns the human-readable format name used in log and error messages (e.g. "pprof", "otelp").
   */
  protected abstract String formatName();

  /**
   * Opens a file and creates a new tracked session.
   *
   * @param path path to the profile file
   * @param alias optional alias; null or blank for none
   * @return the new session info
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if the alias is already in use
   */
  public synchronized SessionInfo<S> open(Path path, String alias) throws IOException {
    if (alias != null && !alias.isBlank()) {
      if (idsByAlias.containsKey(alias)) {
        throw new IllegalArgumentException("Alias already in use: " + alias);
      }
    } else {
      alias = null;
    }

    S session = openSession(path);
    int id = nextId++;

    SessionInfo<S> info = new SessionInfo<>(id, alias, path, Instant.now(), session);
    sessionsById.put(id, info);
    if (alias != null) {
      idsByAlias.put(alias, id);
    }
    currentSessionId = id;

    log.info("Opened {} session {} for: {}", formatName(), id, path);
    return info;
  }

  /**
   * Gets a session by numeric ID or alias.
   *
   * @return the session info, or empty if not found
   */
  public synchronized Optional<SessionInfo<S>> get(String idOrAlias) {
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
   * Gets the most recently opened session.
   *
   * @return the current session info, or empty if no sessions are open
   */
  public synchronized Optional<SessionInfo<S>> getCurrent() {
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

  /** Lists all open sessions. */
  public synchronized List<SessionInfo<S>> list() {
    return new ArrayList<>(sessionsById.values());
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

  /** Closes all open sessions. */
  public synchronized void closeAll() {
    for (SessionInfo<S> info : new ArrayList<>(sessionsById.values())) {
      try {
        closeSession(info);
      } catch (Exception e) {
        log.warn("Error closing {} session {}: {}", formatName(), info.id(), e.getMessage());
      }
    }
  }

  private void closeSession(SessionInfo<S> info) {
    sessionsById.remove(info.id());
    if (info.alias() != null) {
      idsByAlias.remove(info.alias());
    }
    try {
      info.session().close();
    } catch (Exception e) {
      log.warn("Error closing {} session {}: {}", formatName(), info.id(), e.getMessage());
    }
    if (currentSessionId != null && currentSessionId == info.id()) {
      currentSessionId = sessionsById.isEmpty() ? null : sessionsById.keySet().iterator().next();
    }
    log.info("Closed {} session {}", formatName(), info.id());
  }

  /** Returns the number of open sessions. */
  public synchronized int size() {
    return sessionsById.size();
  }

  /** Closes all sessions; called on server shutdown. */
  public synchronized void shutdown() {
    log.info("Shutting down {} registry, closing {} sessions", formatName(), size());
    closeAll();
  }
}

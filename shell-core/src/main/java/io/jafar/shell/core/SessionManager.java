package io.jafar.shell.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages multiple analysis sessions, allowing open/list/switch/close operations. Supports both JFR
 * recording sessions and heap dump sessions through the generic Session interface.
 */
public final class SessionManager {

  /** Reference to an open session with its metadata. */
  public static final class SessionRef {
    public final int id;
    public final String alias;
    public final Session session;
    public final VariableStore variables;
    public String outputFormat = "table";

    public SessionRef(int id, String alias, Session session) {
      this.id = id;
      this.alias = alias;
      this.session = session;
      this.variables = new VariableStore();
    }

    /** Returns the session type identifier. */
    public String getType() {
      return session.getType();
    }
  }

  /** Factory for creating sessions from files. */
  @FunctionalInterface
  public interface SessionFactory {
    Session create(Path path, Object context) throws Exception;
  }

  private final SessionFactory factory;
  private final Object factoryContext;
  private final AtomicInteger nextId = new AtomicInteger(1);
  private final Map<Integer, SessionRef> byId = new LinkedHashMap<>();
  private final Map<String, Integer> byAlias = new HashMap<>();
  private Integer currentId = null;

  /**
   * Creates a SessionManager with a factory for creating sessions.
   *
   * @param factory the session factory
   * @param factoryContext context passed to factory when creating sessions (may be null)
   */
  public SessionManager(SessionFactory factory, Object factoryContext) {
    this.factory = Objects.requireNonNull(factory, "factory");
    this.factoryContext = factoryContext;
  }

  /**
   * Opens a new session for the given file.
   *
   * @param path file to open
   * @param alias optional alias for the session (may be null)
   * @return reference to the new session
   * @throws Exception if the session cannot be created
   */
  public synchronized SessionRef open(Path path, String alias) throws Exception {
    int id = nextId.getAndIncrement();
    if (alias != null) {
      if (byAlias.containsKey(alias)) {
        throw new IllegalArgumentException("Alias already in use: " + alias);
      }
    }
    Session session = factory.create(path, factoryContext);
    SessionRef ref = new SessionRef(id, alias, session);
    byId.put(id, ref);
    if (alias != null) {
      byAlias.put(alias, id);
    }
    currentId = id;
    return ref;
  }

  /**
   * Lists all open sessions.
   *
   * @return list of session references
   */
  public synchronized List<SessionRef> list() {
    return new ArrayList<>(byId.values());
  }

  /**
   * Gets the current active session.
   *
   * @return the current session, or empty if no session is active
   */
  public synchronized Optional<SessionRef> current() {
    return currentId == null ? Optional.empty() : Optional.ofNullable(byId.get(currentId));
  }

  /** Alias for current(). */
  public synchronized Optional<SessionRef> getCurrent() {
    return current();
  }

  /**
   * Gets a session by ID or alias.
   *
   * @param idOrAlias session ID (as string) or alias
   * @return the session, or empty if not found
   */
  public synchronized Optional<SessionRef> get(String idOrAlias) {
    Integer id = parseId(idOrAlias);
    if (id == null) {
      id = byAlias.get(idOrAlias);
    }
    return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
  }

  /**
   * Switches to the specified session.
   *
   * @param idOrAlias session ID or alias
   * @return true if the session was found and switched to
   */
  public synchronized boolean use(String idOrAlias) {
    Optional<SessionRef> ref = get(idOrAlias);
    if (ref.isPresent()) {
      currentId = ref.get().id;
      return true;
    }
    return false;
  }

  /**
   * Closes the specified session.
   *
   * @param idOrAlias session ID or alias
   * @return true if the session was found and closed
   * @throws Exception if closing fails
   */
  public synchronized boolean close(String idOrAlias) throws Exception {
    Optional<SessionRef> ref = get(idOrAlias);
    if (ref.isEmpty()) return false;
    closeById(ref.get().id);
    return true;
  }

  /**
   * Closes all open sessions.
   *
   * @throws Exception if closing fails
   */
  public synchronized void closeAll() throws Exception {
    for (Integer id : new ArrayList<>(byId.keySet())) {
      closeById(id);
    }
    currentId = null;
  }

  /** Returns the number of open sessions. */
  public synchronized int size() {
    return byId.size();
  }

  /** Returns whether there are any open sessions. */
  public synchronized boolean hasOpenSessions() {
    return !byId.isEmpty();
  }

  private void closeById(int id) throws Exception {
    SessionRef ref = byId.remove(id);
    if (ref != null) {
      if (ref.alias != null) byAlias.remove(ref.alias);
      ref.variables.clear();
      ref.session.close();
    }
    if (Objects.equals(currentId, id)) {
      currentId = byId.isEmpty() ? null : byId.keySet().iterator().next();
    }
  }

  private static Integer parseId(String s) {
    try {
      return Integer.parseInt(s);
    } catch (Exception ignore) {
      return null;
    }
  }
}

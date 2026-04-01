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
 * Manages multiple sessions, allowing open/list/switch/close operations.
 *
 * @param <S> the session type
 */
public final class SessionManager<S extends Session> {

  /** Factory for creating sessions from file paths. */
  public interface SessionFactory<S extends Session> {
    S create(Path path, Object context) throws Exception;
  }

  /** A reference to an open session with its metadata and variable store. */
  public static final class SessionRef<S extends Session> {
    public final int id;
    public final String alias;
    public final S session;
    public final VariableStore variables;
    public String outputFormat = "table";

    public SessionRef(int id, String alias, S session) {
      this.id = id;
      this.alias = alias;
      this.session = session;
      this.variables = new VariableStore();
    }
  }

  private final SessionFactory<S> factory;
  private final Object factoryContext;
  private final AtomicInteger nextId = new AtomicInteger(1);
  private final Map<Integer, SessionRef<S>> byId = new LinkedHashMap<>();
  private final Map<String, Integer> byAlias = new HashMap<>();
  private Integer currentId = null;

  /**
   * Creates a new session manager.
   *
   * @param factory the session factory
   * @param factoryContext optional context passed to the factory
   */
  public SessionManager(SessionFactory<S> factory, Object factoryContext) {
    this.factory = Objects.requireNonNull(factory, "factory");
    this.factoryContext = factoryContext;
  }

  public SessionRef<S> open(Path path, String alias) throws Exception {
    // Reserve the ID and validate alias without holding the lock during session creation.
    // factory.create() can be very slow (e.g. parsing a large heap dump) and the lock must
    // not be held during that time because other threads (e.g. the TUI render loop) need
    // synchronized access to sessions.current() / sessions.list().
    int id;
    synchronized (this) {
      if (alias != null && byAlias.containsKey(alias)) {
        throw new IllegalArgumentException("Alias already in use: " + alias);
      }
      id = nextId.getAndIncrement();
    }

    // Slow part — no lock held
    S session = factory.create(path, factoryContext);

    // Register with lock held briefly
    synchronized (this) {
      if (alias != null && byAlias.containsKey(alias)) {
        // Alias was claimed concurrently while we were creating the session
        session.close();
        throw new IllegalArgumentException("Alias already in use: " + alias);
      }
      SessionRef<S> ref = new SessionRef<>(id, alias, session);
      byId.put(id, ref);
      if (alias != null) {
        byAlias.put(alias, id);
      }
      currentId = id;
      return ref;
    }
  }

  public synchronized List<SessionRef<S>> list() {
    return new ArrayList<>(byId.values());
  }

  public synchronized Optional<SessionRef<S>> current() {
    return currentId == null ? Optional.empty() : Optional.ofNullable(byId.get(currentId));
  }

  public synchronized Optional<SessionRef<S>> getCurrent() {
    return current();
  }

  public synchronized Optional<SessionRef<S>> get(String idOrAlias) {
    Integer id = parseId(idOrAlias);
    if (id == null) {
      id = byAlias.get(idOrAlias);
    }
    return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
  }

  public synchronized boolean use(String idOrAlias) {
    Optional<SessionRef<S>> ref = get(idOrAlias);
    if (ref.isPresent()) {
      currentId = ref.get().id;
      return true;
    }
    return false;
  }

  public synchronized boolean close(String idOrAlias) throws Exception {
    Optional<SessionRef<S>> ref = get(idOrAlias);
    if (ref.isEmpty()) return false;
    closeById(ref.get().id);
    return true;
  }

  public synchronized void closeAll() throws Exception {
    for (Integer id : new ArrayList<>(byId.keySet())) {
      closeById(id);
    }
    currentId = null;
  }

  private void closeById(int id) throws Exception {
    SessionRef<S> ref = byId.remove(id);
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

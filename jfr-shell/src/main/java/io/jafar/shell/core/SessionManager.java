package io.jafar.shell.core;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Manages multiple JFR sessions, allowing open/list/switch/close operations. */
public class SessionManager {
  public interface JFRSessionFactory {
    JFRSession create(Path path, ParsingContext ctx) throws Exception;
  }

  public static final class SessionRef {
    public final int id;
    public final String alias;
    public final JFRSession session;

    public SessionRef(int id, String alias, JFRSession session) {
      this.id = id;
      this.alias = alias;
      this.session = session;
    }
  }

  private final ParsingContext context;
  private final JFRSessionFactory factory;
  private final AtomicInteger nextId = new AtomicInteger(1);
  private final Map<Integer, SessionRef> byId = new LinkedHashMap<>();
  private final Map<String, Integer> byAlias = new HashMap<>();
  private Integer currentId = null;

  public SessionManager(ParsingContext context, JFRSessionFactory factory) {
    this.context = Objects.requireNonNull(context, "context");
    this.factory = Objects.requireNonNull(factory, "factory");
  }

  public synchronized SessionRef open(Path path, String alias) throws Exception {
    int id = nextId.getAndIncrement();
    if (alias != null) {
      if (byAlias.containsKey(alias)) {
        throw new IllegalArgumentException("Alias already in use: " + alias);
      }
    }
    JFRSession session = factory.create(path, context);
    SessionRef ref = new SessionRef(id, alias, session);
    byId.put(id, ref);
    if (alias != null) {
      byAlias.put(alias, id);
    }
    currentId = id;
    return ref;
  }

  public synchronized List<SessionRef> list() {
    return new ArrayList<>(byId.values());
  }

  public synchronized Optional<SessionRef> current() {
    return currentId == null ? Optional.empty() : Optional.ofNullable(byId.get(currentId));
  }

  public synchronized Optional<SessionRef> getCurrent() { // alias
    return current();
  }

  public synchronized Optional<SessionRef> get(String idOrAlias) {
    Integer id = parseId(idOrAlias);
    if (id == null) {
      id = byAlias.get(idOrAlias);
    }
    return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
  }

  public synchronized boolean use(String idOrAlias) {
    Optional<SessionRef> ref = get(idOrAlias);
    if (ref.isPresent()) {
      currentId = ref.get().id;
      return true;
    }
    return false;
  }

  public synchronized boolean close(String idOrAlias) throws Exception {
    Optional<SessionRef> ref = get(idOrAlias);
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
    SessionRef ref = byId.remove(id);
    if (ref != null) {
      if (ref.alias != null) byAlias.remove(ref.alias);
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

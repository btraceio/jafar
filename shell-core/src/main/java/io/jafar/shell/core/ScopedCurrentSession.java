package io.jafar.shell.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tracks each caller's "current session" for a session registry, keyed by {@link
 * RequestScope#current()}.
 *
 * <p>A caller's current session is the most recent still-open session it opened itself. When that
 * closes, it falls back to the caller's previous one, never to a session another caller opened.
 * Sessions that belong to no connected caller are every caller's fallback once the caller has no
 * open session of its own: sessions restored at startup, and sessions still open when the caller
 * that opened them disconnected. The latter is what lets an MCP client that reconnects (and so gets
 * a new session id, hence a new scope) keep working with the recordings it had open.
 */
public final class ScopedCurrentSession {

  private final Map<String, Deque<Integer>> openedByScope = new HashMap<>();
  private final Deque<Integer> unowned = new ArrayDeque<>();

  /** Records that the calling scope opened session {@code id}, making it that scope's current. */
  public synchronized void opened(int id) {
    openedByScope.computeIfAbsent(RequestScope.current(), k -> new ArrayDeque<>()).addLast(id);
  }

  /** Records a session restored outside any request, usable as every scope's fallback. */
  public synchronized void restored(int id) {
    unowned.addLast(id);
  }

  /** Returns the calling scope's current session id, or {@code null} if it has none. */
  public synchronized Integer current() {
    Deque<Integer> own = openedByScope.get(RequestScope.current());
    if (own != null && !own.isEmpty()) {
      return own.peekLast();
    }
    return unowned.peekLast();
  }

  /** Forgets session {@code id} for every scope. */
  public synchronized void closed(int id) {
    Integer boxed = id;
    openedByScope.values().removeIf(ids -> ids.remove(boxed) && ids.isEmpty());
    unowned.remove(boxed);
  }

  /**
   * Drops the state of every non-null scope not in {@code liveScopes}, e.g. disconnected clients.
   * Sessions those scopes still had open become unowned fallbacks rather than being forgotten.
   */
  public synchronized void retainScopes(Set<String> liveScopes) {
    openedByScope
        .entrySet()
        .removeIf(
            entry -> {
              if (entry.getKey() == null || liveScopes.contains(entry.getKey())) {
                return false;
              }
              unowned.addAll(entry.getValue());
              return true;
            });
  }
}

package io.jafar.shell.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who owns each open session in a registry, and what each caller may see and close.
 *
 * <p>One registry instance serves every client of an MCP daemon, so without ownership one client
 * could close another's recordings, collide with its aliases, or read its sessions by guessing a
 * numeric id. A session belongs to the caller that opened it ({@link RequestScope#current()}); that
 * caller sees it, may close it, and may name it with an alias that only needs to be unique among
 * its own. Sessions restored at startup belong to no caller and are visible to all.
 *
 * <p>A caller with no scope is the server itself (shutdown, or code outside any request) and is
 * unrestricted. This is separate from the "current session" pointer in {@link
 * ScopedCurrentSession}, which an unscoped caller deliberately never inherits.
 *
 * <p>This class only does the bookkeeping. The registry still owns the sessions and does the
 * closing, and calls these methods under its own lock.
 */
public final class SessionOwnership {

  private record AliasKey(String scope, String alias) {}

  private final Map<Integer, String> ownerById = new LinkedHashMap<>();
  private final Map<AliasKey, Integer> idsByAlias = new HashMap<>();
  private final Map<Integer, AliasKey> aliasById = new HashMap<>();

  /** Fails if the calling scope already uses {@code alias}; call before opening anything costly. */
  public synchronized void requireAliasFree(String alias) {
    if (alias != null && idsByAlias.containsKey(new AliasKey(RequestScope.current(), alias))) {
      throw new IllegalArgumentException("Alias already in use: " + alias);
    }
  }

  /** Records session {@code id} as owned by the calling scope. */
  public synchronized void add(int id, String alias) {
    requireAliasFree(alias);
    record(id, RequestScope.current(), alias);
  }

  /** Records a session that belongs to no client, such as one restored at startup. */
  public synchronized void addUnowned(int id, String alias) {
    record(id, null, alias);
  }

  private void record(int id, String owner, String alias) {
    ownerById.put(id, owner);
    if (alias != null) {
      AliasKey key = new AliasKey(owner, alias);
      idsByAlias.put(key, id);
      aliasById.put(id, key);
    }
  }

  /** Forgets a session's owner and alias. */
  public synchronized void remove(int id) {
    ownerById.remove(id);
    AliasKey key = aliasById.remove(id);
    if (key != null) {
      idsByAlias.remove(key);
    }
  }

  /** Whether the calling scope may see session {@code id}. */
  public synchronized boolean isVisible(int id) {
    return ownerById.containsKey(id) && visibleTo(RequestScope.current(), ownerById.get(id));
  }

  /**
   * Resolves a numeric id or an alias to a session the caller may see, or {@code null}. An alias
   * resolves to the caller's own first, then to a shared one.
   */
  public synchronized Integer resolve(String idOrAlias) {
    if (idOrAlias == null || idOrAlias.isBlank()) {
      return null;
    }
    try {
      int id = Integer.parseInt(idOrAlias);
      return isVisible(id) ? id : null;
    } catch (NumberFormatException notANumber) {
      String caller = RequestScope.current();
      Integer own = idsByAlias.get(new AliasKey(caller, idOrAlias));
      if (own != null) {
        return own;
      }
      Integer shared = idsByAlias.get(new AliasKey(null, idOrAlias));
      if (shared != null) {
        return shared;
      }
      if (caller == null) { // the server itself: whichever client holds it
        for (Map.Entry<AliasKey, Integer> e : idsByAlias.entrySet()) {
          if (e.getKey().alias().equals(idOrAlias)) {
            return e.getValue();
          }
        }
      }
      return null;
    }
  }

  /** The ids among {@code all} that the caller may see, in the order given. */
  public synchronized List<Integer> visibleIds(Collection<Integer> all) {
    String caller = RequestScope.current();
    List<Integer> out = new ArrayList<>();
    for (int id : all) {
      if (ownerById.containsKey(id) && visibleTo(caller, ownerById.get(id))) {
        out.add(id);
      }
    }
    return out;
  }

  /**
   * The ids among {@code all} that a bulk close by the caller takes: everything it can see, which
   * is its own sessions and the shared ones, never another client's. The shared ones are included
   * because they appear in the caller's listings and nobody else owns them (sessions restored after
   * a restart), so "close all" that left them behind would look broken. The server itself may close
   * everything.
   */
  public synchronized List<Integer> closableByCaller(Collection<Integer> all) {
    String caller = RequestScope.current();
    List<Integer> out = new ArrayList<>();
    for (int id : all) {
      if (!ownerById.containsKey(id)) {
        continue;
      }
      if (visibleTo(caller, ownerById.get(id))) {
        out.add(id);
      }
    }
    return out;
  }

  /**
   * Ids owned by a client that is not in {@code liveScopes}: sessions nobody can reach any more.
   */
  public synchronized List<Integer> ownedByScopesOutside(Set<String> liveScopes) {
    List<Integer> out = new ArrayList<>();
    for (Map.Entry<Integer, String> e : ownerById.entrySet()) {
      if (e.getValue() != null && !liveScopes.contains(e.getValue())) {
        out.add(e.getKey());
      }
    }
    return out;
  }

  private static boolean visibleTo(String caller, String owner) {
    return caller == null || owner == null || owner.equals(caller);
  }
}

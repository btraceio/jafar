package io.jafar.shell.core.scripting;

import io.jafar.shell.core.VariableStore;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The scoping rules both shells apply to variables.
 *
 * <ul>
 *   <li>{@code set name = ...} writes the current session's store, or the global store when no
 *       session is open; {@code set --global} always writes the global store.
 *   <li>A name is looked up in the current session's store first, then in the global store.
 *   <li>{@code unset name} removes from the store {@code set} would have written, and if the name
 *       is not there, from the other one.
 * </ul>
 *
 * <p>A session's store is cleared when the session closes ({@code SessionManager} does that); the
 * global store lives as long as the shell.
 *
 * <p>Not thread-safe, like {@link VariableStore} beneath it: use it from the thread that dispatches
 * the shell's commands.
 */
public final class VariableScopes {
  private final VariableStore global;
  private final Supplier<VariableStore> session;

  /**
   * @param global the shell's global store
   * @param session the current session's store, or {@code null} when no session is open
   */
  public VariableScopes(VariableStore global, Supplier<VariableStore> session) {
    this.global = Objects.requireNonNull(global, "global");
    this.session = Objects.requireNonNull(session, "session");
  }

  /** The global store. */
  public VariableStore global() {
    return global;
  }

  /** The current session's store, or {@code null} when no session is open. */
  public VariableStore session() {
    return session.get();
  }

  /** Where {@code set} writes: the global store if asked for, else the session's if one is open. */
  public VariableStore target(boolean globalScope) {
    if (globalScope) {
      return global;
    }
    VariableStore s = session();
    return s != null ? s : global;
  }

  /** The variable visible under this name: session first, then global; {@code null} if none. */
  public VariableStore.Value resolve(String name) {
    VariableStore s = session();
    if (s != null && s.contains(name)) {
      return s.get(name);
    }
    return global.contains(name) ? global.get(name) : null;
  }

  /** {@code "global"}, {@code "session"}, or {@code null}: which store {@link #resolve} reads. */
  public String scopeOf(String name) {
    VariableStore s = session();
    if (s != null && s.contains(name)) {
      return "session";
    }
    return global.contains(name) ? "global" : null;
  }

  /**
   * Removes a variable from the {@link #target} store, or failing that from the other one.
   *
   * @return whether a variable was removed
   */
  public boolean remove(String name, boolean globalScope) {
    if (target(globalScope).remove(name)) {
      return true;
    }
    VariableStore other = globalScope ? session() : global;
    return other != null && other.remove(name);
  }
}

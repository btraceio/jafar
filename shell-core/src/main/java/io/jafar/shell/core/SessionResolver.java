package io.jafar.shell.core;

import java.util.Optional;

/** Resolves a session by ID or alias string. Used by cross-session operators like join. */
@FunctionalInterface
public interface SessionResolver {
  Optional<SessionManager.SessionRef<? extends Session>> resolve(String idOrAlias);
}

package io.jafar.shell.core;

/**
 * Thread-local scope key identifying the caller issuing the current request.
 *
 * <p>Session registries use this, via {@link ScopedCurrentSession}, to key their "current session"
 * convenience pointer per caller instead of a single JVM-wide value. This matters when one registry
 * instance is shared by multiple concurrent callers, such as an MCP server process handling several
 * client connections at once: without a per-caller scope, one caller's {@code *_open} silently
 * changes which session another caller's session-less tool calls resolve to.
 *
 * <p>The MCP server sets the scope to the MCP session id around every tool call, in stdio and SSE
 * mode alike (stdio sessions also get an id). Code that never sets it, such as a CLI shell or
 * registry work done outside a tool call, sees {@code null}. The scope is not propagated to other
 * threads: registry calls made off the tool-call thread fall into the {@code null} scope.
 */
public final class RequestScope {

  private static final ThreadLocal<String> KEY = new ThreadLocal<>();

  private RequestScope() {}

  /** Sets the scope key for the calling thread's remaining request handling. */
  public static void set(String scopeKey) {
    KEY.set(scopeKey);
  }

  /** Clears the scope key for the calling thread. Callers must do this in a {@code finally}. */
  public static void clear() {
    KEY.remove();
  }

  /** Returns the current scope key, or {@code null} if none is set. */
  public static String current() {
    return KEY.get();
  }
}

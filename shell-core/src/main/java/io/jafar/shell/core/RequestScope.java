package io.jafar.shell.core;

/**
 * Thread-local scope key identifying the caller issuing the current request.
 *
 * <p>Session registries (e.g. {@code SamplingSessionRegistry}) use this to key their "current
 * session" convenience pointer per caller instead of a single JVM-wide value. This matters when one
 * registry instance is shared by multiple concurrent callers, such as an MCP server process
 * handling several client connections at once: without a per-caller scope, one caller's {@code
 * *_open} silently changes which session another caller's session-less tool calls resolve to.
 *
 * <p>A single-caller process (a CLI shell, or an MCP server run over stdio, which only ever talks
 * to one client) never sets a scope key, so {@link #current()} returns {@code null} and every
 * caller shares the same (only) bucket — identical to the pre-existing behavior.
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

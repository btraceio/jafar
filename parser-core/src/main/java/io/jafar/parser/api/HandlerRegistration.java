package io.jafar.parser.api;

/**
 * Registration handle for a previously registered handler.
 *
 * <p>{@link #close()} deregisters the handler. It is idempotent, safe to call after the parser has
 * been closed, and safe to call from inside a handler or from another thread while {@code run()} is
 * in progress: an event already being dispatched when the handler is deregistered may still be
 * delivered to it, at most one per parsing thread; no later event is.
 *
 * <p>Each call to {@code handle} returns its own registration and each registration counts once:
 * closing one never releases another, including another registration of the same handler.
 *
 * <p>A parser and the parsers derived from it with {@code withParserListener} share their handlers,
 * so closing a registration deregisters the handler for all of them. Closing the last of those
 * parsers deregisters everything; a registration only needs to be closed to stop a handler while
 * the parser stays in use.
 *
 * @param <T> the handled event type
 */
public interface HandlerRegistration<T> extends AutoCloseable {
  /**
   * Deregisters the handler associated with this registration.
   *
   * <p>The default implementation throws {@link UnsupportedOperationException}, so that existing
   * implementations of this interface keep compiling; registrations returned by the parsers in this
   * library support it.
   *
   * @throws UnsupportedOperationException if this implementation does not support it
   */
  @Override
  default void close() {
    throw new UnsupportedOperationException(getClass().getName() + " does not support close");
  }

  /**
   * Deregisters the handler associated with this registration, if {@code cookie} is the parser that
   * created it; otherwise does nothing.
   *
   * @param cookie the parser that created this registration
   * @deprecated use {@link #close()}, which needs no parser argument
   */
  @Deprecated
  void destroy(JafarParser cookie);
}

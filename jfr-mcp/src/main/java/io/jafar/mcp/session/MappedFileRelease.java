package io.jafar.mcp.session;

/**
 * Asks the JVM to unmap the files of sessions that were just closed.
 *
 * <p>Recordings and heap dumps are read through memory-mapped buffers. Closing a session drops the
 * session's references to them, but the JDK unmaps a buffer only when the garbage collector has
 * found it unreachable and its cleaner has run. Until then, Windows refuses to delete, move or
 * overwrite the file (#227). The mapping cannot be released at close time: event values resolve
 * constant-pool entries from the buffer lazily, so after an explicit unmap a late read could crash
 * the JVM.
 *
 * <p>{@link #afterClose()} therefore requests a collection. It is a request: with {@code
 * -XX:+DisableExplicitGC} it does nothing, and the cleaner thread unmaps the buffers shortly after
 * the collection, not before this method returns.
 */
public final class MappedFileRelease {

  private MappedFileRelease() {}

  /**
   * Requests a garbage collection so the closed sessions' mapped buffers are unmapped. Call it
   * outside any lock: a full collection on a large heap can take a while.
   */
  public static void afterClose() {
    System.gc();
  }
}

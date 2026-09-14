package io.jafar.shell.core.analysis;

/**
 * Reports how far a long analysis has got.
 *
 * <p>The analyses used to call the MCP server's {@code sendProgress} directly, which is a large
 * part of why they could not be called from anywhere else. This is the same notification with the
 * transport removed: the MCP server forwards it as a progress notification, the shell can print it
 * or ignore it, and a test uses {@link #NONE}.
 */
@FunctionalInterface
public interface Progress {

  /**
   * @param current steps finished
   * @param total steps expected; a best guess, not a promise
   * @param message what is happening now, in words a user would recognise
   */
  void step(int current, int total, String message);

  /** Discards progress. For callers that have nowhere to show it. */
  Progress NONE = (current, total, message) -> {};
}

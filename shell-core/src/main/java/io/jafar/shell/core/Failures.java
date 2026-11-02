package io.jafar.shell.core;

/** Turns a failure into one line a user can act on. */
public final class Failures {

  private Failures() {}

  /**
   * The failure's message followed by every cause message it does not already contain, joined by
   * {@code ": "}. Wrappers that repeat their cause's message add nothing, so the line names what
   * failed (the outermost message) and why (the root cause) once each.
   */
  public static String describe(Throwable e) {
    StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage()));
    for (Throwable c = e.getCause(); c != null && c != c.getCause(); c = c.getCause()) {
      if (c.getMessage() != null && !sb.toString().contains(c.getMessage())) {
        sb.append(": ").append(c.getMessage());
      }
    }
    return sb.toString();
  }
}

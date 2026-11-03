package io.jafar.shell.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Turns a failure into one line a user can act on. */
public final class Failures {

  private Failures() {}

  /**
   * The failure's message followed by every cause message it does not already contain, joined by
   * {@code ": "}. Wrappers that repeat their cause's message add nothing, so the line names what
   * failed (the outermost message) and why (the root cause) once each. A failure without a message
   * is named by its type, so the line is never just {@code "null"}. Cause chains with cycles are
   * walked once.
   */
  public static String describe(Throwable e) {
    StringBuilder sb = new StringBuilder(messageOrType(e));
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    seen.add(e);
    for (Throwable c = e.getCause(); c != null && seen.add(c); c = c.getCause()) {
      if (c.getMessage() != null && !sb.toString().contains(c.getMessage())) {
        sb.append(": ").append(c.getMessage());
      }
    }
    return sb.toString();
  }

  private static String messageOrType(Throwable e) {
    String message = e.getMessage();
    return message == null || message.isEmpty() ? e.getClass().getName() : message;
  }
}

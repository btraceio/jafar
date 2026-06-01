package io.jafar.mcp.result;

import java.util.List;

/** Utility for in-place response-size limiting. */
public final class ResultLimiter {

  private ResultLimiter() {}

  /**
   * Caps {@code list} at {@code max} elements in place. If truncated, returns the number of removed
   * rows; callers can surface a truncation marker in the response.
   */
  public static int truncate(List<?> list, int max) {
    int size = list.size();
    if (size <= max) {
      return 0;
    }
    int removed = size - max;
    list.subList(max, size).clear();
    return removed;
  }
}

package io.jafar.shell.cli;

/** Simple, self-contained pager for CLI output. */
final class PagedPrinter {
  private final CommandDispatcher.IO io;
  private final boolean enabled;
  private final int pageSize;
  private int lineCount = 0;
  private boolean aborted = false;

  private PagedPrinter(CommandDispatcher.IO io, boolean enabled, int pageSize) {
    this.io = io;
    this.enabled = enabled;
    this.pageSize = Math.max(5, pageSize);
  }

  static PagedPrinter forIO(CommandDispatcher.IO io) {
    boolean enable = decideEnabled();
    int size = decidePageSize();
    return new PagedPrinter(io, enable, size);
  }

  void println(String s) {
    if (aborted) return;
    io.println(s);
    if (!enabled) return;
    lineCount++;
    if (lineCount >= pageSize) {
      if (!promptMore()) {
        aborted = true;
        return;
      }
      lineCount = 0;
    }
  }

  void printf(String fmt, Object... args) {
    if (aborted) return;
    io.printf(fmt, args);
    // Heuristic: count this as one line if ends with newline, else ignore.
    if (!enabled) return;
    if (fmt.endsWith("\n")) {
      lineCount++;
      if (lineCount >= pageSize) {
        if (!promptMore()) {
          aborted = true;
          return;
        }
        lineCount = 0;
      }
    }
  }

  private boolean promptMore() {
    // Non-interactive fallback: do not block
    if (System.console() == null) return true;
    try {
      io.printf("-- more -- (Enter: next, q: quit) ");
      int ch = System.in.read();
      // Drain until newline
      while (ch != -1 && ch != '\n') {
        if (ch == 'q' || ch == 'Q') {
          io.println("");
          return false;
        }
        ch = System.in.read();
      }
      io.println("");
      return true;
    } catch (Exception ignore) {
      return true;
    }
  }

  private static boolean decideEnabled() {
    String env = System.getenv("JFR_SHELL_PAGER");
    if (env != null) {
      String v = env.trim().toLowerCase();
      if (v.equals("0") || v.equals("off") || v.equals("false")) return false;
      if (v.equals("1") || v.equals("on") || v.equals("true")) return true;
    }
    // Default: enable only when attached to a console (interactive)
    return System.console() != null;
  }

  private static int decidePageSize() {
    String env = System.getenv("JFR_SHELL_PAGE_SIZE");
    if (env != null) {
      try {
        return Math.max(5, Integer.parseInt(env.trim()));
      } catch (NumberFormatException ignore) {
      }
    }
    return 24; // conservative default
  }
}

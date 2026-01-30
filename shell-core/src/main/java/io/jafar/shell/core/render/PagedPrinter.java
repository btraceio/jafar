package io.jafar.shell.core.render;

import io.jafar.shell.core.OutputWriter;

/** Simple, self-contained pager for CLI output. */
public final class PagedPrinter {
  private final OutputWriter out;
  private final boolean enabled;
  private final int pageSize;
  private int lineCount = 0;
  private boolean aborted = false;

  private PagedPrinter(OutputWriter out, boolean enabled, int pageSize) {
    this.out = out;
    this.enabled = enabled;
    this.pageSize = Math.max(5, pageSize);
  }

  /** Creates a PagedPrinter for the given output writer with default settings. */
  public static PagedPrinter forOutput(OutputWriter out) {
    boolean enable = decideEnabled();
    int size = decidePageSize();
    return new PagedPrinter(out, enable, size);
  }

  /** Creates a PagedPrinter with explicit settings. */
  public static PagedPrinter create(OutputWriter out, boolean enabled, int pageSize) {
    return new PagedPrinter(out, enabled, pageSize);
  }

  /** Prints a line, pausing for pager if needed. */
  public void println(String s) {
    if (aborted) return;
    out.println(s);
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

  /** Prints formatted output, tracking lines for paging. */
  public void printf(String fmt, Object... args) {
    if (aborted) return;
    out.printf(fmt, args);
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

  /** Returns whether output has been aborted by user. */
  public boolean isAborted() {
    return aborted;
  }

  private boolean promptMore() {
    // Non-interactive fallback: do not block
    if (System.console() == null) return true;

    // Additional safety: re-check the pager property
    String prop = System.getProperty("shell.pager");
    if (prop != null) {
      String v = prop.trim().toLowerCase();
      if (v.equals("0") || v.equals("off") || v.equals("false")) {
        return true;
      }
    }

    try {
      out.printf("-- more -- (Enter: next, q: quit) ");
      int ch = System.in.read();
      // Drain until newline
      while (ch != -1 && ch != '\n') {
        if (ch == 'q' || ch == 'Q') {
          out.println("");
          return false;
        }
        ch = System.in.read();
      }
      out.println("");
      return true;
    } catch (Exception ignore) {
      return true;
    }
  }

  private static boolean decideEnabled() {
    // Check system property first
    String prop = System.getProperty("shell.pager");
    if (prop != null) {
      String v = prop.trim().toLowerCase();
      if (v.equals("0") || v.equals("off") || v.equals("false")) return false;
      if (v.equals("1") || v.equals("on") || v.equals("true")) return true;
    }
    // Also check JFR-specific property for backward compatibility
    prop = System.getProperty("jfr.shell.pager");
    if (prop != null) {
      String v = prop.trim().toLowerCase();
      if (v.equals("0") || v.equals("off") || v.equals("false")) return false;
      if (v.equals("1") || v.equals("on") || v.equals("true")) return true;
    }
    // Check environment variable
    String env = System.getenv("SHELL_PAGER");
    if (env != null) {
      String v = env.trim().toLowerCase();
      if (v.equals("0") || v.equals("off") || v.equals("false")) return false;
      if (v.equals("1") || v.equals("on") || v.equals("true")) return true;
    }
    env = System.getenv("JFR_SHELL_PAGER");
    if (env != null) {
      String v = env.trim().toLowerCase();
      if (v.equals("0") || v.equals("off") || v.equals("false")) return false;
      if (v.equals("1") || v.equals("on") || v.equals("true")) return true;
    }
    // Default: enable only when attached to a console (interactive)
    return System.console() != null;
  }

  private static int decidePageSize() {
    String env = System.getenv("SHELL_PAGE_SIZE");
    if (env != null) {
      try {
        return Math.max(5, Integer.parseInt(env.trim()));
      } catch (NumberFormatException ignore) {
      }
    }
    env = System.getenv("JFR_SHELL_PAGE_SIZE");
    if (env != null) {
      try {
        return Math.max(5, Integer.parseInt(env.trim()));
      } catch (NumberFormatException ignore) {
      }
    }
    return 24; // conservative default
  }
}

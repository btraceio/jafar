package io.jafar.shell.core;

import java.io.PrintStream;

/**
 * Abstraction for shell output. Allows renderers to write output without depending on specific
 * terminal implementations.
 */
public interface OutputWriter {

  /** Prints a line to standard output. */
  void println(String s);

  /** Prints formatted output to standard output. */
  void printf(String fmt, Object... args);

  /** Prints an error message (typically to stderr or in a different color). */
  void error(String s);

  /** Creates an OutputWriter that writes to the given PrintStream. */
  static OutputWriter forPrintStream(PrintStream out) {
    return forPrintStream(out, out);
  }

  /** Creates an OutputWriter that writes to separate stdout and stderr streams. */
  static OutputWriter forPrintStream(PrintStream out, PrintStream err) {
    return new OutputWriter() {
      @Override
      public void println(String s) {
        out.println(s);
      }

      @Override
      public void printf(String fmt, Object... args) {
        out.printf(fmt, args);
      }

      @Override
      public void error(String s) {
        err.println(s);
      }
    };
  }

  /** Creates an OutputWriter that writes to System.out and System.err. */
  static OutputWriter system() {
    return forPrintStream(System.out, System.err);
  }
}

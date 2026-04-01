package io.jafar.pprof.shell.pprofpath;

/** Thrown when a pprof query string cannot be parsed. */
public final class PprofPathParseException extends RuntimeException {

  public PprofPathParseException(String message) {
    super(message);
  }
}

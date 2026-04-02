package io.jafar.pprof.shell.pprofpath;

import io.jafar.shell.core.sampling.path.SamplesPathParseException;

/** Thrown when a pprof samples query string is syntactically invalid. */
public class PprofPathParseException extends SamplesPathParseException {
  public PprofPathParseException(String message) {
    super(message);
  }
}

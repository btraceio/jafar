package io.jafar.shell.core.sampling.path;

/** Thrown when a samples query string is syntactically invalid. */
public class SamplesPathParseException extends RuntimeException {
  public SamplesPathParseException(String message) {
    super(message);
  }
}

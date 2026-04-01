package io.jafar.otelp.shell.otelppath;

/** Thrown when an OtelpPath query string cannot be parsed. */
public final class OtelpPathParseException extends RuntimeException {

  public OtelpPathParseException(String message) {
    super(message);
  }
}

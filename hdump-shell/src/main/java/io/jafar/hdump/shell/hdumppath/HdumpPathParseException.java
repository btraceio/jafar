package io.jafar.hdump.shell.hdumppath;

/** Exception thrown when HdumpPath query parsing fails. */
public final class HdumpPathParseException extends RuntimeException {

  public HdumpPathParseException(String message) {
    super(message);
  }

  public HdumpPathParseException(String message, Throwable cause) {
    super(message, cause);
  }
}

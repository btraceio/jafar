package io.jafar.parser.api;

/**
 * Base exception for all Jafar parsing related errors. Provides contextual information to help with
 * debugging.
 */
public class JafarParseException extends Exception {
  /** Contextual information about where the parsing error occurred. */
  private final String context;

  /** Error code identifying the specific type of parsing error. */
  private final String errorCode;

  /**
   * Constructs a new JafarParseException with the specified message.
   *
   * @param message the detail message
   */
  public JafarParseException(String message) {
    this(message, null, null);
  }

  /**
   * Constructs a new JafarParseException with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public JafarParseException(String message, Throwable cause) {
    this(message, cause, null, null);
  }

  /**
   * Constructs a new JafarParseException with the specified message, context, and error code.
   *
   * @param message the detail message
   * @param context the context information
   * @param errorCode the error code
   */
  public JafarParseException(String message, String context, String errorCode) {
    this(message, null, context, errorCode);
  }

  /**
   * Constructs a new JafarParseException with the specified message, cause, context, and error
   * code.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   * @param context the context information
   * @param errorCode the error code
   */
  public JafarParseException(String message, Throwable cause, String context, String errorCode) {
    super(formatMessage(message, context, errorCode), cause);
    this.context = context;
    this.errorCode = errorCode;
  }

  /**
   * Formats the message with context and error code information.
   *
   * @param message the base message
   * @param context the context information
   * @param errorCode the error code
   * @return the formatted message
   */
  private static String formatMessage(String message, String context, String errorCode) {
    StringBuilder sb = new StringBuilder(message);
    if (context != null) {
      sb.append(" [Context: ").append(context).append("]");
    }
    if (errorCode != null) {
      sb.append(" [Error Code: ").append(errorCode).append("]");
    }
    return sb.toString();
  }

  /**
   * Gets the context information for this exception.
   *
   * @return the context information, or null if none
   */
  public String getContext() {
    return context;
  }

  /**
   * Gets the error code for this exception.
   *
   * @return the error code, or null if none
   */
  public String getErrorCode() {
    return errorCode;
  }
}

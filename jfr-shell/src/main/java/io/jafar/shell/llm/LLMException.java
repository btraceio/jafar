package io.jafar.shell.llm;

/**
 * Exception thrown when LLM operations fail. Provides categorization of errors to enable
 * appropriate error handling and user messaging.
 */
public class LLMException extends Exception {

  /** The type of error that occurred. */
  public enum ErrorType {
    /** LLM provider is not available or unreachable. */
    PROVIDER_UNAVAILABLE,
    /** Request timed out waiting for LLM response. */
    TIMEOUT,
    /** LLM returned a response that cannot be parsed or is invalid. */
    INVALID_RESPONSE,
    /** Request was rate limited by the provider. */
    RATE_LIMITED,
    /** Authentication failed (invalid API key, etc.). */
    AUTH_FAILED,
    /** Network error occurred during communication. */
    NETWORK_ERROR,
    /** Failed to parse the LLM response. */
    PARSE_ERROR
  }

  private final ErrorType type;
  private final boolean retryable;

  /**
   * Creates a new LLM exception.
   *
   * @param type the error type
   * @param message the error message
   */
  public LLMException(ErrorType type, String message) {
    super(message);
    this.type = type;
    this.retryable = determineRetryable(type);
  }

  /**
   * Creates a new LLM exception with a cause.
   *
   * @param type the error type
   * @param message the error message
   * @param cause the underlying cause
   */
  public LLMException(ErrorType type, String message, Throwable cause) {
    super(message, cause);
    this.type = type;
    this.retryable = determineRetryable(type);
  }

  /**
   * Determines whether an error type is retryable.
   *
   * @param type the error type
   * @return true if the operation can be retried
   */
  private static boolean determineRetryable(ErrorType type) {
    return switch (type) {
      case TIMEOUT, NETWORK_ERROR, RATE_LIMITED -> true;
      case PROVIDER_UNAVAILABLE, AUTH_FAILED, INVALID_RESPONSE, PARSE_ERROR -> false;
    };
  }

  /**
   * Gets the error type.
   *
   * @return the error type
   */
  public ErrorType getType() {
    return type;
  }

  /**
   * Checks if this error is retryable.
   *
   * @return true if the operation can be retried
   */
  public boolean isRetryable() {
    return retryable;
  }
}

package io.jafar.mcp.exception;

/**
 * Exception thrown by JFR MCP server operations.
 *
 * <p>Provides clear error messages for MCP tool failures.
 */
public class JfrMcpException extends RuntimeException {

  /**
   * Creates exception with message.
   *
   * @param message error message
   */
  public JfrMcpException(String message) {
    super(message);
  }

  /**
   * Creates exception with message and cause.
   *
   * @param message error message
   * @param cause underlying cause
   */
  public JfrMcpException(String message, Throwable cause) {
    super(message, cause);
  }
}

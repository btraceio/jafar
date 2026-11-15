package io.jafar.parser.api;

/**
 * Interface for handling errors that occur during JFR parsing.
 *
 * @deprecated Nothing in Jafar ever calls an {@code ErrorHandler}: no parser accepts one, so
 *     neither {@link #DEFAULT} nor {@link #lenient()} has any effect. To decide what happens to an
 *     event the untyped parser cannot decode, use {@link
 *     UntypedJafarParser#onDecodeError(UntypedJafarParser.DecodeErrorHandler)}. Kept only for
 *     source and binary compatibility; it will be removed.
 */
@Deprecated
public interface ErrorHandler {

  /** Default error handler that logs errors and propagates them as RuntimeExceptions. */
  ErrorHandler DEFAULT =
      new ErrorHandler() {
        @Override
        public void handleConfigurationError(JafarConfigurationException e) {
          throw new RuntimeException("Configuration error: " + e.getMessage(), e);
        }

        @Override
        public void handleSerializationError(JafarSerializationException e) {
          throw new RuntimeException("Serialization error: " + e.getMessage(), e);
        }

        @Override
        public void handleIOError(JafarIOException e) {
          throw new RuntimeException("I/O error: " + e.getMessage(), e);
        }

        @Override
        public void handleGenericError(JafarParseException e) {
          throw new RuntimeException("Parse error: " + e.getMessage(), e);
        }
      };

  /**
   * Handle configuration-related errors.
   *
   * @param e the configuration exception
   */
  void handleConfigurationError(JafarConfigurationException e);

  /**
   * Handle serialization/bytecode generation errors.
   *
   * @param e the serialization exception
   */
  void handleSerializationError(JafarSerializationException e);

  /**
   * Handle I/O related errors.
   *
   * @param e the I/O exception
   */
  void handleIOError(JafarIOException e);

  /**
   * Handle generic parsing errors.
   *
   * @param e the parse exception
   */
  void handleGenericError(JafarParseException e);

  /**
   * Creates a lenient error handler that logs errors but continues processing.
   *
   * @return a lenient error handler
   */
  static ErrorHandler lenient() {
    return new ErrorHandler() {
      @Override
      public void handleConfigurationError(JafarConfigurationException e) {
        System.err.println("Warning - Configuration error: " + e.getMessage());
      }

      @Override
      public void handleSerializationError(JafarSerializationException e) {
        System.err.println("Warning - Serialization error: " + e.getMessage());
      }

      @Override
      public void handleIOError(JafarIOException e) {
        System.err.println("Warning - I/O error: " + e.getMessage());
      }

      @Override
      public void handleGenericError(JafarParseException e) {
        System.err.println("Warning - Parse error: " + e.getMessage());
      }
    };
  }
}

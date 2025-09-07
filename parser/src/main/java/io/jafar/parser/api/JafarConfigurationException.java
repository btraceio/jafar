package io.jafar.parser.api;

/**
 * Exception thrown when there are issues with Jafar parser configuration, such as missing or
 * invalid annotations, incorrect interface definitions, etc.
 */
public class JafarConfigurationException extends JafarParseException {

  /**
   * Constructs a new JafarConfigurationException with the specified message.
   *
   * @param message the detail message
   */
  public JafarConfigurationException(String message) {
    super(message, null, "CONFIG");
  }

  /**
   * Constructs a new JafarConfigurationException with the specified message and context.
   *
   * @param message the detail message
   * @param context the context information
   */
  public JafarConfigurationException(String message, String context) {
    super(message, context, "CONFIG");
  }

  /**
   * Constructs a new JafarConfigurationException with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public JafarConfigurationException(String message, Throwable cause) {
    super(message, cause, null, "CONFIG");
  }

  /**
   * Constructs a new JafarConfigurationException with the specified message, cause, and context.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   * @param context the context information
   */
  public JafarConfigurationException(String message, Throwable cause, String context) {
    super(message, cause, context, "CONFIG");
  }

  /**
   * Creates a JafarConfigurationException for a missing annotation.
   *
   * @param clazz the class that is missing the annotation
   * @param annotationType the type of annotation that is missing
   * @return a new JafarConfigurationException instance
   */
  public static JafarConfigurationException missingAnnotation(
      Class<?> clazz, String annotationType) {
    return new JafarConfigurationException(
        String.format("Missing @%s annotation on class", annotationType), clazz.getName());
  }

  /**
   * Creates a JafarConfigurationException for an invalid interface.
   *
   * @param clazz the class that is not a valid interface
   * @return a new JafarConfigurationException instance
   */
  public static JafarConfigurationException invalidInterface(Class<?> clazz) {
    return new JafarConfigurationException(
        "JFR type handler must be an interface", clazz.getName());
  }

  /**
   * Creates a JafarConfigurationException for an invalid field mapping.
   *
   * @param clazz the class containing the invalid field mapping
   * @param fieldName the name of the field with invalid mapping
   * @param reason the reason why the mapping is invalid
   * @return a new JafarConfigurationException instance
   */
  public static JafarConfigurationException invalidFieldMapping(
      Class<?> clazz, String fieldName, String reason) {
    return new JafarConfigurationException(
        String.format("Invalid field mapping for '%s': %s", fieldName, reason), clazz.getName());
  }
}

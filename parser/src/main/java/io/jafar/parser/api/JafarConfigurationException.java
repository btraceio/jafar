package io.jafar.parser.api;

/**
 * Exception thrown when there are issues with Jafar parser configuration,
 * such as missing or invalid annotations, incorrect interface definitions, etc.
 */
public class JafarConfigurationException extends JafarParseException {
    
    public JafarConfigurationException(String message) {
        super(message, null, "CONFIG");
    }

    public JafarConfigurationException(String message, String context) {
        super(message, context, "CONFIG");
    }

    public JafarConfigurationException(String message, Throwable cause) {
        super(message, cause, null, "CONFIG");
    }

    public JafarConfigurationException(String message, Throwable cause, String context) {
        super(message, cause, context, "CONFIG");
    }

    public static JafarConfigurationException missingAnnotation(Class<?> clazz, String annotationType) {
        return new JafarConfigurationException(
            String.format("Missing @%s annotation on class", annotationType),
            clazz.getName()
        );
    }

    public static JafarConfigurationException invalidInterface(Class<?> clazz) {
        return new JafarConfigurationException(
            "JFR type handler must be an interface",
            clazz.getName()
        );
    }

    public static JafarConfigurationException invalidFieldMapping(Class<?> clazz, String fieldName, String reason) {
        return new JafarConfigurationException(
            String.format("Invalid field mapping for '%s': %s", fieldName, reason),
            clazz.getName()
        );
    }
} 
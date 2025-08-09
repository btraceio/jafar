package io.jafar.parser.api;

import io.jafar.parser.api.lazy.JfrType;

/**
 * Utility class for common validation operations that throw appropriate exceptions.
 */
public final class ValidationUtils {
    
    private ValidationUtils() {
        // Utility class - no instantiation
    }

    /**
     * Validates that a class is suitable for JFR type handling.
     * 
     * @param clazz the class to validate
     * @throws JafarConfigurationException if validation fails
     */
    public static void validateJfrTypeHandler(Class<?> clazz) throws JafarConfigurationException {
        if (clazz == null) {
            throw new JafarConfigurationException("JFR type handler class cannot be null");
        }

        // Skip validation for primitive types and String
        if (clazz.isPrimitive() || clazz.isAssignableFrom(String.class)) {
            return;
        }

        // Must be an interface
        if (!clazz.isInterface()) {
            throw JafarConfigurationException.invalidInterface(clazz);
        }

        // Must have @JfrType annotation
        JfrType typeAnnotation = clazz.getAnnotation(JfrType.class);
        if (typeAnnotation == null) {
            throw JafarConfigurationException.missingAnnotation(clazz, "JfrType");
        }

        // Annotation value must not be empty
        if (typeAnnotation.value() == null || typeAnnotation.value().trim().isEmpty()) {
            throw new JafarConfigurationException(
                "JfrType annotation value cannot be empty",
                clazz.getName()
            );
        }
    }

    /**
     * Validates that a parser is not closed before operation.
     * 
     * @param closed whether the parser is closed
     * @throws JafarIOException if parser is closed
     */
    public static void validateParserNotClosed(boolean closed) throws JafarIOException {
        if (closed) {
            throw JafarIOException.parserClosed();
        }
    }

    /**
     * Validates that required parameters are not null.
     * 
     * @param value the value to check
     * @param parameterName the name of the parameter for error reporting
     * @throws JafarConfigurationException if value is null
     */
    public static void requireNonNull(Object value, String parameterName) throws JafarConfigurationException {
        if (value == null) {
            throw new JafarConfigurationException(
                String.format("Required parameter '%s' cannot be null", parameterName)
            );
        }
    }

    /**
     * Validates that a string parameter is not null or empty.
     * 
     * @param value the string to check
     * @param parameterName the name of the parameter for error reporting
     * @throws JafarConfigurationException if value is null or empty
     */
    public static void requireNonEmpty(String value, String parameterName) throws JafarConfigurationException {
        if (value == null || value.trim().isEmpty()) {
            throw new JafarConfigurationException(
                String.format("Required parameter '%s' cannot be null or empty", parameterName)
            );
        }
    }
} 
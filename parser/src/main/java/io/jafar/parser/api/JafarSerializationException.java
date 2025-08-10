package io.jafar.parser.api;

import java.nio.file.Path;

/**
 * Exception thrown during bytecode generation and class serialization operations.
 */
public class JafarSerializationException extends JafarParseException {
    
    public JafarSerializationException(String message) {
        super(message, null, "SERIALIZATION");
    }

    public JafarSerializationException(String message, String context) {
        super(message, context, "SERIALIZATION");
    }

    public JafarSerializationException(String message, Throwable cause) {
        super(message, cause, null, "SERIALIZATION");
    }

    public JafarSerializationException(String message, Throwable cause, String context) {
        super(message, cause, context, "SERIALIZATION");
    }

    public static JafarSerializationException bytecodeGenerationFailed(String className, Throwable cause) {
        return new JafarSerializationException(
            "Failed to generate bytecode for class",
            cause,
            className
        );
    }

    public static JafarSerializationException bytecodeGenerationFailed(String className, Path debugPath, Throwable cause) {
        return new JafarSerializationException(
            String.format("Failed to generate bytecode for class. Debug bytecode available at: %s", debugPath),
            cause,
            className
        );
    }

    public static JafarSerializationException classLoadingFailed(String className, Throwable cause) {
        return new JafarSerializationException(
            "Failed to load generated handler class",
            cause,
            className
        );
    }

    public static JafarSerializationException methodHandleCreationFailed(String className, String methodName, Throwable cause) {
        return new JafarSerializationException(
            String.format("Failed to create method handle for method '%s'", methodName),
            cause,
            className
        );
    }
} 
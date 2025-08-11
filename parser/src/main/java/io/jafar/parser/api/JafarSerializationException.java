package io.jafar.parser.api;

import java.nio.file.Path;

/**
 * Exception thrown during bytecode generation and class serialization operations.
 */
public class JafarSerializationException extends JafarParseException {
    
    /**
     * Constructs a new JafarSerializationException with the specified message.
     * 
     * @param message the detail message
     */
    public JafarSerializationException(String message) {
        super(message, null, "SERIALIZATION");
    }

    /**
     * Constructs a new JafarSerializationException with the specified message and context.
     * 
     * @param message the detail message
     * @param context the context information
     */
    public JafarSerializationException(String message, String context) {
        super(message, context, "SERIALIZATION");
    }

    /**
     * Constructs a new JafarSerializationException with the specified message and cause.
     * 
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public JafarSerializationException(String message, Throwable cause) {
        super(message, cause, null, "SERIALIZATION");
    }

    /**
     * Constructs a new JafarSerializationException with the specified message, cause, and context.
     * 
     * @param message the detail message
     * @param cause the cause of the exception
     * @param context the context information
     */
    public JafarSerializationException(String message, Throwable cause, String context) {
        super(message, cause, context, "SERIALIZATION");
    }

    /**
     * Creates a JafarSerializationException for bytecode generation failure.
     * 
     * @param className the name of the class that failed to generate
     * @param cause the underlying exception
     * @return a new JafarSerializationException instance
     */
    public static JafarSerializationException bytecodeGenerationFailed(String className, Throwable cause) {
        return new JafarSerializationException(
            "Failed to generate bytecode for class",
            cause,
            className
        );
    }

    /**
     * Creates a JafarSerializationException for bytecode generation failure with debug path.
     * 
     * @param className the name of the class that failed to generate
     * @param debugPath the path where debug bytecode is available
     * @param cause the underlying exception
     * @return a new JafarSerializationException instance
     */
    public static JafarSerializationException bytecodeGenerationFailed(String className, Path debugPath, Throwable cause) {
        return new JafarSerializationException(
            String.format("Failed to generate bytecode for class. Debug bytecode available at: %s", debugPath),
            cause,
            className
        );
    }

    /**
     * Creates a JafarSerializationException for class loading failure.
     * 
     * @param className the name of the class that failed to load
     * @param cause the underlying exception
     * @return a new JafarSerializationException instance
     */
    public static JafarSerializationException classLoadingFailed(String className, Throwable cause) {
        return new JafarSerializationException(
            "Failed to load generated handler class",
            cause,
            className
        );
    }

    /**
     * Creates a JafarSerializationException for method handle creation failure.
     * 
     * @param className the name of the class containing the method
     * @param methodName the name of the method that failed to create a handle for
     * @param cause the underlying exception
     * @return a new JafarSerializationException instance
     */
    public static JafarSerializationException methodHandleCreationFailed(String className, String methodName, Throwable cause) {
        return new JafarSerializationException(
            String.format("Failed to create method handle for method '%s'", methodName),
            cause,
            className
        );
    }
} 
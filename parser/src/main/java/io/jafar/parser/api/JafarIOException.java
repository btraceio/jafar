package io.jafar.parser.api;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Exception thrown for I/O related errors during JFR file parsing.
 */
public class JafarIOException extends JafarParseException {
    
    /**
     * Constructs a new JafarIOException with the specified message.
     * 
     * @param message the detail message
     */
    public JafarIOException(String message) {
        super(message, null, "IO");
    }

    /**
     * Constructs a new JafarIOException with the specified message and context.
     * 
     * @param message the detail message
     * @param context the context information
     */
    public JafarIOException(String message, String context) {
        super(message, context, "IO");
    }

    /**
     * Constructs a new JafarIOException with the specified message and cause.
     * 
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public JafarIOException(String message, Throwable cause) {
        super(message, cause, null, "IO");
    }

    /**
     * Constructs a new JafarIOException with the specified message, cause, and context.
     * 
     * @param message the detail message
     * @param cause the cause of the exception
     * @param context the context information
     */
    public JafarIOException(String message, Throwable cause, String context) {
        super(message, cause, context, "IO");
    }

    /**
     * Creates a JafarIOException for a file read error.
     * 
     * @param filePath the path of the file that could not be read
     * @param cause the underlying IOException
     * @return a new JafarIOException instance
     */
    public static JafarIOException fileReadError(Path filePath, IOException cause) {
        return new JafarIOException(
            "Failed to read JFR file",
            cause,
            filePath.toString()
        );
    }

    /**
     * Creates a JafarIOException for an invalid buffer error.
     * 
     * @param cause the underlying exception
     * @return a new JafarIOException instance
     */
    public static JafarIOException invalidBuffer(Throwable cause) {
        return new JafarIOException(
            "Invalid buffer encountered during parsing",
            cause,
            "BUFFER_ERROR"
        );
    }

    /**
     * Creates a JafarIOException for a parsing error.
     * 
     * @param filePath the path of the file being parsed
     * @param cause the underlying exception
     * @return a new JafarIOException instance
     */
    public static JafarIOException parsingError(Path filePath, Throwable cause) {
        return new JafarIOException(
            "Error occurred while parsing JFR recording",
            cause,
            filePath.toString()
        );
    }

    /**
     * Creates a JafarIOException for a closed parser error.
     * 
     * @return a new JafarIOException instance
     */
    public static JafarIOException parserClosed() {
        return new JafarIOException(
            "Parser is closed",
            "PARSER_CLOSED"
        );
    }

    /**
     * Creates a JafarIOException for a chunk parsing error.
     * 
     * @param chunkIndex the index of the chunk that failed to parse
     * @param cause the underlying exception
     * @return a new JafarIOException instance
     */
    public static JafarIOException chunkParsingError(int chunkIndex, Throwable cause) {
        return new JafarIOException(
            "Failed to parse chunk",
            cause,
            "chunk-" + chunkIndex
        );
    }
} 
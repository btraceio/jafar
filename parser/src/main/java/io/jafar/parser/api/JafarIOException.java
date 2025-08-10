package io.jafar.parser.api;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Exception thrown for I/O related errors during JFR file parsing.
 */
public class JafarIOException extends JafarParseException {
    
    public JafarIOException(String message) {
        super(message, null, "IO");
    }

    public JafarIOException(String message, String context) {
        super(message, context, "IO");
    }

    public JafarIOException(String message, Throwable cause) {
        super(message, cause, null, "IO");
    }

    public JafarIOException(String message, Throwable cause, String context) {
        super(message, cause, context, "IO");
    }

    public static JafarIOException fileReadError(Path filePath, IOException cause) {
        return new JafarIOException(
            "Failed to read JFR file",
            cause,
            filePath.toString()
        );
    }

    public static JafarIOException invalidBuffer(Throwable cause) {
        return new JafarIOException(
            "Invalid buffer encountered during parsing",
            cause,
            "BUFFER_ERROR"
        );
    }

    public static JafarIOException parsingError(Path filePath, Throwable cause) {
        return new JafarIOException(
            "Error occurred while parsing JFR recording",
            cause,
            filePath.toString()
        );
    }

    public static JafarIOException parserClosed() {
        return new JafarIOException(
            "Parser is closed",
            "PARSER_CLOSED"
        );
    }

    public static JafarIOException chunkParsingError(int chunkIndex, Throwable cause) {
        return new JafarIOException(
            "Failed to parse chunk",
            cause,
            "chunk-" + chunkIndex
        );
    }
} 
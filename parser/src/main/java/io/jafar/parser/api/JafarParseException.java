package io.jafar.parser.api;

/**
 * Base exception for all Jafar parsing related errors.
 * Provides contextual information to help with debugging.
 */
public class JafarParseException extends Exception {
    private final String context;
    private final String errorCode;

    public JafarParseException(String message) {
        this(message, null, null);
    }

    public JafarParseException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public JafarParseException(String message, String context, String errorCode) {
        this(message, null, context, errorCode);
    }

    public JafarParseException(String message, Throwable cause, String context, String errorCode) {
        super(formatMessage(message, context, errorCode), cause);
        this.context = context;
        this.errorCode = errorCode;
    }

    private static String formatMessage(String message, String context, String errorCode) {
        StringBuilder sb = new StringBuilder(message);
        if (context != null) {
            sb.append(" [Context: ").append(context).append("]");
        }
        if (errorCode != null) {
            sb.append(" [Error Code: ").append(errorCode).append("]");
        }
        return sb.toString();
    }

    public String getContext() {
        return context;
    }

    public String getErrorCode() {
        return errorCode;
    }
} 
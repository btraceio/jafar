package io.jafar.parser.api;
import io.jafar.parser.impl.TypedJafarParserImpl;
import io.jafar.parser.impl.ParsingContextImpl;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Strongly-typed JFR parser.
 * <p>
 * Events are mapped to user-defined interfaces annotated with {@link JfrType} and
 * methods representing event fields, optionally annotated with {@link JfrField}
 * when names differ. Registered {@link JFRHandler handlers} receive fully
 * deserialized event instances.
 * </p>
 *
 * <p>
 * Handlers are invoked synchronously on the parser thread. Keep handler logic
 * fast, or offload work to another thread.
 * </p>
 */
public interface TypedJafarParser extends JafarParser, AutoCloseable {
    /**
     * Start a new parsing session.
     * @param path the recording path
     * @return the parser instance
     */
    static TypedJafarParser open(String path) {
        return open(path, ParsingContextImpl.EMPTY);
    }

    /**
     * Start a new parsing session.
     * @param path the recording path
     * @return the parser instance
     */
    static TypedJafarParser open(Path path) {
        return open(path, ParsingContextImpl.EMPTY);
    }

    /**
     * Start a new parsing session with a shared context.
     * @param path the recording path
     * @param context the shared context. When recordings are opened with the same context,
     *                computationally expensive resources (e.g., generated handler bytecode)
     *                may be reused across sessions
     * @return the parser instance
     */
    static TypedJafarParser open(String path, ParsingContext context) {
        return open(Paths.get(path), context);
    }

    /**
     * Start a new parsing session with a shared context.
     * @param path the recording path
     * @param context the shared context. When recordings are opened with the same context,
     *                computationally expensive resources (e.g., generated handler bytecode)
     *                may be reused across sessions
     * @return the parser instance
     * @throws IllegalArgumentException if {@code context} is not a supported implementation
     */
    static TypedJafarParser open(Path path, ParsingContext context) {
        if (!(context instanceof ParsingContextImpl)) {
            throw new IllegalArgumentException("parsingContext must be an instance of ParsingContextImpl");
        }
        return new TypedJafarParserImpl(path, (ParsingContextImpl) context);
    }

    /**
     * Registers a handler for events of the given JFR interface type.
     * <p>The {@code clz} must be an interface annotated with {@link JfrType}. Methods correspond
     * to event fields; when names differ, annotate with {@link JfrField}.</p>
     * <p>Handlers are invoked synchronously on the parser thread. If a handler throws, parsing
     * stops and the exception propagates to {@link #run()}.</p>
     *
     * @param clz JFR event interface to handle
     * @param handler callback receiving deserialized events and parse {@link Control}
     * @return a registration that can be destroyed to stop receiving events
     * @throws IllegalArgumentException if {@code clz} is invalid
     */
    <T> HandlerRegistration<T> handle(Class<T> clz, JFRHandler<T> handler);

    /**
     * Parses the recording and synchronously invokes registered handlers.
     * @throws IOException if reading fails or a parsing error occurs
     */
    void run() throws IOException;
}

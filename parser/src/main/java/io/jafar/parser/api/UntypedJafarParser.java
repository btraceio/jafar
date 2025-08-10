package io.jafar.parser.api;

import io.jafar.parser.impl.ParsingContextImpl;
import io.jafar.parser.impl.UntypedJafarParserImpl;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Untyped JFR parser.
 * <p>
 * Events are exposed as {@code Map<String, Object>} with keys representing field names and
 * values being boxed primitives, {@link String}, nested maps, or arrays.
 * </p>
 * <p>
 * Handlers are invoked synchronously on the parser thread.
 * </p>
 */
public interface UntypedJafarParser extends JafarParser, AutoCloseable {
    /**
     * Start a new parsing session.
     * @param path the recording path
     * @return the parser instance
     */
    static UntypedJafarParser open(String path) {
        return open(path, ParsingContextImpl.EMPTY);
    }

    /**
     * Start a new parsing session.
     * @param path the recording path
     * @return the parser instance
     */
    static UntypedJafarParser open(Path path) {
        return open(path, ParsingContextImpl.EMPTY);
    }

    /**
     * Start a new parsing session with a shared context.
     * @param path the recording path
     * @param context the shared context. When recordings are opened with the same context,
     *                computationally expensive resources may be reused across sessions
     * @return the parser instance
     */
    static UntypedJafarParser open(String path, ParsingContext context) {
        return open(Paths.get(path), context);
    }

    /**
     * Start a new parsing session with a shared context.
     * @param path the recording path
     * @param context the shared context. When recordings are opened with the same context,
     *                computationally expensive resources may be reused across sessions
     * @return the parser instance
     * @throws IllegalArgumentException if {@code context} is not a supported implementation
     */
    static UntypedJafarParser open(Path path, ParsingContext context) {
        if (!(context instanceof ParsingContextImpl)) {
            throw new IllegalArgumentException("parsingContext must be an instance of ParsingContextImpl");
        }
        return new UntypedJafarParserImpl(path, (ParsingContextImpl) context);
    }

    /**
     * Registers a handler receiving untyped event maps.
     * <p>Keys are field names; values are boxed primitives, {@link String}, nested maps, or arrays.</p>
     * <p>Exceptions thrown from the handler stop parsing and propagate to {@link #run()}.</p>
     *
     * @param handler consumer of event maps
     * @return a registration that can be destroyed to stop receiving events
     */
    HandlerRegistration<?> handle(Consumer<Map<String, Object>> handler);
}

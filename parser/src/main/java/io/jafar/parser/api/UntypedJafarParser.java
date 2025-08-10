package io.jafar.parser.api;

import io.jafar.parser.impl.ParsingContextImpl;
import io.jafar.parser.impl.UntypedJafarParserImpl;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Consumer;

public interface UntypedJafarParser extends JafarParser, AutoCloseable {
    /**
     * Start a new parsing session
     * @param path the recording path
     * @return the parser instance
     */
    static UntypedJafarParser open(String path) {
        return open(path, ParsingContextImpl.EMPTY);
    }

    /**
     * Start a new parsing session
     * @param path the recording path
     * @return the parser instance
     */
    static UntypedJafarParser open(Path path) {
        return open(path, ParsingContextImpl.EMPTY);
    }

    /**
     * Start a new parsing session with shared context,
     * @param path the recording path
     * @param context the shared context
     *                If another recording is opened with the same context some resources,
     *                like generated handler bytecode, might be reused
     * @return the parser instance
     */
    static UntypedJafarParser open(String path, ParsingContext context) {
        return open(Paths.get(path), context);
    }

    /**
     * Start a new parsing session with shared context,
     * @param path the recording path
     * @param context the shared context
     *                If another recording is opened with the same context some resources,
     *                like generated handler bytecode, might be reused
     * @return the parser instance
     */
    static UntypedJafarParser open(Path path, ParsingContext context) {
        if (!(context instanceof ParsingContextImpl)) {
            throw new IllegalArgumentException("parsingContext must be an instance of ParsingContextImpl");
        }
        return new UntypedJafarParserImpl(path, (ParsingContextImpl) context);
    }

    HandlerRegistration<?> handle(Consumer<Map<String, Object>> handler);
}

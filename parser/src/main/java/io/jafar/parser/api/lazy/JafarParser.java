package io.jafar.parser.api.lazy;
import io.jafar.parser.impl.lazy.JafarParserImpl;
import io.jafar.parser.impl.lazy.ParsingContextImpl;

import java.io.IOException;
import java.nio.file.Paths;

public interface JafarParser extends AutoCloseable {
    /**
     * Start a new parsing session
     * @param path the recording path
     * @return the parser instance
     */
    static JafarParser open(String path) {
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
    static JafarParser open(String path, ParsingContext context) {
        if (!(context instanceof ParsingContextImpl)) {
            throw new IllegalArgumentException("parsingContext must be an instance of ParsingContextImpl");
        }
        return new JafarParserImpl(Paths.get(path), (ParsingContextImpl) context);
    }

    <T> HandlerRegistration<T> handle(Class<T> clz, JFRHandler<T> handler);

    void run() throws IOException;
}

package io.jafar.parser.api;

import io.jafar.parser.impl.ParsingContextImpl;

import java.nio.file.Path;

/**
 * Cross-recording context.
 * Implementation specific, but allows sharing computationally intensive resources
 * between parsing sessions,
 */
public interface ParsingContext {
    /**
     * Create a new instance of ParsingContext.
     *
     * @return a new instance of ParsingContext
     */
    static ParsingContext create() {
        return new ParsingContextImpl();
    }

    /**
     * Creates a new instance of a TypedJafarParser for the given path.
     *
     * @param path the path to the recording
     * @return a new TypedJafarParser instance
     */
    TypedJafarParser newTypedParser(Path path);

    /**
     * Creates a new instance of an UnTypedJafarParser for the given path.
     *
     * @param path the path to the recording
     * @return a new UnTypedJafarParser instance
     */
    UntypedJafarParser newUntypedParser(Path path);

    /**
     * Returns the uptime of the parsing context in nanoseconds.
     *
     * @return the uptime in nanoseconds
     */
    long uptime();
}


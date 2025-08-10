package io.jafar.parser.api;

import io.jafar.parser.internal_api.ChunkParserListener;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Common entry point for JFR parsing sessions.
 * <p>
 * Use {@link #newTypedParser(Path)} for strongly-typed parsing backed by
 * {@link TypedJafarParser}, or {@link #newUntypedParser(Path)} for a lightweight
 * map-based parser backed by {@link UntypedJafarParser}. Implementations are
 * {@link AutoCloseable}; prefer try-with-resources to ensure resources are
 * released.
 * </p>
 *
 * <p>
 * Calling {@link #run()} reads the recording and synchronously invokes all
 * registered handlers. If a handler throws, parsing stops and the exception is
 * propagated to the caller of {@code run()}.
 * </p>
 */
public interface JafarParser {
    static TypedJafarParser newTypedParser(Path path) {
        return TypedJafarParser.open(path.toString());
    }

    static TypedJafarParser newTypedParser(Path path, ParsingContext context) {
        return TypedJafarParser.open(path, context);
    }

    static UntypedJafarParser newUntypedParser(Path path) {
        return UntypedJafarParser.open(path.toString());
    }

    static UntypedJafarParser newUntypedParser(Path path, ParsingContext context) {
        return UntypedJafarParser.open(path, context);
    }

    /**
     * Parses the recording and synchronously invokes registered handlers.
     *
     * @throws IOException if an I/O error occurs while reading the recording or
     *                     if a parsing error occurs and is wrapped as an I/O failure
     */
    void run() throws IOException;

    <T extends JafarParser> T withParserListener(ChunkParserListener listener);
}

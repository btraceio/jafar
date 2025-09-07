package io.jafar.parser.api;

import io.jafar.parser.internal_api.ChunkParserListener;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Common entry point for JFR parsing sessions.
 *
 * <p>Use {@link #newTypedParser(Path)} for strongly-typed parsing backed by {@link
 * TypedJafarParser}, or {@link #newUntypedParser(Path)} for a lightweight map-based parser backed
 * by {@link UntypedJafarParser}. Implementations are {@link AutoCloseable}; prefer
 * try-with-resources to ensure resources are released.
 *
 * <p>Calling {@link #run()} reads the recording and synchronously invokes all registered handlers.
 * If a handler throws, parsing stops and the exception is propagated to the caller of {@code
 * run()}.
 */
public interface JafarParser {
  /**
   * Creates a new typed parser for the specified file path.
   *
   * @param path the path to the JFR recording file
   * @return a new TypedJafarParser instance
   */
  static TypedJafarParser newTypedParser(Path path) {
    return TypedJafarParser.open(path.toString());
  }

  /**
   * Creates a new typed parser for the specified file path with a shared context.
   *
   * @param path the path to the JFR recording file
   * @param context the shared parsing context
   * @return a new TypedJafarParser instance
   */
  static TypedJafarParser newTypedParser(Path path, ParsingContext context) {
    return TypedJafarParser.open(path, context);
  }

  /**
   * Creates a new untyped parser for the specified file path.
   *
   * @param path the path to the JFR recording file
   * @return a new UntypedJafarParser instance
   */
  static UntypedJafarParser newUntypedParser(Path path) {
    return UntypedJafarParser.open(path.toString());
  }

  /**
   * Creates a new untyped parser for the specified file path with a shared context.
   *
   * @param path the path to the JFR recording file
   * @param context the shared parsing context
   * @return a new UntypedJafarParser instance
   */
  static UntypedJafarParser newUntypedParser(Path path, ParsingContext context) {
    return UntypedJafarParser.open(path, context);
  }

  /**
   * Parses the recording and synchronously invokes registered handlers.
   *
   * @throws IOException if an I/O error occurs while reading the recording or if a parsing error
   *     occurs and is wrapped as an I/O failure
   */
  void run() throws IOException;

  /**
   * Configures a parser listener for chunk-level parsing events.
   *
   * @param <T> the type of parser being configured
   * @param listener the chunk parser listener to use
   * @return this parser instance for method chaining
   */
  <T extends JafarParser> T withParserListener(ChunkParserListener listener);
}

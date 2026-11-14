package io.jafar.parser.api;

import io.jafar.parser.impl.ParsingContextImpl;
import io.jafar.parser.impl.TypedJafarParserImpl;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Strongly-typed JFR parser.
 *
 * <p>Events are mapped to user-defined interfaces annotated with {@link JfrType} and methods
 * representing event fields, optionally annotated with {@link JfrField} when names differ.
 * Registered {@link JFRHandler handlers} receive fully deserialized event instances.
 *
 * <p><b>Threading.</b> A recording's chunks are parsed in parallel, one task per chunk on a pool of
 * {@code max(availableProcessors - 2, 1)} threads, so handlers are called concurrently from several
 * parser-owned threads and must be thread-safe. Events are delivered in file order within one
 * chunk; there is no order between chunks. {@link Control.ChunkInfo#chunkId()} is unique per parsed
 * chunk within the JVM, so it can key per-chunk state; it is not the chunk's position in the file.
 * Every handler call has completed when {@code run()} returns. For strictly sequential,
 * file-ordered delivery create the parser from {@link ParsingContext#withParallelism(int)
 * ParsingContext.withParallelism(1)}.
 */
public interface TypedJafarParser extends JafarParser, AutoCloseable {
  /**
   * Start a new parsing session.
   *
   * @param path the recording path
   * @return the parser instance
   */
  static TypedJafarParser open(String path) {
    return open(path, new ParsingContextImpl());
  }

  /**
   * Start a new parsing session.
   *
   * @param path the recording path
   * @return the parser instance
   */
  static TypedJafarParser open(Path path) {
    return open(path, new ParsingContextImpl());
  }

  /**
   * Start a new parsing session with a shared context.
   *
   * @param path the recording path
   * @param context the shared context. When recordings are opened with the same context,
   *     computationally expensive resources (e.g., generated handler bytecode) may be reused across
   *     sessions
   * @return the parser instance
   */
  static TypedJafarParser open(String path, ParsingContext context) {
    return open(Paths.get(path), context);
  }

  /**
   * Start a new parsing session with a shared context.
   *
   * @param path the recording path
   * @param context the shared context. When recordings are opened with the same context,
   *     computationally expensive resources (e.g., generated handler bytecode) may be reused across
   *     sessions
   * @return the parser instance
   * @throws IllegalArgumentException if {@code context} is not a supported implementation
   */
  static TypedJafarParser open(Path path, ParsingContext context) {
    if (!(context instanceof ParsingContextImpl)) {
      throw new IllegalArgumentException(
          "parsingContext must be an instance of ParsingContextImpl");
    }
    return new TypedJafarParserImpl(path, (ParsingContextImpl) context);
  }

  /**
   * Registers a handler for events of the given JFR interface type.
   *
   * <p>The {@code clz} must be an interface annotated with {@link JfrType}. Methods correspond to
   * event fields; when names differ, annotate with {@link JfrField}.
   *
   * <p>Handlers are called from the chunk parsing threads, concurrently unless the parser was
   * created with a parallelism of one, and must be thread-safe (see the class description). If a
   * handler throws, parsing stops and the exception propagates to {@link #run()}. Register handlers
   * before calling {@code run()}.
   *
   * @param <T> the type of JFR events this handler processes
   * @param clz JFR event interface to handle
   * @param handler callback receiving deserialized events and parse {@link Control}
   * @return a registration that can be closed to stop receiving events
   * @throws IllegalArgumentException if {@code clz} is invalid
   */
  <T> HandlerRegistration<T> handle(Class<T> clz, JFRHandler<T> handler);

  /**
   * Parses the recording and invokes registered handlers on the chunk parsing threads; returns when
   * every chunk has been parsed and every handler call has completed.
   *
   * @throws IOException if reading fails or a parsing error occurs
   */
  void run() throws IOException;

  /**
   * Registers a build-time generated handler factory for a specific event type.
   *
   * <p><b>Note:</b> Manual registration is <b>optional</b>. The parser automatically discovers and
   * registers factories via {@link java.util.ServiceLoader}. Only use this method if you need to
   * register factories programmatically or override auto-discovered factories.
   *
   * <p>Build-time generated handlers avoid runtime bytecode generation overhead. The factory
   * provides thread-local cached handler instances, reducing allocations during parsing.
   *
   * <p>When a factory is registered (manually or via ServiceLoader), the parser will:
   *
   * <ol>
   *   <li>Call {@link HandlerFactory#bind} when recording metadata becomes available
   *   <li>Use {@link HandlerFactory#get} to obtain cached handler instances for events
   * </ol>
   *
   * @param <T> the handler interface type
   * @param factory the build-time generated handler factory
   * @see HandlerFactory
   * @see java.util.ServiceLoader
   */
  <T> void registerFactory(HandlerFactory<T> factory);
}

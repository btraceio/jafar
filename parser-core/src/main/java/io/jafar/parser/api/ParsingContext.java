package io.jafar.parser.api;

import io.jafar.parser.impl.ParsingContextImpl;
import java.nio.file.Path;

/**
 * Cross-recording context. Implementation specific, but allows sharing computationally intensive
 * resources between parsing sessions.
 *
 * <p>Reuse a single {@code ParsingContext} across multiple parser instances to enable caching and
 * reduce initialization overhead.
 *
 * <p>The parser automatically detects metadata compatibility through fingerprinting and safely
 * reuses generated handler classes across recordings with identical metadata structures. This
 * eliminates metaspace bloat and significantly improves parsing performance through JIT compilation
 * benefits when handlers are reused across multiple parsing sessions.
 *
 * <p>Handler classes accumulate JIT optimizations with each use, typically achieving 2-3x
 * throughput improvement after warm-up when parsing recordings with compatible metadata.
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
   * Creates a new instance of a {@link TypedJafarParser} for the given path.
   *
   * @param path the path to the recording
   * @return a new {@link TypedJafarParser} instance
   */
  TypedJafarParser newTypedParser(Path path);

  /**
   * Creates a new instance of an {@link UntypedJafarParser} for the given path.
   *
   * <p>Uses {@link UntypedStrategy#SPARSE_ACCESS} by default.
   *
   * @param path the path to the recording
   * @return a new {@link UntypedJafarParser} instance
   */
  UntypedJafarParser newUntypedParser(Path path);

  /**
   * Creates a new instance of an {@link UntypedJafarParser} for the given path with a specific
   * optimization strategy.
   *
   * <p>Different access patterns benefit from different deserialization strategies:
   *
   * <ul>
   *   <li>{@link UntypedStrategy#SPARSE_ACCESS} - Optimized for accessing 1-5 fields per event
   *       (filtering, sampling)
   *   <li>{@link UntypedStrategy#FULL_ITERATION} - Optimized for iterating all fields (bulk export,
   *       conversion)
   *   <li>{@link UntypedStrategy#AUTO} - Auto-detect access pattern (future feature)
   * </ul>
   *
   * @param path the path to the recording
   * @param strategy the optimization strategy
   * @return a new {@link UntypedJafarParser} instance
   */
  UntypedJafarParser newUntypedParser(Path path, UntypedStrategy strategy);

  /**
   * Returns a context that shares this context's cached resources but whose parsers read chunks on
   * {@code parallelism} threads instead of the default {@code max(availableProcessors - 2, 1)}.
   *
   * <p>With {@code withParallelism(1)} a parser delivers events strictly one at a time, in the
   * order they appear in the file, chunk after chunk, and every handler call is complete before
   * {@code run()} returns. Handlers then need no synchronization of their own. Handlers are still
   * called on a parser-owned thread, not on the thread that called {@code run()}. With any larger
   * value, chunks are parsed concurrently: handlers must be thread-safe, events keep file order
   * only within one chunk, and there is no order between chunks.
   *
   * <p>This context is not changed; parsers already created from it keep their setting.
   *
   * <p>The default implementation throws {@link UnsupportedOperationException}, so that existing
   * implementations of this interface keep compiling; contexts created by {@link #create()} support
   * it.
   *
   * @param parallelism the number of chunk parsing threads, at least 1
   * @return a context sharing this context's resources, with the given parallelism
   * @throws IllegalArgumentException if {@code parallelism} is less than 1
   * @throws UnsupportedOperationException if this implementation does not support it
   */
  default ParsingContext withParallelism(int parallelism) {
    throw new UnsupportedOperationException(
        getClass().getName() + " does not support withParallelism");
  }

  /**
   * The number of chunk parsing threads requested with {@link #withParallelism(int)}.
   *
   * <p>Code that reads a recording with this context's settings but without one of its parsers uses
   * this to size its own chunk pool.
   *
   * @return the requested number of chunk parsing threads, or {@code 0} when none was requested and
   *     the default, {@code max(availableProcessors - 2, 1)}, applies
   */
  default int parallelism() {
    return 0;
  }

  /**
   * Returns the uptime of the parsing context in nanoseconds.
   *
   * <p>Measures cumulative time since creation, across all parser sessions using this context.
   *
   * @return the uptime in nanoseconds
   */
  long uptime();
}

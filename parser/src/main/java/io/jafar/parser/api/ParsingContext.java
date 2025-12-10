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
   * Returns the uptime of the parsing context in nanoseconds.
   *
   * <p>Measures cumulative time since creation, across all parser sessions using this context.
   *
   * @return the uptime in nanoseconds
   */
  long uptime();
}

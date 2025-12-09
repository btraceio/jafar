package io.jafar.parser.api;

/**
 * Strategy hint for untyped parser optimization.
 *
 * <p>The untyped parser uses bytecode generation to optimize performance. Different access patterns
 * benefit from different deserialization strategies:
 *
 * <ul>
 *   <li>{@link #SPARSE_ACCESS} - Optimized for accessing only a few fields per event (filtering,
 *       sampling)
 *   <li>{@link #FULL_ITERATION} - Optimized for iterating all fields (bulk export, conversion)
 *   <li>{@link #AUTO} - Auto-detect access pattern (future feature)
 * </ul>
 *
 * @see ParsingContext#setUntypedStrategy(UntypedStrategy)
 */
public enum UntypedStrategy {
  /**
   * Optimize for sparse field access (1-5 fields per event).
   *
   * <p>Uses hybrid eager/lazy deserialization:
   *
   * <ul>
   *   <li>Simple events (≤10 fields): Eager HashMap
   *   <li>Complex events (>10 fields): Lazy deserialization
   * </ul>
   *
   * <p><b>Best for:</b> Filtering, sampling, metadata queries
   *
   * <p><b>Performance:</b>
   *
   * <ul>
   *   <li>Sparse access: +5% vs baseline
   *   <li>Full iteration: -6% vs baseline
   * </ul>
   */
  SPARSE_ACCESS,

  /**
   * Optimize for full field iteration (all fields accessed).
   *
   * <p>Uses eager HashMap deserialization for all events, regardless of complexity.
   *
   * <p><b>Best for:</b> Bulk export, data conversion, analytics
   *
   * <p><b>Performance:</b>
   *
   * <ul>
   *   <li>Sparse access: Same as baseline
   *   <li>Full iteration: Matches baseline performance
   * </ul>
   */
  FULL_ITERATION,

  /**
   * Auto-detect access pattern based on runtime behavior.
   *
   * <p><b>Current behavior:</b> Falls back to {@link #SPARSE_ACCESS}.
   *
   * <p><b>Future enhancement:</b> Will adaptively switch between strategies based on observed field
   * access patterns after warm-up period.
   */
  AUTO
}

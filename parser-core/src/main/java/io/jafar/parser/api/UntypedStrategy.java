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
 * <h2>How to Choose</h2>
 *
 * <p><b>Use {@link #SPARSE_ACCESS} (default) if:</b>
 *
 * <ul>
 *   <li>You access only a few specific fields per event
 *   <li>You're filtering events based on field values
 *   <li>You're sampling or extracting metadata
 *   <li>You call {@code event.get("fieldName")} for specific fields
 * </ul>
 *
 * <p><b>Use {@link #FULL_ITERATION} if:</b>
 *
 * <ul>
 *   <li>You iterate all fields with {@code event.entrySet()}
 *   <li>You're doing bulk export to databases (DuckDB, PostgreSQL, etc.)
 *   <li>You're converting JFR to other formats (JSON, CSV, Parquet)
 *   <li>You're performing analytics that examine all event fields
 * </ul>
 *
 * <p><b>Example - Sparse Access:</b>
 *
 * <pre>{@code
 * // Default strategy - optimized for sparse field access
 * try (UntypedJafarParser parser = ctx.newUntypedParser(file)) {
 *   parser.handle((type, event, ctl) -> {
 *     long time = (Long) event.get("startTime");
 *     if (time > threshold) {
 *       String thread = (String) event.get("threadName");
 *       logger.info("Event at {} on {}", time, thread);
 *     }
 *   });
 * }
 * }</pre>
 *
 * <p><b>Example - Full Iteration:</b>
 *
 * <pre>{@code
 * // FULL_ITERATION strategy - optimized for iterating all fields
 * try (UntypedJafarParser parser =
 *     ctx.newUntypedParser(file, UntypedStrategy.FULL_ITERATION)) {
 *   parser.handle((type, event, ctl) -> {
 *     for (Map.Entry<String, Object> entry : event.entrySet()) {
 *       duckdb.insert(entry.getKey(), entry.getValue());
 *     }
 *   });
 * }
 * }</pre>
 *
 * @see ParsingContext#newUntypedParser(Path, UntypedStrategy)
 * @see UntypedJafarParser
 */
public enum UntypedStrategy {
  /**
   * Optimize for sparse field access (1-5 fields per event).
   *
   * <p>Uses hybrid eager/lazy deserialization:
   *
   * <ul>
   *   <li>Simple events (≤10 fields): Eager HashMap
   *   <li>Complex events (&gt;10 fields): Lazy deserialization
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

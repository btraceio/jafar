package io.jafar.parser.api;

import io.jafar.parser.impl.ParsingContextImpl;
import io.jafar.parser.impl.UntypedJafarParserImpl;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Untyped JFR parser with optimization strategies for different access patterns.
 *
 * <p>Events are exposed as {@code Map<String, Object>} with keys representing field names and
 * values being boxed primitives, {@link String}, inline complex values as {@code Map<String,
 * Object>}, or wrappers such as {@link ArrayType} (arrays) and {@link ComplexType} (complex values,
 * e.g., constant-pool backed references).
 *
 * <h2>Performance Tuning</h2>
 *
 * <p>The parser supports optimization strategies via {@link UntypedStrategy} to tune performance
 * for your access pattern:
 *
 * <h3>Sparse Field Access (Default)</h3>
 *
 * <p>Best for filtering, sampling, or metadata queries where you access only a few fields per
 * event:
 *
 * <pre>{@code
 * ParsingContext ctx = ParsingContext.create();
 * // Uses SPARSE_ACCESS strategy by default
 *
 * try (UntypedJafarParser parser = ctx.newUntypedParser(file)) {
 *   parser.handle((type, event, ctl) -> {
 *     // Access only specific fields efficiently
 *     long startTime = (Long) event.get("startTime");
 *     String threadName = (String) event.get("threadName");
 *
 *     if (startTime > threshold) {
 *       System.out.println(threadName);
 *     }
 *   });
 *   parser.run();
 * }
 * }</pre>
 *
 * <h3>Full Field Iteration</h3>
 *
 * <p>Best for bulk export, data conversion, or analytics where you iterate all fields:
 *
 * <pre>{@code
 * ParsingContext ctx = ParsingContext.create();
 *
 * // Use FULL_ITERATION for optimal performance when iterating all fields
 * try (UntypedJafarParser parser =
 *     ctx.newUntypedParser(file, UntypedStrategy.FULL_ITERATION)) {
 *   parser.handle((type, event, ctl) -> {
 *     // Efficiently iterate all fields
 *     for (Map.Entry<String, Object> entry : event.entrySet()) {
 *       exportToDuckDB(entry.getKey(), entry.getValue());
 *     }
 *   });
 *   parser.run();
 * }
 * }</pre>
 *
 * <h2>Working with Complex Types</h2>
 *
 * <p>Example of consuming wrappers for nested structures:
 *
 * <pre>{@code
 * parser.handle((type, value, ctl) -> {
 *   Map<String, Object> thread = Values.as(value, Map.class, "eventThread").orElse(null);
 *   if (thread != null) {
 *     Object id = thread.get("javaThreadId");
 *   }
 *
 *   Object framesVal = Values.get(value, "stackTrace", "frames");
 *   if (framesVal instanceof ArrayType at) {
 *     Object arr = at.getArray();
 *     if (arr instanceof Object[] objs) {
 *       for (Object el : objs) {
 *         if (el instanceof ComplexType cpx) {
 *           Map<String, Object> m = cpx.getValue();
 *         } else if (el instanceof Map) {
 *           Map<String, Object> m = (Map<String, Object>) el; // inline complex element
 *         }
 *       }
 *     }
 *   }
 * });
 * }</pre>
 *
 * <p>Handlers are invoked synchronously on the parser thread.
 *
 * @see UntypedStrategy
 * @see ParsingContext#newUntypedParser(Path, UntypedStrategy)
 */
public interface UntypedJafarParser extends JafarParser, AutoCloseable {
  /**
   * Functional interface for handling untyped JFR events.
   *
   * <p>This interface provides a callback mechanism for processing untyped JFR events as maps of
   * field names to values.
   */
  @FunctionalInterface
  interface EventHandler {
    /**
     * Handles an untyped JFR event.
     *
     * @param type the metadata class type of the event
     * @param value the event data as a map of field names to values
     * @param ctl parser control object for flow control and metadata access
     */
    void handle(MetadataClass type, Map<String, Object> value, Control ctl);
  }

  /**
   * Start a new parsing session.
   *
   * @param path the recording path
   * @return the parser instance
   */
  static UntypedJafarParser open(String path) {
    return open(path, ParsingContextImpl.EMPTY);
  }

  /**
   * Start a new parsing session.
   *
   * @param path the recording path
   * @return the parser instance
   */
  static UntypedJafarParser open(Path path) {
    return open(path, ParsingContextImpl.EMPTY);
  }

  /**
   * Start a new parsing session with a shared context.
   *
   * @param path the recording path
   * @param context the shared context. When recordings are opened with the same context,
   *     computationally expensive resources may be reused across sessions
   * @return the parser instance
   */
  static UntypedJafarParser open(String path, ParsingContext context) {
    return open(Paths.get(path), context);
  }

  /**
   * Start a new parsing session with a shared context.
   *
   * @param path the recording path
   * @param context the shared context. When recordings are opened with the same context,
   *     computationally expensive resources may be reused across sessions
   * @return the parser instance
   * @throws IllegalArgumentException if {@code context} is not a supported implementation
   */
  static UntypedJafarParser open(Path path, ParsingContext context) {
    return open(path, context, UntypedStrategy.SPARSE_ACCESS);
  }

  /**
   * Start a new parsing session with a shared context and optimization strategy.
   *
   * @param path the recording path
   * @param context the shared context. When recordings are opened with the same context,
   *     computationally expensive resources may be reused across sessions
   * @param strategy the optimization strategy for event deserialization
   * @return the parser instance
   * @throws IllegalArgumentException if {@code context} is not a supported implementation
   */
  static UntypedJafarParser open(Path path, ParsingContext context, UntypedStrategy strategy) {
    if (!(context instanceof ParsingContextImpl)) {
      throw new IllegalArgumentException(
          "parsingContext must be an instance of ParsingContextImpl");
    }
    return new UntypedJafarParserImpl(path, (ParsingContextImpl) context, strategy);
  }

  /**
   * Registers a handler receiving untyped event maps.
   *
   * <p>Keys are field names; values are boxed primitives, {@link String}, inline complex values as
   * {@code Map<String, Object>}, or wrappers such as {@link ArrayType} and {@link ComplexType}.
   *
   * <p>Exceptions thrown from the handler stop parsing and propagate to {@link #run()}.
   *
   * @param handler consumer of event maps
   * @return a registration that can be destroyed to stop receiving events
   */
  HandlerRegistration<?> handle(EventHandler handler);
}

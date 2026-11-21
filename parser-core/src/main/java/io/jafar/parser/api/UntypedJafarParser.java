package io.jafar.parser.api;

import io.jafar.parser.TimeRange;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.impl.ParsingContextImpl;
import io.jafar.parser.impl.UntypedJafarParserImpl;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.SortedMap;

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
 *   // Values.get returns the backing Java array, never the ArrayType wrapper
 *   Object frames = Values.get(value, "stackTrace", "frames");
 *   if (frames instanceof Object[] objs) {
 *     for (Object el : objs) {
 *       if (el instanceof ComplexType cpx) {
 *         Map<String, Object> m = cpx.getValue(); // constant-pool reference; may be null
 *       } else if (el instanceof Map) {
 *         Map<String, Object> m = (Map<String, Object>) el; // inline complex element
 *       }
 *     }
 *   }
 * });
 * }</pre>
 *
 * <p>{@link Values#resolvedDeep(Map)} returns a copy of an event with every {@link ComplexType} and
 * {@link ArrayType} replaced by its map or array, at any depth, for consumers that prefer one
 * representation over branching.
 *
 * <p><b>Threading.</b> A recording's chunks are parsed in parallel, one task per chunk on a pool of
 * {@code max(availableProcessors - 2, 1)} threads, so handlers are called concurrently from several
 * parser-owned threads and must be thread-safe. Events are delivered in file order within one
 * chunk; there is no order between chunks. {@link Control.ChunkInfo#chunkId()} is unique per parsed
 * chunk within the JVM, so it can key per-chunk state; it is not the chunk's position in the file.
 * Every handler call has completed when {@code run()} returns. For strictly sequential,
 * file-ordered delivery create the parser from {@link ParsingContext#withParallelism(int)
 * ParsingContext.withParallelism(1)}.
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

  /** Receives an event's header without the event being decoded. See {@link #handleHeaders}. */
  @FunctionalInterface
  interface EventHeaderHandler {
    /**
     * The {@code startTime} passed for an event whose type has no {@code startTime} field of type
     * {@code long} (not an array, not a constant-pool reference).
     */
    long NO_START_TIME = Long.MIN_VALUE;

    /**
     * Handles one event's header.
     *
     * @param type the metadata class type of the event
     * @param size the event's size in bytes as the recording stores it, header included
     * @param startTime the event's {@code startTime}, the same value a decoded event carries under
     *     {@code "startTime"}: epoch nanoseconds for a {@code @Timestamp(TICKS)} field, which every
     *     JDK event has, otherwise the stored value; or {@link #NO_START_TIME} when the type has no
     *     {@code long startTime} field
     * @param ctl parser control object for flow control and metadata access
     */
    void onEvent(MetadataClass type, long size, long startTime, Control ctl);
  }

  /**
   * Decides what happens to an event whose payload cannot be decoded. See {@link
   * #onDecodeError(DecodeErrorHandler)}.
   */
  @FunctionalInterface
  interface DecodeErrorHandler {
    /**
     * Called once per undecodable event.
     *
     * @param failure names the event type, chunk and position; its cause is the decoder's reason
     * @return {@code true} to skip the event and continue with the next one; {@code false} to fail
     *     the parse with {@code failure}
     */
    boolean onDecodeError(EventDecodingException failure);
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
   * <p>A handler is called once per event however often it is registered. Registering the same
   * handler again (one that {@code equals} a registered one) returns a further registration of it,
   * and the handler stays registered until every registration has been closed.
   *
   * <p>Safe to call while {@code run()} is in progress, from any thread; the handler receives the
   * events dispatched after it was registered.
   *
   * @param handler consumer of event maps
   * @return a registration that can be closed to stop receiving events
   */
  HandlerRegistration<?> handle(EventHandler handler);

  /**
   * Registers a handler for the event types {@code filter} accepts.
   *
   * <p>The handler receives exactly the events whose type the filter accepts. An event is decoded
   * only if at least one registered handler wants its type, so a parser whose handlers are all
   * filtered skips every other event at the cost of reading its header: on a recording where the
   * wanted type is rare this is several times faster than decoding everything and ignoring most of
   * it in the handler. {@link #handle(EventHandler)} accepts every type, so one unfiltered handler
   * makes the parser decode everything again.
   *
   * <pre>{@code
   * parser.handle(
   *     TypeFilter.named("jdk.ExecutionSample"),
   *     (type, event, ctl) -> samples.increment());
   * }</pre>
   *
   * <p>{@link TypeFilter#named(String...)} covers selection by name; any predicate on the type
   * works.
   *
   * <p>The filter is asked once per chunk and event type, not once per event, on the thread parsing
   * that chunk; it is asked again when the set of registered handlers changes. It must be
   * thread-safe and must answer the same for the same type every time.
   *
   * <p>An event that is not decoded cannot fail to decode: a corrupted event of a type no handler
   * wants is no longer reported to {@link #onDecodeError(DecodeErrorHandler)} and does not fail the
   * parse. It is still reported to a {@link #withParserListener parser listener}'s {@code onEvent}.
   *
   * <p>Otherwise as {@link #handle(EventHandler)}: a handler is called once per event even when it
   * is registered several times, with or without a filter; registering the same filter and handler
   * again (both {@code equals} the earlier ones) counts a further registration; and it is safe to
   * call while {@code run()} is in progress.
   *
   * <p>The default implementation throws {@link UnsupportedOperationException}, so that existing
   * implementations of this interface keep compiling; parsers created by {@link #open} support it.
   *
   * @param filter decides, per event type, whether the handler wants its events
   * @param handler consumer of event maps
   * @return a registration that can be closed to stop receiving events
   * @throws UnsupportedOperationException if this implementation does not support type filters
   */
  default HandlerRegistration<?> handle(TypeFilter filter, EventHandler handler) {
    throw new UnsupportedOperationException(
        getClass().getName() + " does not support type filters");
  }

  /**
   * Registers a handler for the event types {@code filter} accepts, for a caller that only wants
   * events whose {@code startTime} is in {@code startTime}.
   *
   * <p><b>The range is a hint, not a filter.</b> It lets the parser skip a whole chunk that cannot
   * hold an event starting in the range: such a chunk costs its header and its metadata, and
   * neither its constant pools nor its events are read. From the chunks that are read the handler
   * receives every event of the accepted types, in the range or not, so it still has to check
   * {@code startTime} itself. No event that starts in the range is ever withheld.
   *
   * <pre>{@code
   * TimeRange lastMinute = TimeRange.since(endNanos - 60_000_000_000L);
   * parser.handle(
   *     TypeFilter.named("jdk.ExecutionSample"),
   *     lastMinute,
   *     (type, event, ctl) -> {
   *       if ((Long) event.get("startTime") >= lastMinute.fromEpochNanos()) samples.increment();
   *     });
   * }</pre>
   *
   * <p>What can be skipped is narrower than a chunk's header suggests, because an event is written
   * when it is committed and carries whatever start time its writer gave it:
   *
   * <ul>
   *   <li>A chunk that ends before the range begins is skipped. No event starts after the chunk it
   *       was written to has ended.
   *   <li>A chunk that starts after the range ends is <b>not</b> skipped: it can hold events that
   *       started long before it did. So only the range's lower bound saves work, and {@code
   *       TimeRange.until(t)} skips nothing. This is true of events with a duration, which are
   *       written when they end, and of others too: a profiler may emit an event when the chunk is
   *       finished, stamped with a much earlier time.
   *   <li>A chunk that was never finished (its header carries no duration) is always read, and so
   *       is a chunk in which an accepted type's {@code startTime} is not a tick timestamp.
   * </ul>
   *
   * <p>A chunk is skipped only when no registered handler can need it: one handler registered
   * without a range keeps every chunk. The handlers registered when {@link #run()} starts decide
   * whether chunks are examined at all; a range registered later skips nothing. For skipping, the
   * filter is asked about each of a chunk's types once more than {@link #handle(TypeFilter,
   * EventHandler)} asks it.
   *
   * <p>A skipped chunk is reported to a {@link #withParserListener parser listener} with {@code
   * onChunkStart}, {@code onMetadata} and {@code onChunkEnd(skipped = true)}.
   *
   * <p>Otherwise as {@link #handle(TypeFilter, EventHandler)}. The default implementation throws
   * {@link UnsupportedOperationException}; parsers created by {@link #open} support it.
   *
   * @param filter decides, per event type, whether the handler wants its events
   * @param startTime the start times the caller is interested in
   * @param handler consumer of event maps
   * @return a registration that can be closed to stop receiving events
   * @throws UnsupportedOperationException if this implementation does not support time ranges
   */
  default HandlerRegistration<?> handle(
      TypeFilter filter, TimeRange startTime, EventHandler handler) {
    throw new UnsupportedOperationException(
        getClass().getName() + " does not support start-time ranges");
  }

  /**
   * Sets what happens to an event whose payload cannot be decoded (a corrupted or non-conforming
   * recording).
   *
   * <p>Without a decode error handler, or when it returns {@code false}, the parse fails: {@link
   * #run()} throws an {@code IOException} whose cause chain contains the {@link
   * EventDecodingException}. When it returns {@code true} the event is skipped and parsing resumes
   * at the next event, located by the event size the chunk already declares. If it throws, the
   * parse fails with that exception. In no case does an event handler receive the bad event, not
   * even partially.
   *
   * <p>Chunks are parsed in parallel, so the handler can be called concurrently from several
   * threads and must be thread-safe. A corrupted constant pool entry makes every event that
   * references it undecodable, so one defect can produce many calls; count them, and return {@code
   * false} to stop.
   *
   * <p>A skipped event is still reported to a {@link #withParserListener parser listener}'s {@code
   * onEvent}, which works at the chunk level and receives the event's valid position and size; only
   * event handlers never see it.
   *
   * <p>The default implementation throws {@link UnsupportedOperationException}, so that existing
   * implementations of this interface keep compiling; parsers created by {@link #open} support it.
   *
   * @param handler the decode error handler, or {@code null} to restore the default (fail)
   * @return this parser
   * @throws UnsupportedOperationException if this implementation does not support it
   */
  default UntypedJafarParser onDecodeError(DecodeErrorHandler handler) {
    throw new UnsupportedOperationException(
        getClass().getName() + " does not support onDecodeError");
  }

  /**
   * Registers a handler for the headers of the events whose type {@code filter} accepts: their
   * type, size and {@code startTime}, without decoding them.
   *
   * <p>An event whose type only header handlers want is not decoded: its payload is skipped as if
   * no handler wanted it, except for its {@code startTime}. When the type has a {@code long
   * startTime} field, the fields before it are skipped without being decoded and the start time is
   * read; in JDK recordings it is the first field. So a parser whose handlers are all header
   * handlers reads every event's header and nothing more, which is what counting events per type
   * needs. When a value handler (see {@link #handle(EventHandler)}) wants the same event, the event
   * is decoded for it as usual, and the header handler is called first.
   *
   * <pre>{@code
   * Map<String, LongAdder> counts = new ConcurrentHashMap<>();
   * parser.handleHeaders(
   *     type -> true,
   *     (type, size, startTime, ctl) ->
   *         counts.computeIfAbsent(type.getName(), k -> new LongAdder()).increment());
   * parser.run();
   * }</pre>
   *
   * <p>{@link #countEvents(TypeFilter)} does exactly that.
   *
   * <p>The handler is called once per event, on the thread parsing the event's chunk, so it must be
   * thread-safe like any other handler. It is called for an event that would fail to decode, since
   * the event is not decoded for it, and is not called for an event whose type the chunk's metadata
   * does not declare. Exceptions thrown from it stop parsing and propagate to {@link #run()}.
   *
   * <p>A start-time range registered with {@link #handle(TypeFilter, TimeRange, EventHandler)}
   * skips no chunk while a header handler is registered: the header handler asks for every chunk.
   *
   * <p>Otherwise as {@link #handle(TypeFilter, EventHandler)}: the filter is asked once per chunk
   * and type; registering the same filter and handler again counts a further registration, and the
   * handler is still called once per event; it is safe to call while {@code run()} is in progress.
   *
   * <p>The default implementation throws {@link UnsupportedOperationException}, so that existing
   * implementations of this interface keep compiling; parsers created by {@link #open} support it.
   *
   * @param filter which event types the handler wants; must be thread-safe
   * @param handler consumer of event headers
   * @return a registration that can be closed to stop receiving headers
   */
  default HandlerRegistration<?> handleHeaders(TypeFilter filter, EventHeaderHandler handler) {
    throw new UnsupportedOperationException(
        getClass().getName() + " does not support handleHeaders");
  }

  /**
   * Counts the events of every type, without decoding them. See {@link #countEvents(TypeFilter)}.
   *
   * @return the number of events per type name, in name order
   * @throws IOException if the parse fails
   * @throws UnsupportedOperationException if this parser does not implement it (the default
   *     implementation); parsers created by {@link #open} do
   */
  default SortedMap<String, Long> countEvents() throws IOException {
    return countEvents(type -> true);
  }

  /**
   * Counts the events of each type {@code filter} accepts, without decoding them: registers a
   * header handler (see {@link #handleHeaders}), calls {@link #run()} and closes the registration.
   *
   * <p>The default implementation throws {@link UnsupportedOperationException}, so that existing
   * implementations of this interface keep compiling; parsers created by {@link #open} support it.
   *
   * <p>Handlers registered earlier run in the same parse, and a parser listener sees it, as with
   * any call to {@link #run()}. The counting handler is registered on this parser like any other,
   * so a {@code run()} of the same parser already in progress on another thread (or a call from
   * inside one of its handlers) delivers its events to it too, and they are counted; call it when
   * no other parse of this parser is running.
   *
   * @param filter which event types to count
   * @return the number of events per type name, in name order; a type with no event is absent
   * @throws IOException if the parse fails
   * @throws UnsupportedOperationException if this parser does not implement it (the default
   *     implementation); parsers created by {@link #open} do
   */
  default SortedMap<String, Long> countEvents(TypeFilter filter) throws IOException {
    throw new UnsupportedOperationException(getClass().getName() + " does not support countEvents");
  }
}

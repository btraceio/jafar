package io.jafar.parser.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Iterator-style access to JFR events from a recording.
 *
 * <p>This interface provides pull-based event consumption as an alternative to the callback-based
 * {@link UntypedJafarParser.EventHandler} approach, eliminating the "effectively final" constraint
 * on variables used within lambda expressions.
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * try (EventIterator it = EventIterator.open(recordingPath)) {
 *     int counter = 0; // No AtomicInteger needed!
 *     List<String> threadNames = new ArrayList<>(); // Can be mutated freely
 *
 *     while (it.hasNext()) {
 *         JafarRecordedEvent event = it.next();
 *         counter++; // Can mutate plain variables
 *
 *         // Easy early termination
 *         if (counter > 1000) {
 *             break;
 *         }
 *
 *         // Process event data
 *         Object thread = event.value().get("eventThread");
 *         if (thread instanceof Map) {
 *             threadNames.add(((Map<?, ?>) thread).get("name").toString());
 *         }
 *     }
 *
 *     System.out.println("Processed " + counter + " events");
 * }
 * }</pre>
 *
 * <h2>Performance Tuning</h2>
 *
 * <p>The iterator supports optimization strategies via {@link UntypedStrategy}. Use {@link
 * UntypedStrategy#FULL_ITERATION} if you iterate all fields of each event:
 *
 * <pre>{@code
 * ParsingContext ctx = ParsingContext.create();
 *
 * // Use FULL_ITERATION strategy for optimal performance when iterating all fields
 * try (EventIterator it =
 *     EventIterator.open(recordingPath, ctx, 1000, UntypedStrategy.FULL_ITERATION)) {
 *   while (it.hasNext()) {
 *     JafarRecordedEvent event = it.next();
 *
 *     // Efficiently iterate all fields
 *     for (Map.Entry<String, Object> entry : event.value().entrySet()) {
 *       processField(entry.getKey(), entry.getValue());
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p><b>Threading:</b> This iterator runs single-threaded (disables parallel chunk processing). For
 * maximum throughput in production pipelines, use {@link UntypedJafarParser#handle} instead.
 *
 * <p><b>Memory:</b> Events are buffered in a blocking queue. The default buffer size is 1000
 * events. Configure a larger buffer for higher throughput at the cost of memory usage.
 *
 * <p><b>Resource management:</b> This iterator must be closed to release resources. Use
 * try-with-resources as shown in the examples above.
 *
 * @see UntypedStrategy
 * @see UntypedJafarParser
 */
public interface EventIterator extends Iterator<JafarRecordedEvent>, AutoCloseable {

  /**
   * Opens a recording for iterator-based consumption with default settings.
   *
   * @param path the path to the JFR recording file
   * @return a new iterator instance
   * @throws IOException if the recording cannot be opened
   */
  static EventIterator open(Path path) throws IOException {
    return open(path, ParsingContext.create());
  }

  /**
   * Opens a recording with a shared parsing context for metadata reuse.
   *
   * <p>Sharing a parsing context across multiple parsers allows metadata to be reused, reducing
   * memory overhead when processing multiple recordings from the same JVM.
   *
   * @param path the path to the JFR recording file
   * @param context shared parsing context for metadata reuse
   * @return a new iterator instance
   * @throws IOException if the recording cannot be opened
   */
  static EventIterator open(Path path, ParsingContext context) throws IOException {
    return open(path, context, 1000); // default buffer size
  }

  /**
   * Opens a recording with a custom buffer size.
   *
   * <p>The buffer size controls the maximum number of events buffered between the parser and
   * consumer. A larger buffer improves throughput when processing is slower than parsing, but uses
   * more memory. A smaller buffer reduces memory usage but may reduce throughput.
   *
   * @param path the path to the JFR recording file
   * @param context shared parsing context for metadata reuse
   * @param bufferSize maximum events buffered (higher = more memory, better throughput)
   * @return a new iterator instance
   * @throws IOException if the recording cannot be opened
   * @throws IllegalArgumentException if bufferSize is less than 1
   */
  static EventIterator open(Path path, ParsingContext context, int bufferSize) throws IOException {
    return open(path, context, bufferSize, UntypedStrategy.SPARSE_ACCESS);
  }

  /**
   * Opens a recording with a custom buffer size and optimization strategy.
   *
   * <p>The buffer size controls the maximum number of events buffered between the parser and
   * consumer. A larger buffer improves throughput when processing is slower than parsing, but uses
   * more memory. A smaller buffer reduces memory usage but may reduce throughput.
   *
   * <p>The strategy controls deserialization optimization. Use {@link
   * UntypedStrategy#SPARSE_ACCESS} if you access only a few fields per event, or {@link
   * UntypedStrategy#FULL_ITERATION} if you iterate all fields of each event.
   *
   * @param path the path to the JFR recording file
   * @param context shared parsing context for metadata reuse
   * @param bufferSize maximum events buffered (higher = more memory, better throughput)
   * @param strategy optimization strategy for event deserialization
   * @return a new iterator instance
   * @throws IOException if the recording cannot be opened
   * @throws IllegalArgumentException if bufferSize is less than 1
   * @see UntypedStrategy
   */
  static EventIterator open(
      Path path, ParsingContext context, int bufferSize, UntypedStrategy strategy)
      throws IOException {
    if (bufferSize < 1) {
      throw new IllegalArgumentException("Buffer size must be at least 1");
    }
    return new io.jafar.parser.impl.EventIteratorImpl(path, context, bufferSize, strategy);
  }

  /**
   * Returns {@code true} if more events are available.
   *
   * <p>This method blocks until an event becomes available or the end of the recording is reached.
   * If parsing encounters an error, this method returns {@code false} and the error can be
   * retrieved via {@link #getParsingError()}.
   *
   * @return {@code true} if more events are available, {@code false} if end-of-stream
   */
  @Override
  boolean hasNext();

  /**
   * Returns the next event in the recording.
   *
   * @return the next event
   * @throws NoSuchElementException if no more events are available
   */
  @Override
  JafarRecordedEvent next() throws NoSuchElementException;

  /**
   * Returns any parsing error that occurred in the background producer thread.
   *
   * <p>If the parser encounters an error (e.g., corrupt recording, I/O error), the error is
   * captured and {@link #hasNext()} will return {@code false}. Call this method to retrieve the
   * error for logging or handling.
   *
   * @return the parsing exception, or {@code null} if no error occurred
   */
  IOException getParsingError();

  /**
   * Closes the iterator and releases all resources.
   *
   * <p>This method interrupts the background parsing thread and waits for it to terminate. If a
   * parsing error occurred, it will be thrown from this method.
   *
   * @throws IOException if a parsing error occurred or if cleanup fails
   */
  @Override
  void close() throws IOException;
}

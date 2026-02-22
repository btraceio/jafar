package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventIterator;
import io.jafar.parser.api.JafarRecordedEvent;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of EventIterator using a producer-consumer pattern.
 *
 * <p>This class bridges the callback-based parser to a pull-based iterator by:
 *
 * <ul>
 *   <li>Starting a background thread that runs the parser
 *   <li>Registering a handler that enqueues events into a bounded buffer
 *   <li>Providing iterator methods that consume from the buffer
 * </ul>
 *
 * <p>The bounded buffer provides natural backpressure: if the consumer is slow, the producer blocks
 * until space is available in the queue.
 */
public final class EventIteratorImpl implements EventIterator {

  /** Sentinel value used to signal end-of-stream. */
  private static final JafarRecordedEvent END_MARKER =
      new JafarRecordedEvent(null, Collections.emptyMap(), -1, Control.ChunkInfo.NONE);

  /** The blocking queue for producer-consumer communication. */
  private final BlockingQueue<JafarRecordedEvent> queue;

  /** The background thread that runs the parser. */
  private final Thread producerThread;

  /** The parser instance. */
  private final UntypedJafarParser parser;

  /** Flag indicating whether close() has been called. */
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /** Any parsing error that occurred in the producer thread. */
  private volatile IOException parsingError = null;

  /** The next event pre-fetched by hasNext(). */
  private JafarRecordedEvent next = null;

  /** Flag indicating end-of-stream has been reached. */
  private boolean endOfStream = false;

  /**
   * Constructs a new EventIteratorImpl for the specified recording.
   *
   * @param path the path to the JFR recording file
   * @param context the parsing context to use
   * @param bufferSize the maximum number of events to buffer
   * @param strategy the optimization strategy for event deserialization
   * @throws IOException if the parser cannot be opened
   */
  public EventIteratorImpl(
      Path path, ParsingContext context, int bufferSize, UntypedStrategy strategy)
      throws IOException {
    this.queue = new ArrayBlockingQueue<>(bufferSize);
    this.parser = UntypedJafarParser.open(path, context, strategy);

    // Register handler that enqueues events
    parser.handle(
        (type, value, ctl) -> {
          try {
            JafarRecordedEvent event =
                new JafarRecordedEvent(type, value, ctl.stream().position(), ctl.chunkInfo());
            // Blocking put - provides backpressure if consumer is slow
            queue.put(event);
          } catch (InterruptedException e) {
            // Restore interrupt status and abort parsing
            Thread.currentThread().interrupt();
            ctl.abort();
          }
        });

    // Start parsing in background thread
    this.producerThread =
        new Thread(
            () -> {
              try {
                parser.run();
                // Signal completion by enqueuing sentinel
                queue.put(END_MARKER);
              } catch (IOException e) {
                // Capture parsing error
                parsingError = e;
                // Best-effort signal completion
                try {
                  queue.put(END_MARKER);
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                }
              } catch (InterruptedException e) {
                // Thread was interrupted during queue.put(END_MARKER)
                Thread.currentThread().interrupt();
              } finally {
                // Clean up parser resources
                try {
                  parser.close();
                } catch (Exception e) {
                  // Log but don't propagate - original error takes precedence
                  if (parsingError == null && e instanceof IOException) {
                    parsingError = (IOException) e;
                  }
                }
              }
            },
            "jafar-event-iterator-producer");

    // Don't make it daemon - we want to finish parsing even if main thread exits
    this.producerThread.setDaemon(false);
    this.producerThread.start();
  }

  @Override
  public boolean hasNext() {
    // Already at end
    if (endOfStream) {
      return false;
    }

    // Already have next event pre-fetched
    if (next != null) {
      return true;
    }

    // Try to fetch next event
    try {
      next = queue.take(); // Block until event available

      // Check for end-of-stream sentinel
      if (next == END_MARKER) {
        endOfStream = true;
        next = null;
        return false;
      }

      return true;
    } catch (InterruptedException e) {
      // Restore interrupt status
      Thread.currentThread().interrupt();
      endOfStream = true;
      return false;
    }
  }

  @Override
  public JafarRecordedEvent next() {
    // Check if event is available
    if (!hasNext()) {
      throw new NoSuchElementException("No more events available");
    }

    // Return pre-fetched event and clear
    JafarRecordedEvent result = next;
    next = null;
    return result;
  }

  @Override
  public IOException getParsingError() {
    return parsingError;
  }

  @Override
  public void close() throws IOException {
    // Ensure close() is only executed once
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    // If early termination (consumer stopped before end), drain the queue
    // to unblock the producer thread so it can finish naturally
    if (producerThread.isAlive() && !endOfStream) {
      // Drain remaining events to let producer complete without blocking
      while (!endOfStream && producerThread.isAlive()) {
        try {
          JafarRecordedEvent event = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
          if (event == END_MARKER || event == null) {
            break;
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    // Wait for producer thread to finish naturally (with timeout)
    try {
      producerThread.join(5000); // Wait up to 5 seconds
    } catch (InterruptedException e) {
      // Restore interrupt status
      Thread.currentThread().interrupt();
    }

    // Close parser
    try {
      parser.close();
    } catch (Exception e) {
      // Capture genuine errors, ignore interrupt-related cleanup errors
      if (parsingError == null
          && e instanceof IOException
          && !(e instanceof java.nio.channels.ClosedByInterruptException)) {
        parsingError = (IOException) e;
      }
    }

    // Propagate any genuine parsing error
    if (parsingError != null
        && !(parsingError instanceof java.nio.channels.ClosedByInterruptException)) {
      throw parsingError;
    }
  }
}

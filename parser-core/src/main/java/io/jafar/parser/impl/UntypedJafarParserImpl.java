package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.UntypedEventStreamFactory;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of UntypedJafarParser for processing JFR recordings without type safety.
 *
 * <p>This class provides untyped parsing of JFR recordings, exposing events as maps of field names
 * to values rather than strongly-typed objects.
 */
public final class UntypedJafarParserImpl implements UntypedJafarParser {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(UntypedJafarParserImpl.class);

  /**
   * Implementation of HandlerRegistration for untyped event handlers.
   *
   * @param <T> the type parameter (unused in untyped parsing)
   */
  private final class HandlerRegistrationImpl<T> implements HandlerRegistration<T> {
    /** The event handler associated with this registration. */
    private final EventHandler handler;

    /**
     * Constructs a new HandlerRegistrationImpl with the specified handler.
     *
     * @param handler the event handler to register
     */
    HandlerRegistrationImpl(EventHandler handler) {
      this.handler = handler;
    }

    @Override
    public void close() {
      removeHandler(handler);
    }

    @Override
    @Deprecated
    public void destroy(JafarParser cookie) {
      if (cookie == UntypedJafarParserImpl.this) {
        close();
      }
    }
  }

  /** The chunk parser listener for this parser. */
  private final ChunkParserListener parserListener;

  /** The path to the JFR recording file. */
  private final Path path;

  /** The parsing context for this parser. */
  private final ParsingContext context;

  /** The optimization strategy for event deserialization. */
  private final UntypedStrategy strategy;

  private static final EventHandler[] NO_HANDLERS = new EventHandler[0];

  /**
   * The registered event handlers, in registration order, as a copy-on-write array: the array an
   * element was read from is never modified, so the chunk threads dispatching an event iterate a
   * stable snapshot while {@link #handle} and deregistration replace the field (both under {@code
   * handlersLock}). A handler instance appears at most once.
   */
  private volatile EventHandler[] handlers = NO_HANDLERS;

  private final Object handlersLock = new Object();

  private volatile DecodeErrorHandler decodeErrorHandler;

  /**
   * Constructs a new UntypedJafarParserImpl for the specified path and context.
   *
   * @param path the path to the JFR recording file
   * @param context the parsing context to use
   */
  public UntypedJafarParserImpl(Path path, ParsingContext context) {
    this(path, context, UntypedStrategy.SPARSE_ACCESS);
  }

  /**
   * Constructs a new UntypedJafarParserImpl for the specified path, context, and strategy.
   *
   * @param path the path to the JFR recording file
   * @param context the parsing context to use
   * @param strategy the optimization strategy for event deserialization
   */
  public UntypedJafarParserImpl(Path path, ParsingContext context, UntypedStrategy strategy) {
    this.path = path;
    this.context = context;
    this.strategy = strategy != null ? strategy : UntypedStrategy.SPARSE_ACCESS;
    this.parserListener = null;
  }

  /**
   * Constructs a new UntypedJafarParserImpl as a copy of another instance.
   *
   * @param other the instance to copy from
   * @param listener the chunk parser listener to use
   */
  private UntypedJafarParserImpl(UntypedJafarParserImpl other, ChunkParserListener listener) {
    this.path = other.path;
    this.context = other.context;
    this.strategy = other.strategy;

    // the arrays are never modified in place, so sharing the current one is safe
    this.handlers = other.handlers;
    this.decodeErrorHandler = other.decodeErrorHandler;
    this.parserListener = listener;
  }

  @Override
  public HandlerRegistration<?> handle(EventHandler handler) {
    Objects.requireNonNull(handler, "handler");
    synchronized (handlersLock) {
      EventHandler[] current = handlers;
      if (indexOf(current, handler) < 0) {
        EventHandler[] updated = Arrays.copyOf(current, current.length + 1);
        updated[current.length] = handler;
        handlers = updated;
      }
    }
    return new HandlerRegistrationImpl<>(handler);
  }

  private void removeHandler(EventHandler handler) {
    synchronized (handlersLock) {
      EventHandler[] current = handlers;
      int idx = indexOf(current, handler);
      if (idx < 0) {
        return; // already removed, or cleared by close()
      }
      if (current.length == 1) {
        handlers = NO_HANDLERS;
        return;
      }
      EventHandler[] updated = new EventHandler[current.length - 1];
      System.arraycopy(current, 0, updated, 0, idx);
      System.arraycopy(current, idx + 1, updated, idx, current.length - idx - 1);
      handlers = updated;
    }
  }

  private static int indexOf(EventHandler[] array, EventHandler handler) {
    for (int i = 0; i < array.length; i++) {
      if (array[i].equals(handler)) {
        return i;
      }
    }
    return -1;
  }

  /** System property that, when {@code false}, forces the generic reader. */
  public static final String GENERATED_PROPERTY = "jafar.untyped.generated";

  /**
   * Whether {@link #run()} will read events with the bytecode-generated, strategy-aware reader
   * ({@code parser-codegen}) rather than the generic one. It does when {@code parser-codegen} is on
   * the classpath, unless the system property {@value #GENERATED_PROPERTY} is {@code false}.
   *
   * @return {@code true} if the strategy chosen at construction selects generated deserializers
   */
  public boolean usesGeneratedReader() {
    return ParsingContextImpl.untypedStreamFactory() != null
        && !"false".equalsIgnoreCase(System.getProperty(GENERATED_PROPERTY));
  }

  @Override
  public void run() throws IOException {
    try (StreamingChunkParser parser =
        new StreamingChunkParser(
            ((ParsingContextImpl) context).untypedContextFactory(strategy),
            ((ParsingContextImpl) context).parallelism())) {
      parser.parse(path, newListener());
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public UntypedJafarParser onDecodeError(DecodeErrorHandler handler) {
    this.decodeErrorHandler = handler;
    return this;
  }

  private ChunkParserListener newListener() {
    UntypedEventStreamFactory.Sink sink =
        new UntypedEventStreamFactory.Sink() {
          @Override
          public void onEventValue(MetadataClass type, Map<String, Object> value, Control ctl) {
            // One read per event: a handler deregistered while this event is being dispatched
            // (from a handler or from another thread) still receives it, and no later one.
            EventHandler[] current = handlers;
            for (int i = 0; i < current.length; i++) {
              current[i].handle(type, value, ctl);
              if (((ControlImpl) ctl).isAborted()) {
                // same as the typed parser: no further handler sees this event after abort()
                break;
              }
            }
          }

          @Override
          public boolean onDecodeError(EventDecodingException failure) {
            DecodeErrorHandler h = decodeErrorHandler;
            return h != null && h.onDecodeError(failure);
          }
        };
    if (usesGeneratedReader()) {
      return ParsingContextImpl.untypedStreamFactory().create(parserListener, sink);
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "Untyped parser strategy {} reads with the generic reader: {}",
          strategy,
          ParsingContextImpl.untypedStreamFactory() == null
              ? "parser-codegen is not on the classpath"
              : "disabled by -D" + GENERATED_PROPERTY + "=false");
    }
    return new EventStream(parserListener) {
      @Override
      protected void onEventValue(MetadataClass type, Map<String, Object> value, Control ctl) {
        sink.onEventValue(type, value, ctl);
      }

      @Override
      protected boolean onDecodeError(EventDecodingException failure) {
        return sink.onDecodeError(failure);
      }
    };
  }

  @Override
  public void close() throws Exception {
    synchronized (handlersLock) {
      handlers = NO_HANDLERS;
    }
  }

  /**
   * Creates a new instance with the specified parser listener.
   *
   * @param listener the chunk parser listener to use
   * @return a new UntypedJafarParserImpl instance
   */
  @SuppressWarnings("unchecked")
  @Override
  public UntypedJafarParserImpl withParserListener(ChunkParserListener listener) {
    return new UntypedJafarParserImpl(this, listener);
  }
}

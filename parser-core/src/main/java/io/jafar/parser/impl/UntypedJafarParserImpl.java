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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** The registry generation this registration was counted in. */
    private final long registeredIn;

    private final AtomicBoolean closed = new AtomicBoolean();

    HandlerRegistrationImpl(EventHandler handler, long registeredIn) {
      this.handler = handler;
      this.registeredIn = registeredIn;
    }

    @Override
    public void close() {
      // once per registration: a second close() must not release another registration's count
      if (closed.compareAndSet(false, true)) {
        handlers.remove(handler, registeredIn);
      }
    }

    @Override
    @Deprecated
    public void destroy(JafarParser cookie) {
      if (cookie instanceof UntypedJafarParserImpl
          && ((UntypedJafarParserImpl) cookie).handlers == handlers) {
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

  /**
   * The registered event handlers. Shared with the copies {@link #withParserListener} makes, so a
   * handler registered or deregistered through one of them is registered or deregistered for all.
   */
  private final HandlerRegistry<EventHandler> handlers;

  /**
   * How many parsers share {@link #handlers} and have not been closed; the handlers are cleared
   * when the last one closes.
   */
  private final AtomicInteger owners;

  /** Whether this parser has given up its share of {@link #handlers}. */
  private final AtomicBoolean released = new AtomicBoolean();

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
    this.handlers = new HandlerRegistry<>(new EventHandler[0]);
    this.owners = new AtomicInteger(1);
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

    this.handlers = other.handlers;
    this.owners = other.owners;
    this.owners.incrementAndGet();
    this.decodeErrorHandler = other.decodeErrorHandler;
    this.parserListener = listener;
  }

  @Override
  public HandlerRegistration<?> handle(EventHandler handler) {
    Objects.requireNonNull(handler, "handler");
    return new HandlerRegistrationImpl<>(handler, handlers.add(handler));
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
            EventHandler[] current = handlers.snapshot();
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
    if (released.compareAndSet(false, true) && owners.decrementAndGet() == 0) {
      handlers.clear();
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

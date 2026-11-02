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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    public void destroy(JafarParser cookie) {
      assert cookie == UntypedJafarParserImpl.this;
      handlers.remove(handler);
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

  /** The set of registered event handlers. */
  private final Set<EventHandler> handlers;

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
    this.handlers = new HashSet<>();
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

    this.handlers = new HashSet<>(other.handlers);
    this.decodeErrorHandler = other.decodeErrorHandler;
    this.parserListener = listener;
  }

  @Override
  public HandlerRegistration<?> handle(EventHandler handler) {
    handlers.add(handler);
    return new HandlerRegistrationImpl<>(handler);
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
        new StreamingChunkParser(((ParsingContextImpl) context).untypedContextFactory(strategy))) {
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
            for (EventHandler h : handlers) {
              h.handle(type, value, ctl);
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
    handlers.clear();
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

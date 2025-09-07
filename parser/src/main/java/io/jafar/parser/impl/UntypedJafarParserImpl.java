package io.jafar.parser.impl;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
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

  /** The set of registered event handlers. */
  private final Set<EventHandler> handlers;

  /**
   * Constructs a new UntypedJafarParserImpl for the specified path and context.
   *
   * @param path the path to the JFR recording file
   * @param context the parsing context to use
   */
  public UntypedJafarParserImpl(Path path, ParsingContext context) {
    this.path = path;
    this.context = context;
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

    this.handlers = new HashSet<>(other.handlers);
    this.parserListener = listener;
  }

  @Override
  public HandlerRegistration<?> handle(EventHandler handler) {
    handlers.add(handler);
    return new HandlerRegistrationImpl<>(handler);
  }

  @Override
  public void run() throws IOException {
    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) context).untypedContextFactory())) {
      ChunkParserListener listener =
          new EventStream(parserListener) {
            @Override
            protected void onEventValue(MetadataClass type, Map<String, Object> value) {
              handlers.forEach(h -> h.handle(type, value));
            }
          };
      parser.parse(path, listener);
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e);
    }
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

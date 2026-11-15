package io.jafar.parser.impl;

import io.jafar.parser.TimeRange;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.UntypedEventStreamFactory;
import io.jafar.parser.internal_api.collections.LongObjectHashMap;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
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

  @Override
  public HandlerRegistration<?> handle(TypeFilter filter, EventHandler handler) {
    Objects.requireNonNull(filter, "filter");
    Objects.requireNonNull(handler, "handler");
    EventHandler filtered = new FilteredHandler(filter, null, handler);
    return new HandlerRegistrationImpl<>(filtered, handlers.add(filtered));
  }

  @Override
  public HandlerRegistration<?> handle(
      TypeFilter filter, TimeRange startTime, EventHandler handler) {
    Objects.requireNonNull(filter, "filter");
    Objects.requireNonNull(startTime, "startTime");
    Objects.requireNonNull(handler, "handler");
    EventHandler filtered =
        new FilteredHandler(filter, startTime.isUnbounded() ? null : startTime, handler);
    return new HandlerRegistrationImpl<>(filtered, handlers.add(filtered));
  }

  /**
   * A handler registered with a type filter. It sits in the registry in place of the handler, so
   * that registration counting and removal work as for any other; dispatch never calls it, it calls
   * {@link #handler} for the types {@link #filter} accepts.
   */
  private static final class FilteredHandler implements EventHandler {
    final TypeFilter filter;

    /** The start times the handler asked for, or {@code null} when it did not restrict them. */
    final TimeRange startTime;

    final EventHandler handler;

    FilteredHandler(TypeFilter filter, TimeRange startTime, EventHandler handler) {
      this.filter = filter;
      this.startTime = startTime;
      this.handler = handler;
    }

    @Override
    public void handle(MetadataClass type, Map<String, Object> value, Control ctl) {
      if (filter.test(type)) {
        handler.handle(type, value, ctl);
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FilteredHandler)) {
        return false;
      }
      FilteredHandler other = (FilteredHandler) o;
      return filter.equals(other.filter)
          && Objects.equals(startTime, other.startTime)
          && handler.equals(other.handler);
    }

    @Override
    public int hashCode() {
      return 31 * (31 * filter.hashCode() + Objects.hashCode(startTime)) + handler.hashCode();
    }
  }

  /**
   * Skips the chunks no registered handler can need, by start time. It sits between the event
   * stream and the caller's parser listener; see {@link ChunkPruning} for the rules.
   *
   * <p>The decision is taken once per chunk, when its metadata has been read and before its
   * constant pools and events are: a skipped chunk costs its header and its metadata. A handler
   * registered without a start-time range, or no handler at all, keeps every chunk.
   */
  private static final class PruningListener implements ChunkParserListener {
    private final ChunkParserListener delegate;
    private final HandlerRegistry<EventHandler> handlers;

    PruningListener(ChunkParserListener delegate, HandlerRegistry<EventHandler> handlers) {
      this.delegate = delegate;
      this.handlers = handlers;
    }

    @Override
    public void onRecordingStart(ParserContext context) {
      if (delegate != null) {
        delegate.onRecordingStart(context);
      }
    }

    @Override
    public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
      context.put(ChunkHeader.class, header);
      return delegate == null || delegate.onChunkStart(context, chunkIndex, header);
    }

    @Override
    public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
      if (delegate != null && !delegate.onMetadata(context, metadata)) {
        return false;
      }
      ChunkHeader header = context.get(ChunkHeader.class);
      EventHandler[] registered = handlers.snapshot();
      if (header == null || registered.length == 0) {
        return true;
      }
      for (int i = 0; i < registered.length; i++) {
        if (!(registered[i] instanceof FilteredHandler)) {
          return true;
        }
        FilteredHandler handler = (FilteredHandler) registered[i];
        if (handler.startTime == null
            || ChunkPruning.needsChunk(
                handler.filter,
                handler.startTime,
                header.startNanos,
                header.duration,
                metadata.getClasses())) {
          return true;
        }
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "Chunk {} skipped: it spans [{}, {}] ns and no handler's start-time range reaches it",
            context.getChunkIndex(),
            header.startNanos,
            header.startNanos + header.duration);
      }
      return false;
    }

    @Override
    public boolean onCheckpoint(ParserContext context, CheckpointEvent checkpoint) {
      return delegate == null || delegate.onCheckpoint(context, checkpoint);
    }

    @Override
    public boolean onEvent(
        ParserContext context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
      return delegate == null
          || delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize);
    }

    @Override
    public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
      context.remove(ChunkHeader.class);
      return delegate == null || delegate.onChunkEnd(context, chunkIndex, skipped);
    }

    @Override
    public void onRecordingEnd(ParserContext context) {
      if (delegate != null) {
        delegate.onRecordingEnd(context);
      }
    }
  }

  /**
   * The listener the event stream delegates to: the caller's, wrapped for chunk skipping when a
   * handler registered so far asked for a start-time range. Without one nothing is wrapped, so
   * parsers that do not use ranges pay nothing for them; a range registered after {@code run()}
   * began therefore skips no chunk, which the range's contract allows.
   */
  private ChunkParserListener delegateListener() {
    EventHandler[] registered = handlers.snapshot();
    for (int i = 0; i < registered.length; i++) {
      if (registered[i] instanceof FilteredHandler
          && ((FilteredHandler) registered[i]).startTime != null) {
        return new PruningListener(parserListener, handlers);
      }
    }
    return parserListener;
  }

  /**
   * Which handlers want each event type of one chunk. Held in the chunk's {@link ParserContext} and
   * touched only by the thread parsing that chunk, so it needs no synchronisation.
   *
   * <p>An entry is valid for one set of registered handlers and one {@link MetadataClass} instance:
   * type ids are local to a chunk, and the instance check keeps an entry from answering for another
   * chunk's type should a context ever be reused.
   */
  private static final class ChunkSelection {
    EventHandler[] registered;
    LongObjectHashMap<TypeTargets> byTypeId = new LongObjectHashMap<>();
  }

  private static final class TypeTargets {
    final MetadataClass type;
    final EventHandler[] handlers;

    TypeTargets(MetadataClass type, EventHandler[] handlers) {
      this.type = type;
      this.handlers = handlers;
    }
  }

  private static final EventHandler[] NO_HANDLERS = new EventHandler[0];

  /**
   * The handlers that want events of {@code type}, or {@code null} when none does and the event
   * need not be decoded. A filter is asked once per chunk, type and set of registered handlers.
   */
  private EventHandler[] targetsFor(ParserContext context, long typeId, MetadataClass type) {
    EventHandler[] registered = handlers.snapshot();
    boolean filtered = false;
    for (int i = 0; i < registered.length; i++) {
      if (registered[i] instanceof FilteredHandler) {
        filtered = true;
        break;
      }
    }
    if (!filtered) {
      // No filter in play: every handler gets every event, and with no handler at all the event
      // is still decoded, as before filters existed (a parser listener may rely on decode errors).
      return registered;
    }
    ChunkSelection selection = context.get(ChunkSelection.class);
    if (selection == null) {
      selection = new ChunkSelection();
      context.put(ChunkSelection.class, selection);
    }
    if (selection.registered != registered) {
      selection.registered = registered;
      selection.byTypeId = new LongObjectHashMap<>();
    }
    TypeTargets targets = selection.byTypeId.get(typeId);
    if (targets == null || targets.type != type) {
      targets = new TypeTargets(type, accepting(registered, type));
      selection.byTypeId.put(typeId, targets);
    }
    return targets.handlers.length == 0 ? null : targets.handlers;
  }

  /** The handlers of {@code registered} that accept {@code type}, each once. */
  private static EventHandler[] accepting(EventHandler[] registered, MetadataClass type) {
    EventHandler[] accepted = new EventHandler[registered.length];
    int count = 0;
    for (int i = 0; i < registered.length; i++) {
      EventHandler candidate = registered[i];
      if (candidate instanceof FilteredHandler) {
        FilteredHandler filtered = (FilteredHandler) candidate;
        if (!filtered.filter.test(type)) {
          continue;
        }
        candidate = filtered.handler;
      }
      boolean present = false;
      for (int j = 0; j < count; j++) {
        if (accepted[j].equals(candidate)) {
          // registered both with and without a filter: still one call per event
          present = true;
          break;
        }
      }
      if (!present) {
        accepted[count++] = candidate;
      }
    }
    return count == 0 ? NO_HANDLERS : Arrays.copyOf(accepted, count);
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
          public Object select(ParserContext context, long typeId, MetadataClass type) {
            return targetsFor(context, typeId, type);
          }

          @Override
          public void onEventValue(MetadataClass type, Map<String, Object> value, Control ctl) {
            onEventValue(type, value, ctl, handlers.snapshot());
          }

          @Override
          public void onEventValue(
              MetadataClass type, Map<String, Object> value, Control ctl, Object selection) {
            // The handlers were read once, when the event was selected: a handler deregistered
            // while this event is being dispatched (from a handler or from another thread) still
            // receives it, and no later one.
            EventHandler[] current = (EventHandler[]) selection;
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
    ChunkParserListener delegate = delegateListener();
    if (usesGeneratedReader()) {
      return ParsingContextImpl.untypedStreamFactory().create(delegate, sink);
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "Untyped parser strategy {} reads with the generic reader: {}",
          strategy,
          ParsingContextImpl.untypedStreamFactory() == null
              ? "parser-codegen is not on the classpath"
              : "disabled by -D" + GENERATED_PROPERTY + "=false");
    }
    return new EventStream(delegate) {
      @Override
      protected void onEventValue(MetadataClass type, Map<String, Object> value, Control ctl) {
        sink.onEventValue(type, value, ctl);
      }

      @Override
      protected Object select(ParserContext context, long typeId, MetadataClass type) {
        return sink.select(context, typeId, type);
      }

      @Override
      protected void onEventValue(
          MetadataClass type, Map<String, Object> value, Control ctl, Object selection) {
        sink.onEventValue(type, value, ctl, selection);
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

package io.jafar.parser.impl;

import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.UntypedCodeGenerator;
import io.jafar.parser.internal_api.UntypedDeserializerCache;
import io.jafar.parser.internal_api.UntypedEventDeserializer;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Event stream with Tier 3 bytecode generation optimization for untyped parsing.
 *
 * <p>This class uses {@link UntypedCodeGenerator} to generate specialized deserializers per event
 * type at runtime. Generated deserializers directly read from {@link RecordingStream} without
 * {@link io.jafar.parser.internal_api.ValueProcessor} callback overhead.
 *
 * <p>Values are the same as the generic {@link EventStream}'s; see {@link UntypedCodeGenerator} for
 * the contract and {@code GeneratedUntypedParityTest} for the check.
 *
 * <p>Key optimizations:
 *
 * <ul>
 *   <li>Zero callback overhead (direct bytecode instead of ValueProcessor callbacks)
 *   <li>Simple events (≤10 fields) use eager HashMap deserialization
 *   <li>Complex events (&gt;10 fields) use lazy ArrayPool + LazyEventMap
 *   <li>Deserializers are shared across chunks by structural key ({@link
 *       UntypedCodeGenerator#cacheKey}), never by type id alone
 * </ul>
 */
@SuppressWarnings("unchecked")
public abstract class EventStreamGenerated implements ChunkParserListener {
  private final ChunkParserListener delegate;
  private final ThreadLocal<Control> control = ThreadLocal.withInitial(ControlImpl::new);

  /**
   * Constructs a new EventStreamGenerated with the specified delegate.
   *
   * @param delegate the delegate chunk parser listener
   */
  public EventStreamGenerated(ChunkParserListener delegate) {
    this.delegate = delegate;
  }

  @Override
  public final boolean onMetadata(ParserContext context, MetadataEvent metadata) {
    return delegate == null || delegate.onMetadata(context, metadata);
  }

  @Override
  public final boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
    if (context.get(UntypedDeserializerCache.class) == null) {
      // Without the shared cache every event would have to be skipped; fail instead of silently
      // delivering nothing (Verification.md R3).
      throw new IllegalStateException(
          "EventStreamGenerated needs an untyped parser context (UntypedDeserializerCache missing);"
              + " parse with ParsingContextImpl.untypedContextFactory(...)");
    }
    ((ControlImpl) control.get()).setStream(context.get(RecordingStream.class));
    context.put(Control.ChunkInfo.class, new ChunkInfoImpl(header));
    context.put(ChunkDeserializers.class, new ChunkDeserializers());

    // Set type filter to accept all events (untyped parser behavior)
    context.put(TypeFilter.class, t -> true);

    return delegate == null || delegate.onChunkStart(context, chunkIndex, header);
  }

  @Override
  public final boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
    context.remove(TypeFilter.class);
    context.remove(ChunkDeserializers.class);
    return delegate == null || delegate.onChunkEnd(context, chunkIndex, skipped);
  }

  /**
   * Deserializes the event with a generated deserializer and hands it to {@link #onEventValue}.
   *
   * <p>A failure to generate or run a deserializer is not swallowed: it is rethrown as {@link
   * GeneratedDeserializationException} naming the event type, chunk and position, and the parse
   * fails. (It used to be caught, which skipped the event <em>and</em>, by returning {@code false}
   * to the chunk parser, every remaining event in its chunk.)
   */
  @Override
  public final boolean onEvent(
      ParserContext context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
    ControlImpl ctl = (ControlImpl) control.get();
    MetadataClass eventClz = context.getMetadataLookup().getClass(typeId);
    Map<String, Object> value;
    try {
      UntypedEventDeserializer deserializer = deserializerFor(context, typeId, eventClz);
      value = deserializer.deserialize(context.get(RecordingStream.class), context);
    } catch (Exception e) {
      throw new GeneratedDeserializationException(
          "Generated untyped deserializer failed for event type "
              + (eventClz != null ? eventClz.getName() : "<unknown>")
              + " (id "
              + typeId
              + ") in chunk "
              + context.getChunkIndex()
              + " at position "
              + eventStartPos,
          e);
    }
    onEventValue(eventClz, value, ctl);
    return !ctl.abortFlag
        && (delegate == null
            || delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize));
  }

  private static UntypedEventDeserializer deserializerFor(
      ParserContext context, long typeId, MetadataClass eventClz) {
    if (eventClz == null) {
      throw new IllegalStateException("No metadata for event type id " + typeId);
    }
    // Within a chunk, the MetadataClass instance identifies the type: an identity lookup, with no
    // boxing of the id ...
    ChunkDeserializers local = context.get(ChunkDeserializers.class);
    UntypedEventDeserializer deserializer = local.get(eventClz);
    if (deserializer == null) {
      UntypedStrategy strategy = context.get(UntypedStrategy.class);
      UntypedStrategy effective = strategy != null ? strategy : UntypedStrategy.SPARSE_ACCESS;
      // ... but not across chunks: other chunks may use this id for another type. The shared
      // cache is keyed by structure.
      deserializer =
          shared(
              context.get(UntypedDeserializerCache.class),
              UntypedCodeGenerator.cacheKey(eventClz, effective),
              () -> UntypedCodeGenerator.generate(eventClz, effective));
      local.put(eventClz, deserializer);
    }
    return deserializer;
  }

  /**
   * Returns the shared deserializer for {@code key}, generating it at most once.
   *
   * <p>Generation takes 0.3–0.8 ms per class, so it must not run inside {@code computeIfAbsent}:
   * that holds the map's bin lock for the duration, blocking threads that need <em>other</em> keys
   * in the same bin ("the computation should be short and simple", {@link
   * java.util.concurrent.ConcurrentHashMap#computeIfAbsent}). Instead a {@link Generating}
   * placeholder is installed in O(1) and generated outside the map lock; only threads that need the
   * same key wait, on that placeholder. A hit is a lock-free {@code get} (on JDK 8, {@code
   * computeIfAbsent} locks even on a hit).
   */
  static UntypedEventDeserializer shared(
      UntypedDeserializerCache cache, String key, Supplier<UntypedEventDeserializer> generator) {
    UntypedEventDeserializer d = cache.get(key);
    if (d == null) {
      d = cache.computeIfAbsent(key, k -> new Generating(generator));
    }
    if (d instanceof Generating) {
      Generating g = (Generating) d;
      try {
        return g.resolve();
      } catch (RuntimeException e) {
        cache.remove(key, g); // let a later event retry, as when generation ran in the map
        throw e;
      }
    }
    return d;
  }

  /**
   * Placeholder for a deserializer being generated. Resolves once; afterwards it only forwards, and
   * the per-chunk map holds the resolved instance, so events never go through it.
   */
  private static final class Generating implements UntypedEventDeserializer {
    // Captures the chunk's MetadataClass; cleared once generated, as the cache outlives the chunk.
    private Supplier<UntypedEventDeserializer> generator;
    private volatile UntypedEventDeserializer generated;

    Generating(Supplier<UntypedEventDeserializer> generator) {
      this.generator = generator;
    }

    UntypedEventDeserializer resolve() {
      UntypedEventDeserializer d = generated;
      if (d == null) {
        synchronized (this) {
          d = generated;
          if (d == null) {
            d = generator.get();
            generator = null;
            generated = d;
          }
        }
      }
      return d;
    }

    @Override
    public Map<String, Object> deserialize(RecordingStream stream, ParserContext context) {
      return resolve().deserialize(stream, context);
    }
  }

  /**
   * Per-chunk type → deserializer lookup. Keyed by identity: {@code MetadataClass.equals} is
   * structural and its hash covers all fields. A chunk is parsed by a single thread.
   */
  private static final class ChunkDeserializers
      extends IdentityHashMap<MetadataClass, UntypedEventDeserializer> {
    private static final long serialVersionUID = 1L;
  }

  /**
   * Thrown when a generated deserializer cannot be generated or fails while reading an event. The
   * cause carries the reason.
   */
  public static final class GeneratedDeserializationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GeneratedDeserializationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  @Override
  public final void onRecordingStart(ParserContext context) {
    if (delegate != null) {
      delegate.onRecordingStart(context);
    } else {
      ChunkParserListener.super.onRecordingStart(context);
    }
  }

  @Override
  public final boolean onCheckpoint(ParserContext context, CheckpointEvent checkpoint) {
    return delegate != null
        ? delegate.onCheckpoint(context, checkpoint)
        : ChunkParserListener.super.onCheckpoint(context, checkpoint);
  }

  @Override
  public final void onRecordingEnd(ParserContext context) {
    if (delegate != null) {
      delegate.onRecordingEnd(context);
    } else {
      ChunkParserListener.super.onRecordingEnd(context);
    }
  }

  /**
   * Called when an event value is processed.
   *
   * <p>This method is called for each event value that is successfully deserialized and should be
   * implemented by subclasses to handle the event data.
   *
   * @param type the metadata class type of the event
   * @param value the deserialized event value as a map
   * @param ctl parser {@linkplain Control} object
   */
  protected abstract void onEventValue(MetadataClass type, Map<String, Object> value, Control ctl);
}

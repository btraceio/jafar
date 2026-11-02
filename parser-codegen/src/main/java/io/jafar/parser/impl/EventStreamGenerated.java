package io.jafar.parser.impl;

import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
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
import java.util.concurrent.atomic.AtomicBoolean;
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

  /**
   * Abort state shared by every chunk of one parse. Chunks are parsed on parallel threads, so a
   * per-thread flag would stop only the chunk whose handler called {@link Control#abort()}.
   */
  private final AtomicBoolean aborted = new AtomicBoolean();

  private final ThreadLocal<Control> control =
      ThreadLocal.withInitial(() -> new ControlImpl(aborted));

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
    if (aborted.get()) {
      // Aborted after this chunk started: skip binding the chunk's metadata, its constant pools
      // and its events. The parser then calls onChunkEnd with skipped=true.
      return false;
    }
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

    boolean proceed = delegate == null || delegate.onChunkStart(context, chunkIndex, header);
    // A chunk that starts after an abort is skipped; onChunkEnd still runs, with skipped=true.
    return proceed && !aborted.get();
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
   * <p>Same contract as {@link EventStream#onEvent}: a payload that cannot be decoded (including an
   * event type id the chunk metadata does not declare) is passed to {@link #onDecodeError}; unless
   * that returns {@code true} the parse fails with the {@link EventDecodingException}, which names
   * the type, chunk and position. A failure to <em>generate</em> a deserializer is a parser bug,
   * not bad data: it fails the parse with {@link GeneratedDeserializationException} and is never
   * skipped.
   */
  @Override
  public final boolean onEvent(
      ParserContext context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
    ControlImpl ctl = (ControlImpl) control.get();
    if (ctl.isAborted()) {
      // aborted by a handler in this or another chunk: deliver nothing more, skip the chunk rest
      return false;
    }
    MetadataClass eventClz = context.getMetadataLookup().getClass(typeId);
    Map<String, Object> value;
    try {
      if (eventClz == null) {
        throw new IllegalStateException("No metadata for event type id " + typeId);
      }
      UntypedEventDeserializer deserializer = deserializerFor(context, eventClz);
      value = deserializer.deserialize(context.get(RecordingStream.class), context);
    } catch (GeneratedDeserializationException e) {
      throw e;
    } catch (Exception e) {
      EventDecodingException failure =
          new EventDecodingException(
              eventClz != null ? eventClz.getName() : null,
              typeId,
              context.getChunkIndex(),
              eventStartPos,
              rawSize,
              e);
      if (!onDecodeError(failure)) {
        throw failure;
      }
      // Skipped. StreamingChunkParser repositions to eventStartPos + rawSize after onEvent returns
      // true, so the next event is read from its own start whatever the decoder consumed.
      return !ctl.isAborted()
          && (delegate == null
              || delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize));
    }
    // Outside the try: an exception from the handler is the handler's, not a decoding failure.
    onEventValue(eventClz, value, ctl);
    return !ctl.isAborted()
        && (delegate == null
            || delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize));
  }

  /**
   * Called when an event's payload cannot be decoded, on the thread parsing its chunk.
   *
   * <p>When it returns {@code true}, the delegate listener's {@code onEvent} is still called for
   * the skipped event (its position and size are valid); {@link #onEventValue} is not.
   *
   * @param failure names the event type, chunk and position; its cause is the decoder's reason
   * @return {@code true} to skip the event and continue with the next one, {@code false} to fail
   *     the parse with {@code failure}. The default fails the parse.
   */
  protected boolean onDecodeError(EventDecodingException failure) {
    return false;
  }

  private static UntypedEventDeserializer deserializerFor(
      ParserContext context, MetadataClass eventClz) {
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
              () -> {
                try {
                  return UntypedCodeGenerator.generate(eventClz, effective);
                } catch (RuntimeException e) {
                  throw new GeneratedDeserializationException(
                      "Failed to generate the untyped deserializer for event type "
                          + eventClz.getName(),
                      e);
                }
              });
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
   * Thrown when a deserializer cannot be generated: a parser bug, never skipped by {@link
   * #onDecodeError}. The cause carries the reason. (A failure while <em>reading</em> an event is an
   * {@link EventDecodingException}.)
   */
  public static final class GeneratedDeserializationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GeneratedDeserializationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  @Override
  public final void onRecordingStart(ParserContext context) {
    // A listener instance may be handed to more than one parse; each starts un-aborted.
    aborted.set(false);
    if (delegate != null) {
      delegate.onRecordingStart(context);
    } else {
      ChunkParserListener.super.onRecordingStart(context);
    }
  }

  @Override
  public final boolean onCheckpoint(ParserContext context, CheckpointEvent checkpoint) {
    if (aborted.get()) {
      // Aborted while this chunk's constant pools are being read: skip the rest of the chain.
      return false;
    }
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

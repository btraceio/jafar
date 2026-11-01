package io.jafar.parser.impl;

import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.UntypedCodeGenerator;
import io.jafar.parser.internal_api.UntypedDeserializerCache;
import io.jafar.parser.internal_api.UntypedEventDeserializer;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataFingerprint;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Event stream with Tier 3 bytecode generation optimization for untyped parsing.
 *
 * <p>This class uses {@link UntypedCodeGenerator} to generate specialized deserializers per event
 * type at runtime. Generated deserializers directly read from {@link RecordingStream} without
 * {@link io.jafar.parser.internal_api.ValueProcessor} callback overhead.
 *
 * <p>Key optimizations:
 *
 * <ul>
 *   <li>Zero callback overhead (direct bytecode instead of ValueProcessor callbacks)
 *   <li>Simple events (≤10 fields) use eager HashMap deserialization
 *   <li>Complex events (&gt;10 fields) use lazy ArrayPool + LazyEventMap
 *   <li>Deserializers cached by event type ID for reuse
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
    ((ControlImpl) control.get()).setStream(context.get(RecordingStream.class));
    context.put(Control.ChunkInfo.class, new ChunkInfoImpl(header));

    // Set type filter to accept all events (untyped parser behavior)
    context.put(TypeFilter.class, t -> true);
    context.put(ChunkDeserializers.class, new ChunkDeserializers());

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
   * <p>Same contract as {@link EventStream#onEvent}: a payload that cannot be decoded (including an
   * event type id the chunk metadata does not declare) is passed to {@link #onDecodeError}; unless
   * that returns {@code true} the parse fails with the {@link EventDecodingException}. A failure to
   * <em>generate</em> a deserializer is a parser bug, not bad data, and always fails the parse. (An
   * {@link IOException} used to fall back to the delegate, which returned {@code false} with no
   * delegate, and the chunk parser then skipped the rest of the chunk without an error.)
   */
  @Override
  public final boolean onEvent(
      ParserContext context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
    ControlImpl ctl = (ControlImpl) control.get();
    MetadataClass eventClz = context.getMetadataLookup().getClass(typeId);
    Map<String, Object> value;
    try {
      if (eventClz == null) {
        throw new IllegalStateException(
            "Event type id " + typeId + " is not declared in the chunk metadata");
      }
      UntypedEventDeserializer deserializer = deserializerFor(context, typeId, eventClz);
      value = deserializer.deserialize(context.get(RecordingStream.class), context);
    } catch (DeserializerGenerationException e) {
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
      return !ctl.abortFlag
          && (delegate == null
              || delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize));
    }
    // Outside the try: an exception from the handler is the handler's, not a decoding failure.
    onEventValue(eventClz, value, ctl);
    return !ctl.abortFlag
        && (delegate == null
            || delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize));
  }

  private static UntypedEventDeserializer deserializerFor(
      ParserContext context, long typeId, MetadataClass eventClz) {
    UntypedDeserializerCache cache = context.get(UntypedDeserializerCache.class);
    if (cache == null) {
      // Without the parse-wide cache no event could be decoded; fail rather than skip every chunk
      // (Verification.md R3).
      throw new IllegalStateException(
          "EventStreamGenerated needs an untyped parser context (UntypedDeserializerCache"
              + " missing); parse with ParsingContextImpl.untypedContextFactory(...)");
    }
    // Get strategy from context (defaults to SPARSE_ACCESS if not set)
    UntypedStrategy strategy = context.get(UntypedStrategy.class);
    final UntypedStrategy finalStrategy =
        strategy != null ? strategy : UntypedStrategy.SPARSE_ACCESS;

    // Type ids are chunk-scoped: resolve the id to a deserializer once per chunk, through the
    // parse-wide cache keyed by the type's structure.
    ChunkDeserializers chunkDeserializers = context.get(ChunkDeserializers.class);
    UntypedEventDeserializer deserializer = chunkDeserializers.get(typeId);
    if (deserializer == null) {
      MutableMetadataLookup metadata = (MutableMetadataLookup) context.getMetadataLookup();
      MetadataFingerprint key =
          MetadataFingerprint.compute(
              metadata,
              MetadataFingerprint.computeReachableTypes(
                  metadata, Collections.singleton(eventClz.getName())));
      deserializer =
          cache.computeIfAbsent(
              key,
              k -> {
                try {
                  return UntypedCodeGenerator.generate(eventClz, finalStrategy);
                } catch (Exception e) {
                  throw new DeserializerGenerationException(
                      "Failed to generate deserializer for event type: " + eventClz.getName(), e);
                }
              });
      chunkDeserializers.put(typeId, deserializer);
    }
    return deserializer;
  }

  /** A deserializer could not be generated: a parser bug, never skipped by onDecodeError. */
  static final class DeserializerGenerationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DeserializerGenerationException(String message, Throwable cause) {
      super(message, cause);
    }
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

  /**
   * The deserializers resolved for the current chunk, by type id. A chunk is decoded by one thread.
   */
  private static final class ChunkDeserializers extends HashMap<Long, UntypedEventDeserializer> {
    private static final long serialVersionUID = 1L;
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

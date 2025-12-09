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
 *   <li>Complex events (>10 fields) use lazy ArrayPool + LazyEventMap
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

    return delegate == null || delegate.onChunkStart(context, chunkIndex, header);
  }

  @Override
  public final boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
    context.remove(TypeFilter.class);
    return delegate == null || delegate.onChunkEnd(context, chunkIndex, skipped);
  }

  @SuppressWarnings("unchecked")
  @Override
  public final boolean onEvent(
      ParserContext context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
    ControlImpl ctl = (ControlImpl) control.get();
    try {
      MetadataClass eventClz = context.getMetadataLookup().getClass(typeId);
      RecordingStream stream = context.get(RecordingStream.class);

      // Get or generate deserializer from cache
      UntypedDeserializerCache cache = context.get(UntypedDeserializerCache.class);
      if (cache == null) {
        // Fallback if cache not available (shouldn't happen in normal usage)
        return !ctl.abortFlag
            && delegate != null
            && delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize);
      }

      // Get strategy from context (defaults to SPARSE_ACCESS if not set)
      UntypedStrategy strategy = context.get(UntypedStrategy.class);
      if (strategy == null) {
        strategy = UntypedStrategy.SPARSE_ACCESS;
      }
      final UntypedStrategy finalStrategy = strategy;

      UntypedEventDeserializer deserializer =
          cache.computeIfAbsent(
              typeId,
              id -> {
                try {
                  return UntypedCodeGenerator.generate(eventClz, finalStrategy);
                } catch (Exception e) {
                  throw new RuntimeException(
                      "Failed to generate deserializer for event type: " + eventClz.getName(), e);
                }
              });

      // Deserialize event using generated code
      Map<String, Object> value = deserializer.deserialize(stream, context);

      // Process event value
      onEventValue(eventClz, value, ctl);

    } catch (Exception e) {
      // Handle any errors during generation or deserialization
      return !ctl.abortFlag
          && delegate != null
          && delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize);
    }
    return !ctl.abortFlag
        && (delegate == null
            || delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize));
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

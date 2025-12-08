package io.jafar.parser.impl;

import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import java.io.IOException;
import java.util.Map;

/**
 * Abstract base class for event stream processing in JFR recordings.
 *
 * <p>This class implements ChunkParserListener and provides a framework for processing JFR events
 * with support for constant pools and value processing.
 */
@SuppressWarnings("unchecked")
public abstract class EventStream implements ChunkParserListener {
  /** The delegate chunk parser listener. */
  private final ChunkParserListener delegate;

  private final ThreadLocal<Control> control = ThreadLocal.withInitial(ControlImpl::new);

  /**
   * Constructs a new EventStream with the specified delegate.
   *
   * @param delegate the delegate chunk parser listener
   */
  public EventStream(ChunkParserListener delegate) {
    this.delegate = delegate;
  }

  @Override
  public final boolean onMetadata(ParserContext context, MetadataEvent metadata) {
    // use this callback to inspect/process metadata registrations
    return delegate == null
        || delegate.onMetadata(context, metadata); // can return 'false' to abort processing
  }

  @Override
  public final boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
    ((ControlImpl) control.get()).setStream(context.get(RecordingStream.class));
    context.put(Control.ChunkInfo.class, new ChunkInfoImpl(header));

    MapValueBuilder builder = new MapValueBuilder(context);
    GenericValueReader r = new GenericValueReader(builder);

    // Make sure we hava the generic value reader available
    context.put(GenericValueReader.class, r);
    // We do this in the untyped parser -> we need 'all accepting' type filter
    context.put(TypeFilter.class, t -> true);

    return delegate == null || delegate.onChunkStart(context, chunkIndex, header);
  }

  @Override
  public final boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
    context.remove(GenericValueReader.class);
    context.remove(TypeFilter.class);

    return delegate == null || delegate.onChunkEnd(context, chunkIndex, skipped);
  }

  @SuppressWarnings("unchecked")
  @Override
  public final boolean onEvent(
      ParserContext context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
    ControlImpl ctl = (ControlImpl) control.get();
    try {
      GenericValueReader r = context.get(GenericValueReader.class);
      MapValueBuilder builder = r != null ? r.getProcessor() : null;

      if (builder == null) {
        return !ctl.abortFlag
            && delegate != null
            && delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize);
      }

      MetadataClass eventClz = context.getMetadataLookup().getClass(typeId);
      try {
        builder.reset();
        builder.setEventType(eventClz);
        builder.onComplexValueStart(null, null, eventClz);
        r.readValue(context.get(RecordingStream.class), eventClz);
      } finally {
        builder.onComplexValueEnd(null, null, eventClz);
        Map<String, Object> value = builder.getRoot();

        // Process event value with parsed data
        onEventValue(eventClz, value, ctl);
      }
    } catch (IOException e) {
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
   * <p>This method is called for each event value that is successfully parsed and should be
   * implemented by subclasses to handle the event data.
   *
   * @param type the metadata class type of the event
   * @param value the parsed event value as a map
   * @param ctl parser {@linkplain Control} object
   */
  protected abstract void onEventValue(MetadataClass type, Map<String, Object> value, Control ctl);
}

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event stream with Tier 2 lazy deserialization optimization for benchmarking.
 *
 * <p>This class uses {@link LazyMapValueBuilder} which defers HashMap.Node allocations until fields
 * are accessed. Used to measure performance impact of lazy deserialization pattern.
 */
@SuppressWarnings("unchecked")
public abstract class EventStreamLazy implements ChunkParserListener {
  private final ChunkParserListener delegate;

  /**
   * Abort state shared by every chunk of one parse. Chunks are parsed on parallel threads, so a
   * per-thread flag would stop only the chunk whose handler called {@link Control#abort()}.
   */
  private final AtomicBoolean aborted = new AtomicBoolean();

  private final ThreadLocal<Control> control =
      ThreadLocal.withInitial(() -> new ControlImpl(aborted));

  public EventStreamLazy(ChunkParserListener delegate) {
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

    LazyMapValueBuilder builder = new LazyMapValueBuilder(context);
    GenericValueReader r = new GenericValueReader(builder);

    context.put(GenericValueReader.class, r);
    context.put(TypeFilter.class, t -> true);

    boolean proceed = delegate == null || delegate.onChunkStart(context, chunkIndex, header);
    // A chunk that starts after an abort is skipped; onChunkEnd still runs, with skipped=true.
    return proceed && !aborted.get();
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
    if (ctl.isAborted()) {
      // aborted by a handler in this or another chunk: deliver nothing more, skip the chunk rest
      return false;
    }
    try {
      GenericValueReader r = context.get(GenericValueReader.class);
      LazyMapValueBuilder builder = r != null ? r.getProcessor() : null;

      if (builder == null) {
        return !ctl.isAborted()
            && delegate != null
            && delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize);
      }

      MetadataClass eventClz = context.getMetadataLookup().getClass(typeId);
      try {
        builder.reset();
        builder.onComplexValueStart(null, null, eventClz);
        r.readValue(context.get(RecordingStream.class), eventClz);
      } finally {
        builder.onComplexValueEnd(null, null, eventClz);
        Map<String, Object> value = builder.getRoot();

        onEventValue(eventClz, value, ctl);
      }
    } catch (IOException e) {
      return !ctl.isAborted()
          && delegate != null
          && delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize);
    }
    return !ctl.isAborted()
        && (delegate == null
            || delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize));
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

  protected abstract void onEventValue(MetadataClass type, Map<String, Object> value, Control ctl);
}

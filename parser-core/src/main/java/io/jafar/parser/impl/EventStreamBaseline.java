package io.jafar.parser.impl;

import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
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
 * Baseline event stream for benchmarking without flyweight optimization.
 *
 * <p>This class uses {@link MapValueBuilderBaseline} which returns standard HashMap instances
 * instead of FlyweightEventMap. Used to measure the performance impact of the flyweight pattern.
 */
@SuppressWarnings("unchecked")
public abstract class EventStreamBaseline implements ChunkParserListener {
  private final ChunkParserListener delegate;

  /**
   * Abort state shared by every chunk of one parse. Chunks are parsed on parallel threads, so a
   * per-thread flag would stop only the chunk whose handler called {@link Control#abort()}.
   */
  private final AtomicBoolean aborted = new AtomicBoolean();

  private final ThreadLocal<Control> control =
      ThreadLocal.withInitial(() -> new ControlImpl(aborted));

  public EventStreamBaseline(ChunkParserListener delegate) {
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
    ((ControlImpl) control.get()).setStream(context.get(RecordingStream.class));
    context.put(Control.ChunkInfo.class, new ChunkInfoImpl(header));

    MapValueBuilderBaseline builder = new MapValueBuilderBaseline(context);
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
   * Decodes the event into a map and hands it to {@link #onEventValue}.
   *
   * <p>A payload that cannot be decoded is passed to {@link #onDecodeError}; unless that returns
   * {@code true} the parse fails with the {@link EventDecodingException}, which names the type,
   * chunk and position. The half-built map is never delivered. (Both used to happen: the partial
   * map was delivered from a {@code finally}, and for an {@link IOException} this method then
   * returned {@code false}, which the chunk parser reads as "skip the rest of the chunk", so every
   * later event in the chunk was dropped without an error.)
   */
  @Override
  public final boolean onEvent(
      ParserContext context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
    ControlImpl ctl = (ControlImpl) control.get();
    if (ctl.isAborted()) {
      // aborted by a handler in this or another chunk: deliver nothing more, skip the chunk rest
      return false;
    }
    GenericValueReader r = context.get(GenericValueReader.class);
    MapValueBuilderBaseline builder = r != null ? (MapValueBuilderBaseline) r.getProcessor() : null;
    if (builder == null) {
      // onChunkStart installs the reader for every chunk whose events are read; without it no event
      // could be decoded, so fail rather than skip (Verification.md R3).
      throw new IllegalStateException(
          "No GenericValueReader in chunk " + context.getChunkIndex() + "; onChunkStart not run?");
    }

    MetadataClass eventClz = context.getMetadataLookup().getClass(typeId);
    Map<String, Object> value;
    try {
      builder.reset();
      builder.onComplexValueStart(null, null, eventClz);
      r.readValue(context.get(RecordingStream.class), eventClz);
      builder.onComplexValueEnd(null, null, eventClz);
      value = builder.getRoot();
    } catch (Exception e) {
      EventDecodingException failure =
          new EventDecodingException(
              eventClz != null ? eventClz.getName() : null,
              typeId,
              context.getChunkIndex(),
              eventStartPos,
              rawSize,
              e);
      builder.reset(); // drop whatever the failed event left on the builder's stack
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

  protected abstract void onEventValue(MetadataClass type, Map<String, Object> value, Control ctl);
}

package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.internal_api.RecordingStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of Control interface that provides access to stream information.
 *
 * <p>One instance exists per parsing thread, because the stream position is per chunk and chunks
 * are parsed on parallel threads. The abort state is <b>not</b> per thread: every instance created
 * for one parse shares the same {@link AtomicBoolean}, so {@link #abort()} called from any chunk is
 * seen by all of them (see {@link Control#abort()} for the contract).
 */
final class ControlImpl implements Control {
  private RecordingStream rStream;
  private final AtomicBoolean aborted;

  private final Stream stream =
      new Stream() {
        @Override
        public long position() {
          return rStream != null ? rStream.position() : -1;
        }
      };

  /**
   * Creates a control instance sharing the given abort state.
   *
   * @param aborted the abort state of the parse this instance belongs to; shared by every thread
   *     parsing a chunk of that recording
   */
  ControlImpl(AtomicBoolean aborted) {
    this.aborted = aborted;
  }

  /**
   * Sets the recording stream for this control instance.
   *
   * @param rStream the recording stream to set
   */
  void setStream(RecordingStream rStream) {
    this.rStream = rStream;
  }

  /**
   * Whether {@link #abort()} has been called, on any thread, during the current parse.
   *
   * @return {@code true} once the parse has been aborted
   */
  boolean isAborted() {
    return aborted.get();
  }

  @Override
  public void abort() {
    aborted.set(true);
  }

  /** {@inheritDoc} */
  @Override
  public Stream stream() {
    return stream;
  }

  @Override
  public ChunkInfo chunkInfo() {
    ChunkInfo ci = rStream != null ? rStream.getContext().get(ChunkInfo.class) : null;
    return ci != null ? ci : ChunkInfo.NONE;
  }
}

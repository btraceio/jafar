package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.internal_api.RecordingStream;

/** Implementation of Control interface that provides access to stream information. */
final class ControlImpl implements Control {
  private RecordingStream rStream;
  volatile boolean abortFlag = false;

  private final Stream stream =
      new Stream() {
        @Override
        public long position() {
          return rStream != null ? rStream.position() : -1;
        }
      };

  /**
   * Sets the recording stream for this control instance.
   *
   * @param rStream the recording stream to set
   */
  void setStream(RecordingStream rStream) {
    this.rStream = rStream;
  }

  @Override
  public void abort() {
    this.abortFlag = true;
  }

  /** {@inheritDoc} */
  @Override
  public Stream stream() {
    return stream;
  }

  @Override
  public ChunkInfo chunkInfo() {
    return rStream != null ? rStream.getContext().get(ChunkInfo.class) : ChunkInfo.NONE;
  }
}

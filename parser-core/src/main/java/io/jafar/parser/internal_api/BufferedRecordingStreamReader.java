package io.jafar.parser.internal_api;

import io.jafar.utils.CustomByteBuffer;
import java.nio.ByteBuffer;

/**
 * In-memory, buffer-backed {@link RecordingStreamReader}.
 *
 * <p>Wraps a caller-supplied {@code byte[]} or {@link ByteBuffer} so that JFR data streamed in from
 * a non-seekable source (e.g. a URL) can be parsed by the existing chunk parser machinery without
 * being persisted to a {@code Path}.
 *
 * <p>All read, slice, and position-tracking logic is inherited from {@link
 * RecordingStreamReader.BufferBackedRecordingStreamReader}; this subclass only owns construction
 * and overrides {@link #slice(long, long)} so that slices are themselves {@link
 * BufferedRecordingStreamReader} instances.
 */
public final class BufferedRecordingStreamReader
    extends RecordingStreamReader.BufferBackedRecordingStreamReader {

  /**
   * Wraps the given byte array.
   *
   * @param data the payload to read from; the reader does not copy this array
   */
  public BufferedRecordingStreamReader(byte[] data) {
    // ByteBuffer.wrap returns a BIG_ENDIAN buffer by default — the same order FileChannel.map()
    // produces — which is what the base class's reverse-on-read logic assumes for JFR payloads.
    this(new CustomByteBuffer.ByteBufferWrapper(ByteBuffer.wrap(data)), data.length, 0);
  }

  /**
   * Wraps the given {@link ByteBuffer}. The buffer's current position is treated as offset {@code
   * 0} of the reader; its remaining length becomes the reader's {@link #length()}.
   *
   * <p>The reader takes a {@link ByteBuffer#slice() slice} of the buffer, snapshotting its
   * position, limit, and byte order, but the underlying bytes remain shared with the caller.
   * Mutating the buffer's data after construction will be visible through the reader.
   *
   * @param buffer the source buffer
   */
  public BufferedRecordingStreamReader(ByteBuffer buffer) {
    this(new CustomByteBuffer.ByteBufferWrapper(buffer.slice()), buffer.remaining(), 0);
  }

  private BufferedRecordingStreamReader(
      CustomByteBuffer buffer, long length, int alignementOffset) {
    super(buffer, length, alignementOffset);
  }

  @Override
  public RecordingStreamReader slice() {
    long sliceLength = buffer.remaining();
    return new BufferedRecordingStreamReader(
        buffer.slice(), sliceLength, (int) (alignementOffset + buffer.position()) % 8);
  }

  @Override
  public RecordingStreamReader slice(long pos, long size) {
    return new BufferedRecordingStreamReader(
        buffer.slice(pos, size), size, (int) (alignementOffset + pos) % 8);
  }
}

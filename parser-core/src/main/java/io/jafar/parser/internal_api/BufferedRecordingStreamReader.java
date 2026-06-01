package io.jafar.parser.internal_api;

import io.jafar.utils.CustomByteBuffer;
import java.io.IOException;

/**
 * Implementation of {@link RecordingStreamReader} backed by a buffered byte array.
 *
 * <p>Used for parsing JFR data provided via {@link java.io.InputStream} where memory mapping is not
 * feasible. All read logic, byte-order reversal, and position tracking are inherited from {@link
 * RecordingStreamReader.BufferBackedRecordingStreamReader}; this subclass only owns construction
 * and {@link #slice} overrides.
 */
public final class BufferedRecordingStreamReader
    extends RecordingStreamReader.BufferBackedRecordingStreamReader {

  public BufferedRecordingStreamReader(CustomByteBuffer buffer) {
    this(buffer, buffer.limit(), 0);
  }

  public BufferedRecordingStreamReader(CustomByteBuffer buffer, long length, int alignmentOffset) {
    super(buffer, length, alignmentOffset);
  }

  @Override
  public RecordingStreamReader slice() {
    return new BufferedRecordingStreamReader(
        buffer.slice(), remaining(), (int) (alignementOffset + buffer.position()) % 8);
  }

  @Override
  public RecordingStreamReader slice(long pos, long size) {
    return new BufferedRecordingStreamReader(
        buffer.slice(pos, size), size, (int) (alignementOffset + pos) % 8);
  }

  @Override
  public void close() throws IOException {
    buffer.close();
  }
}

package io.jafar.parser.internal_api;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.api.ParserContext;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A stream for reading JFR recording data with position tracking and context management.
 *
 * <p>This class provides a high-level interface for reading JFR recording data, including support
 * for slicing, marking/resetting, and context management.
 */
public final class RecordingStream implements AutoCloseable {
  /** The underlying reader for the recording data. */
  private final RecordingStreamReader reader;

  /** The parser context associated with this stream. */
  private final ParserContext context;

  /** The marked position for reset operations. */
  private long mark = -1;

  /**
   * Constructs a new RecordingStream from a file path.
   *
   * @param path the path to the JFR recording file
   * @param context the parser context to use
   * @throws IOException if an I/O error occurs during construction
   */
  RecordingStream(Path path, ParserContext context) throws IOException {
    this(RecordingStreamReader.mapped(path), context);
  }

  /**
   * Creates a slice of this stream with the specified position and length.
   *
   * @param pos the starting position for the slice
   * @param len the length of the slice
   * @param context the parser context for the slice
   * @return a new RecordingStream representing the slice
   */
  public RecordingStream slice(long pos, long len, ParserContext context) {
    return new RecordingStream(reader.slice(pos, len), context);
  }

  /**
   * Constructs a new RecordingStream from a reader and context.
   *
   * @param reader the reader for the recording data
   * @param context the parser context to use
   */
  public RecordingStream(RecordingStreamReader reader, ParserContext context) {
    this.reader = reader;
    this.context = context;
    this.context.put(RecordingStream.class, this);
  }

  /**
   * Gets the parser context associated with this stream.
   *
   * @return the parser context
   */
  public ParserContext getContext() {
    return context;
  }

  /**
   * Sets the position in the stream.
   *
   * @param position the new position
   */
  public void position(long position) {
    reader.position(position);
  }

  /**
   * Gets the current position in the stream.
   *
   * @return the current position
   */
  public long position() {
    return reader.position();
  }

  /**
   * Reads bytes into the specified buffer.
   *
   * @param buffer the destination buffer
   * @param offset the starting offset in the buffer
   * @param length the number of bytes to read
   */
  public void read(byte[] buffer, int offset, int length) {
    if (available() < length) {
      throw new RuntimeException("unexpected EOF");
    }
    reader.read(buffer, offset, length);
  }

  /**
   * Reads a single byte from the stream.
   *
   * @return the byte value
   */
  public byte read() {
    return reader.read();
  }

  /**
   * Reads a short value from the stream.
   *
   * @return the short value
   */
  public short readShort() {
    return reader.readShort();
  }

  /**
   * Reads an int value from the stream.
   *
   * @return the int value
   */
  public int readInt() {
    return reader.readInt();
  }

  /**
   * Reads a long value from the stream.
   *
   * @return the long value
   */
  public long readLong() {
    return reader.readLong();
  }

  /**
   * Reads a float value from the stream.
   *
   * @return the float value
   */
  public float readFloat() {
    return reader.readFloat();
  }

  /**
   * Reads a double value from the stream.
   *
   * @return the double value
   */
  public double readDouble() {
    return reader.readDouble();
  }

  /**
   * Reads a variable-length integer from the stream.
   *
   * @return the long value
   */
  public long readVarint() {
    return reader.readVarint();
  }

  /**
   * Reads a boolean value from the stream.
   *
   * @return the boolean value
   */
  public boolean readBoolean() {
    return reader.readBoolean();
  }

  public String readUTF8() throws IOException {
    return ParsingUtils.readUTF8(this, context.getStringTypeId());
  }

  /**
   * Gets the number of bytes available for reading.
   *
   * @return the number of available bytes
   */
  public long available() {
    return reader.remaining();
  }

  /**
   * Skips the specified number of bytes.
   *
   * @param bytes the number of bytes to skip
   */
  public void skip(int bytes) {
    reader.skip(bytes);
  }

  /** Marks the current position for later reset. */
  public void mark() {
    mark = reader.position();
  }

  /** Resets the position to the previously marked position. */
  public void reset() {
    if (mark > -1) {
      position(mark);
      mark = -1;
    }
  }

  @Override
  public void close() {
    try {
      reader.close();
    } catch (IOException ignored) {
    }
  }
}

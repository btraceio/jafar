package io.jafar.utils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Custom byte buffer interface for efficient reading of JFR data.
 *
 * <p>This interface provides a unified abstraction for reading binary data from JFR recordings,
 * supporting both direct memory mapping and spliced reading for large files.
 */
public interface CustomByteBuffer {
  /**
   * Maps a file path to a custom byte buffer.
   *
   * @param channel the file path to map
   * @return a custom byte buffer for the file
   * @throws IOException if an I/O error occurs during mapping
   */
  static CustomByteBuffer map(Path channel) throws IOException {
    return map(channel, Integer.MAX_VALUE);
  }

  /**
   * Maps a file path to a custom byte buffer with a specified splice size.
   *
   * @param path the file path to map
   * @param spliceSize the maximum size for a single mapped buffer
   * @return a custom byte buffer for the file
   * @throws IOException if an I/O error occurs during mapping
   */
  static CustomByteBuffer map(Path path, int spliceSize) throws IOException {
    long size = Files.size(path);
    if (size > spliceSize) {
      return new SplicedMappedByteBuffer(path, spliceSize);
    } else {
      try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
          FileChannel channel = raf.getChannel()) {
        return new ByteBufferWrapper(channel.map(FileChannel.MapMode.READ_ONLY, 0, size));
      }
    }
  }

  /**
   * Creates a slice of this buffer from the current position to the end.
   *
   * @return a new custom byte buffer representing the slice
   */
  CustomByteBuffer slice();

  /**
   * Creates a slice of this buffer with the specified position and length.
   *
   * @param pos the starting position for the slice
   * @param len the length of the slice
   * @return a new custom byte buffer representing the slice
   */
  CustomByteBuffer slice(long pos, long len);

  /**
   * Sets the byte order for this buffer.
   *
   * @param bigEndian the byte order to set
   * @return this buffer for method chaining
   */
  CustomByteBuffer order(ByteOrder bigEndian);

  /**
   * Gets the current byte order of this buffer.
   *
   * @return the current byte order
   */
  ByteOrder order();

  /**
   * Checks if this buffer is using the native byte order.
   *
   * @return true if using native byte order, false otherwise
   */
  boolean isNativeOrder();

  /**
   * Sets the position of this buffer.
   *
   * @param position the new position
   */
  void position(long position);

  /**
   * Gets the current position of this buffer.
   *
   * @return the current position
   */
  long position();

  /**
   * Gets the number of remaining bytes in this buffer.
   *
   * @return the number of remaining bytes
   */
  long remaining();

  /**
   * Reads bytes into the specified buffer.
   *
   * @param buffer the destination buffer
   * @param offset the starting offset in the destination buffer
   * @param length the number of bytes to read
   */
  void get(byte[] buffer, int offset, int length);

  /**
   * Reads a single byte from this buffer.
   *
   * @return the byte value
   */
  byte get();

  /**
   * Reads a short value from this buffer.
   *
   * @return the short value
   */
  short getShort();

  /**
   * Reads an int value from this buffer.
   *
   * @return the int value
   */
  int getInt();

  /**
   * Reads a float value from this buffer.
   *
   * @return the float value
   */
  float getFloat();

  /**
   * Reads a double value from this buffer.
   *
   * @return the double value
   */
  double getDouble();

  /** Marks the current position in this buffer. */
  void mark();

  /** Resets the position to the previously marked position. */
  void reset();

  /**
   * Reads a long value from this buffer.
   *
   * @return the long value
   */
  long getLong();

  /**
   * Wrapper implementation of CustomByteBuffer using MappedByteBuffer.
   *
   * <p>This class provides a concrete implementation that delegates to a MappedByteBuffer while
   * maintaining the CustomByteBuffer interface contract.
   */
  class ByteBufferWrapper implements CustomByteBuffer {
    private final ByteBuffer delegate;
    private final boolean nativeOrder;

    /**
     * Constructs a new ByteBufferWrapper with the specified MappedByteBuffer.
     *
     * @param delegate the MappedByteBuffer to wrap
     */
    public ByteBufferWrapper(ByteBuffer delegate) {
      this.delegate = delegate;
      this.nativeOrder = delegate.order() == ByteOrder.nativeOrder();
      delegate.order(ByteOrder.nativeOrder());
    }

    @Override
    public boolean isNativeOrder() {
      return nativeOrder;
    }

    @Override
    public CustomByteBuffer slice(long pos, long len) {
      ByteBuffer dup = delegate.duplicate();
      dup.position((int) pos);
      dup.limit((int) (pos + len));
      return new ByteBufferWrapper(dup.slice());
    }

    @Override
    public CustomByteBuffer slice() {
      ByteBuffer dup = delegate.duplicate();
      return new ByteBufferWrapper(dup.slice());
    }

    @Override
    public CustomByteBuffer order(ByteOrder order) {
      delegate.order(order);
      return this;
    }

    @Override
    public ByteOrder order() {
      return delegate.order();
    }

    @Override
    public void position(long position) {
      delegate.position((int) position);
      //            this.position = (int) position;
    }

    @Override
    public long position() {
      return delegate.position(); // position;
    }

    @Override
    public long remaining() {
      return delegate.remaining(); // length - position;
    }

    @Override
    public void get(byte[] buffer, int offset, int length) {
      delegate.get(buffer, offset, length);
      //            delegate.get(position, buffer, offset, length);
      //            position += length;
    }

    @Override
    public byte get() {
      return delegate.get();
      //            return delegate.get(position++);
    }

    @Override
    public short getShort() {
      return delegate.getShort();
      //            short s = delegate.getShort(position);
      //            position += 2;
      //            return s;
    }

    @Override
    public int getInt() {
      return delegate.getInt();
      //            int i = delegate.getInt(position);
      //            position += 4;
      //            return i;
    }

    @Override
    public float getFloat() {
      return delegate.getFloat();
      //            float f = delegate.getFloat(position);
      //            position += 4;
      //            return f;
    }

    @Override
    public double getDouble() {
      return delegate.getDouble();
      //            double d = delegate.getDouble(position);
      //            position += 8;
      //            return d;
    }

    @Override
    public long getLong() {
      return delegate.getLong();
      //            long l = delegate.getLong(position);
      //            position += 8;
      //            return l;
    }

    @Override
    public void mark() {
      delegate.mark();
      //            mark = position;
    }

    @Override
    public void reset() {
      delegate.reset();
      //            if (mark > -1) {
      //                position = mark;
      //            }
    }
  }
}

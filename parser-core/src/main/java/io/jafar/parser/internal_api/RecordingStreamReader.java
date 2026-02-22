package io.jafar.parser.internal_api;

import io.jafar.utils.CustomByteBuffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Abstract base class for reading JFR recording data streams.
 *
 * <p>This class provides a unified interface for reading JFR recording data with support for
 * different data types, position tracking, and slicing operations. It handles byte order conversion
 * automatically and provides efficient varint decoding.
 */
public abstract class RecordingStreamReader {
  /**
   * Protected constructor for RecordingStreamReader.
   *
   * <p>This abstract class provides a unified interface for reading JFR recording data.
   */
  protected RecordingStreamReader() {}

  /**
   * Implementation of RecordingStreamReader that uses memory-mapped files.
   *
   * <p>This class provides efficient reading of JFR recording data by mapping the file into memory
   * and using optimized byte operations.
   */
  public static final class MappedRecordingStreamReader extends RecordingStreamReader {
    private final CustomByteBuffer buffer;
    private final long length;
    private final boolean nativeOrder;
    private final int alignementOffset;

    private long remaining;

    /**
     * Constructs a new MappedRecordingStreamReader for the specified file path.
     *
     * @param path the path to the JFR recording file
     * @throws IOException if an I/O error occurs during file mapping
     */
    public MappedRecordingStreamReader(Path path) throws IOException {
      this(CustomByteBuffer.map(path, Integer.MAX_VALUE), Files.size(path), 0);
    }

    /**
     * Constructs a new MappedRecordingStreamReader with the specified buffer and parameters.
     *
     * @param buffer the custom byte buffer to use for reading
     * @param length the total length of the data
     * @param alignementOffset the alignment offset for proper byte ordering
     */
    private MappedRecordingStreamReader(
        CustomByteBuffer buffer, long length, int alignementOffset) {
      this.buffer = buffer;
      this.length = length;
      this.nativeOrder = buffer.isNativeOrder();
      this.alignementOffset = alignementOffset;
      this.remaining = length;
    }

    /** {@inheritDoc} */
    @Override
    public RecordingStreamReader slice() {
      long sliceLength = buffer.remaining();
      return new MappedRecordingStreamReader(
          buffer.slice(), sliceLength, (int) (alignementOffset + buffer.position()) % 8);
    }

    /** {@inheritDoc} */
    @Override
    public RecordingStreamReader slice(long pos, long size) {
      return new MappedRecordingStreamReader(
          buffer.slice(pos, size), size, (int) (alignementOffset + pos) % 8);
    }

    /** {@inheritDoc} */
    @Override
    public long length() {
      return length;
    }

    /** {@inheritDoc} */
    @Override
    public long remaining() {
      return remaining;
    }

    /** {@inheritDoc} */
    @Override
    public long position() {
      return buffer.position();
    }

    /** {@inheritDoc} */
    @Override
    public void position(long newPosition) {
      remaining = length - newPosition;
      buffer.position(newPosition);
    }

    /** {@inheritDoc} */
    @Override
    public void skip(long n) {
      remaining -= n;
      buffer.position(buffer.position() + n);
    }

    /** {@inheritDoc} */
    @Override
    public byte read() {
      remaining--;
      return buffer.get();
    }

    /** {@inheritDoc} */
    @Override
    public void read(byte[] b, int off, int len) {
      remaining -= len;
      buffer.get(b, off, len);
    }

    /** {@inheritDoc} */
    @Override
    public boolean readBoolean() {
      remaining--;
      return buffer.get() != 0;
    }

    /** {@inheritDoc} */
    @Override
    public short readShort() {
      remaining -= 2;
      short s = buffer.getShort();
      return nativeOrder ? s : Short.reverseBytes(s);
    }

    /** {@inheritDoc} */
    @Override
    public int readInt() {
      remaining -= 4;
      int i = buffer.getInt();
      return nativeOrder ? i : Integer.reverseBytes(i);
    }

    /** {@inheritDoc} */
    @Override
    public long readLong() {
      remaining -= 8;
      long l = buffer.getLong();
      return nativeOrder ? l : Long.reverseBytes(l);
    }

    /**
     * Reverses the byte order of a float value.
     *
     * @param f the float value to reverse
     * @return the float value with reversed byte order
     */
    private static float reverseBytes(float f) {
      int i = Float.floatToRawIntBits(f);
      return Float.intBitsToFloat(Integer.reverseBytes(i));
    }

    /**
     * Reverses the byte order of a double value.
     *
     * @param d the double value to reverse
     * @return the double value with reversed byte order
     */
    private static double reverseBytes(double d) {
      long l = Double.doubleToRawLongBits(d);
      return Double.longBitsToDouble(Long.reverseBytes(l));
    }

    /** {@inheritDoc} */
    @Override
    public float readFloat() {
      remaining -= 4;
      float f = buffer.getFloat();
      return nativeOrder ? f : reverseBytes(f);
    }

    /** {@inheritDoc} */
    @Override
    public double readDouble() {
      remaining -= 8;
      double d = buffer.getDouble();
      return nativeOrder ? d : reverseBytes(d);
    }

    /**
     * Finds the first byte position where the 8th bit is unset.
     *
     * @param value the 64-bit value to check
     * @return the position of the first unset 8th bit
     */
    private static int findFirstUnset8thBit(long value) {
      // Step 1: Mask out the 8th bits of each byte
      long mask = 0x8080808080808080L;
      long eighthBits = value & mask;

      // Step 2: Identify which bytes have the 8th bit unset
      long unsetBits = (~eighthBits) & mask;

      // Step 3: Collapse each byte to a single bit
      long collapsed = unsetBits * 0x0101010101010101L;

      // Step 4: Find the first unset byte
      return Long.numberOfTrailingZeros(collapsed) / 8;
    }

    /** {@inheritDoc} */
    @Override
    public long readVarint() {
      return readVarintSeq();
    }

    private long readVarintSeq() {
      byte b0 = buffer.get();
      remaining--;
      long ret = (b0 & 0x7FL);
      if (b0 >= 0) {
        return ret;
      }
      int b1 = buffer.get();
      remaining--;
      ret += (b1 & 0x7FL) << 7;
      if (b1 >= 0) {
        return ret;
      }
      int b2 = buffer.get();
      remaining--;
      ret += (b2 & 0x7FL) << 14;
      if (b2 >= 0) {
        return ret;
      }
      int b3 = buffer.get();
      remaining--;
      ret += (b3 & 0x7FL) << 21;
      if (b3 >= 0) {
        return ret;
      }
      int b4 = buffer.get();
      remaining--;
      ret += (b4 & 0x7FL) << 28;
      if (b4 >= 0) {
        return ret;
      }
      int b5 = buffer.get();
      remaining--;
      ret += (b5 & 0x7FL) << 35;
      if (b5 >= 0) {
        return ret;
      }
      int b6 = buffer.get();
      remaining--;
      ret += (b6 & 0x7FL) << 42;
      if (b6 >= 0) {
        return ret;
      }
      int b7 = buffer.get();
      remaining--;
      ret += (b7 & 0x7FL) << 49;
      if (b7 >= 0) {
        return ret;
      }
      int b8 = buffer.get(); // read last byte raw
      remaining--;
      return ret + (((long) (b8 & 0XFF)) << 56);
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {}
  }

  /**
   * Creates a slice of this reader starting from the current position.
   *
   * @return a new RecordingStreamReader representing the slice
   */
  public abstract RecordingStreamReader slice();

  /**
   * Creates a slice of this reader with the specified position and size.
   *
   * @param pos the starting position for the slice
   * @param size the size of the slice
   * @return a new RecordingStreamReader representing the slice
   */
  public abstract RecordingStreamReader slice(long pos, long size);

  /**
   * Gets the total length of the data stream.
   *
   * @return the total length in bytes
   */
  public abstract long length();

  /**
   * Gets the number of bytes remaining to be read.
   *
   * @return the number of remaining bytes
   */
  public abstract long remaining();

  /**
   * Gets the current position in the data stream.
   *
   * @return the current position in bytes
   */
  public abstract long position();

  /**
   * Sets the position in the data stream.
   *
   * @param newPosition the new position to set
   */
  public abstract void position(long newPosition);

  /**
   * Skips the specified number of bytes.
   *
   * @param n the number of bytes to skip
   */
  public abstract void skip(long n);

  /**
   * Reads a single byte from the stream.
   *
   * @return the byte value read
   */
  public abstract byte read();

  /**
   * Reads bytes into the specified array.
   *
   * @param b the byte array to read into
   * @param off the starting offset in the array
   * @param len the number of bytes to read
   */
  public abstract void read(byte[] b, int off, int len);

  /**
   * Reads a boolean value from the stream.
   *
   * @return the boolean value read
   */
  public abstract boolean readBoolean();

  /**
   * Reads a short value from the stream.
   *
   * @return the short value read
   */
  public abstract short readShort();

  /**
   * Reads an int value from the stream.
   *
   * @return the int value read
   */
  public abstract int readInt();

  /**
   * Reads a long value from the stream.
   *
   * @return the long value read
   */
  public abstract long readLong();

  /**
   * Reads a float value from the stream.
   *
   * @return the float value read
   */
  public abstract float readFloat();

  /**
   * Reads a double value from the stream.
   *
   * @return the double value read
   */
  public abstract double readDouble();

  /**
   * Reads a varint (variable-length integer) from the stream.
   *
   * @return the varint value read
   */
  public abstract long readVarint();

  /**
   * Closes the reader and releases any associated resources.
   *
   * @throws IOException if an I/O error occurs during closing
   */
  public abstract void close() throws IOException;

  /**
   * Creates a new RecordingStreamReader for the specified file path.
   *
   * @param path the path to the JFR recording file
   * @return a new RecordingStreamReader instance
   * @throws IOException if an I/O error occurs during file mapping
   */
  public static RecordingStreamReader mapped(Path path) throws IOException {
    return new MappedRecordingStreamReader(path);
  }
}

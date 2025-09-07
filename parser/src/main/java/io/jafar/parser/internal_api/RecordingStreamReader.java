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

    private static final boolean VARINT_FROM_LONG =
        Boolean.getBoolean("io.jafar.parser.varint_from_long");

    /** {@inheritDoc} */
    @Override
    public long readVarint() {
      if (VARINT_FROM_LONG) {
        // TODO: Experimental - tries optimizing varint decoding by loading 8 bytes at once
        // So far it looks this is actually slowing down the decoding, but I will leave the code
        // here so it can be revisited later
        // The guard flag is false, unless a system property is provided so the condition will
        // be elided
        long pos = checkVarintFromLongPos();
        if (pos > -1) {
          return readVarintFromLong(pos);
        }
      }
      //            long pos = buffer.position();
      //            long r1 = readVarintSeq();
      //            long r1pos = buffer.position();
      //            buffer.position(pos);
      //            long r2 = readVarintSwar();
      //            long r2pos = buffer.position();
      //            if (r1pos != r2pos) {
      //                throw new RuntimeException("varint pos mismatch: " + r1pos + " != " +
      // r2pos);
      //            }
      //            if (r1 != r2) {
      //                throw new RuntimeException("varint mismatch: " + r1 + " != " + r2);
      //            }
      //            return r1;
      return readVarintSwar();
      //            return readVarintSeq();
    }

    /**
     * Reads a varint using SWAR (SIMD Within A Register) optimization.
     *
     * @return the decoded varint value
     */
    long readVarintSwar() {
      if (remaining < 9) return readVarintSeq();

      long basePos = buffer.position();
      //            System.err.println("===> basePos: " + basePos);
      long lo = buffer.getLong();
      int hi = buffer.get() & 0xFF;
      remaining -= 9;

      // Compute continuation bitmap (1 bit per byte)
      int contBits =
          ((int) ((lo >>> 7) & 1) << 0)
              | ((int) ((lo >>> 15) & 1) << 1)
              | ((int) ((lo >>> 23) & 1) << 2)
              | ((int) ((lo >>> 31) & 1) << 3)
              | ((int) ((lo >>> 39) & 1) << 4)
              | ((int) ((lo >>> 47) & 1) << 5)
              | ((int) ((lo >>> 55) & 1) << 6)
              | ((int) ((lo >>> 63) & 1) << 7);
      //                            | ((hi >>> 7)  & 1)     << 8;

      int stopByte = Integer.numberOfTrailingZeros(~contBits);
      if (stopByte >= 9) throw new RuntimeException("malformed varint");

      long b0 = (lo >>> (0 * 8)) & 0xFF;
      long b1 = (lo >>> (1 * 8)) & 0xFF;
      long b2 = (lo >>> (2 * 8)) & 0xFF;
      long b3 = (lo >>> (3 * 8)) & 0xFF;
      long b4 = (lo >>> (4 * 8)) & 0xFF;
      long b5 = (lo >>> (5 * 8)) & 0xFF;
      long b6 = (lo >>> (6 * 8)) & 0xFF;
      long b7 = (lo >>> (7 * 8)) & 0xFF;
      long b8 = hi & 0xFF;
      ;

      long result =
          (b0 & 0x7F)
              | ((b1 & 0x7F) << 7)
              | ((b2 & 0x7F) << 14)
              | ((b3 & 0x7F) << 21)
              | ((b4 & 0x7F) << 28)
              | ((b5 & 0x7F) << 35)
              | ((b6 & 0x7F) << 42)
              | ((b7 & 0x7F) << 49)
              | ((b8 & 0xFF) << 56);

      buffer.position(basePos + stopByte + 1);
      remaining += 8 - stopByte;

      // Mask result to cut off unused high bits
      // (Only the stopByte-th byte is unmasked)
      if (stopByte < 8) {
        return result & ((1L << ((stopByte + 1) * 7)) - 1);
      }
      return result;
    }

    long readRawVarint64() {
      if (buffer.remaining() < 8) {
        return readRawVarint56();
      }
      long basePos = buffer.position();
      long wholeOrMore =
          ((long) buffer.get() & 0xFF)
              | (((long) buffer.get() & 0xFF) << 8)
              | (((long) buffer.get() & 0xFF) << 16)
              | (((long) buffer.get() & 0xFF) << 24)
              | (((long) buffer.get() & 0xFF) << 32)
              | (((long) buffer.get() & 0xFF) << 40)
              | (((long) buffer.get() & 0xFF) << 48)
              | (((long) buffer.get() & 0xFF) << 56);

      long firstOneOnStop = ~wholeOrMore & 0x8080808080808080L;
      if (firstOneOnStop == 0) {
        if (buffer.remaining() < 1) {
          throw new RuntimeException("malformed varint.");
        }
        byte lastByte = buffer.get();
        if (lastByte < 0) {
          throw new RuntimeException("malformed varint.");
        }
        return (wholeOrMore & 0x7FL)
            | ((wholeOrMore >> 8) & 0x7FL) << 7
            | ((wholeOrMore >> 16) & 0x7FL) << 14
            | ((wholeOrMore >> 24) & 0x7FL) << 21
            | ((wholeOrMore >> 32) & 0x7FL) << 28
            | ((wholeOrMore >> 40) & 0x7FL) << 35
            | ((wholeOrMore >> 48) & 0x7FL) << 42
            | ((wholeOrMore >> 56) & 0x7FL) << 49
            | ((long) lastByte) << 56;
      }

      int bitsToKeep = Long.numberOfTrailingZeros(firstOneOnStop) + 1;
      int toSkip = bitsToKeep >> 3;
      buffer.position((int) basePos + toSkip + 8); // +8 to account for the 8 get()s
      long thisVarintMask = firstOneOnStop ^ (firstOneOnStop - 1);
      long wholeWithContinuations = wholeOrMore & thisVarintMask;

      wholeWithContinuations =
          (wholeWithContinuations & 0x7F007F007F007F00L) >>> 1
              | (wholeWithContinuations & 0x007F007F007F007FL);

      wholeWithContinuations =
          (wholeWithContinuations & 0x00003FFF00003FFFL) >>> 2
              | (wholeWithContinuations & 0x3FFF00003FFF0000L);

      wholeWithContinuations =
          (wholeWithContinuations & 0x00000000001FFFFFL)
              | ((wholeWithContinuations >>> 3) & 0x0000001FFFFF0000L)
              | ((wholeWithContinuations >>> 6) & 0x1FFF000000000000L);

      return wholeWithContinuations;
    }

    private long readRawVarint56() {
      if (buffer.remaining() < 4) {
        if (buffer.remaining() == 0) {
          return 0;
        }
        buffer.mark();
        byte tmp = buffer.get();
        if (tmp >= 0) {
          return tmp;
        }
        long result = tmp & 127;
        if (buffer.remaining() <= 0) {
          buffer.reset();
          return 0;
        }
        if ((tmp = buffer.get()) >= 0) {
          return result | ((long) tmp << 7);
        }
        result |= (long) (tmp & 127) << 7;
        if (buffer.remaining() <= 0) {
          buffer.reset();
          return 0;
        }
        if ((tmp = buffer.get()) >= 0) {
          return result | ((long) tmp << 14);
        }
        return result | ((long) (tmp & 127)) << 14;
      }

      long basePos = buffer.position();
      int wholeOrMore = buffer.getInt();
      int firstOneOnStop = ~wholeOrMore & 0x80808080;
      if (firstOneOnStop == 0) {
        if (buffer.remaining() <= 1) {
          throw new RuntimeException("malformed varint.");
        }
        byte lastByte = buffer.get();
        if (lastByte < 0) {
          throw new RuntimeException("malformed varint.");
        }
        return (wholeOrMore & 0x7F)
            | ((wholeOrMore >> 8) & 0x7F) << 7
            | ((wholeOrMore >> 16) & 0x7F) << 14
            | ((wholeOrMore >> 24) & 0x7F) << 21
            | ((long) lastByte) << 28;
      }

      int bitsToKeep = Integer.numberOfTrailingZeros(firstOneOnStop) + 1;
      int toSkip = bitsToKeep >> 3;
      buffer.position((int) basePos + toSkip);
      int thisVarintMask = firstOneOnStop ^ (firstOneOnStop - 1);
      int wholeWithContinuations = wholeOrMore & thisVarintMask;

      wholeWithContinuations =
          (wholeWithContinuations & 0x7F007F) | ((wholeWithContinuations & 0x7F007F00) >> 1);
      return (wholeWithContinuations & 0x3FFF) | ((wholeWithContinuations & 0x3FFF0000L) >> 2);
    }

    private long checkVarintFromLongPos() {
      long pos = buffer.position();
      if (((pos + alignementOffset) & 7) == 0) {
        if (remaining >= 8) {
          return pos;
        }
      }
      return -1;
    }

    private long readVarintFromLong(long pos) {
      long value = buffer.getLong();

      int parts = findFirstUnset8thBit(value) + 1;
      long l = value;
      if (parts < 8) {
        long mask = (0XFFFFFFFFFFFFFFFFL >>> (8 - parts) * 8);
        l = l & mask;
      }

      long extracted = l & 0x7F7F7F7F7F7F7F7FL; // Extract lower 7 bits
      long result =
          ((extracted & 0x000000000000007FL))
              | ((extracted & 0x0000000000007F00L) >> 1)
              | ((extracted & 0x00000000007F0000L) >> 2)
              | ((extracted & 0x000000007F000000L) >> 3)
              | ((extracted & 0x0000007F00000000L) >> 4)
              | ((extracted & 0x00007F0000000000L) >> 5)
              | ((extracted & 0x007F000000000000L) >> 6)
              | ((extracted & 0x7F00000000000000L) >> 7);

      if (parts == 9) {
        byte b = buffer.get();
        result |= (b & 0x7FL) << 56;
      } else {
        position(pos + parts);
      }
      remaining -= parts;
      return result;
    }

    long readVarintSimd() {
      if (remaining < 10) {
        return readVarintSeq();
      }

      long basePos = buffer.position();
      long first8 = buffer.getLong();
      short last2 = buffer.getShort();
      remaining -= 10;

      // Detect stop byte
      for (int i = 0; i < 8; i++) {
        if (((first8 >>> (i * 8)) & 0x80) == 0) {
          long result = 0;
          for (int j = 0, shift = 0; j <= i; j++, shift += 7) {
            result |= ((first8 >>> (j * 8)) & 0x7FL) << shift;
          }
          buffer.position(basePos + i + 1);
          remaining += 9 - i;
          return result;
        }
      }

      int b8 = last2 & 0xFF;
      if ((b8 & 0x80) == 0) {
        long result = 0;
        for (int j = 0, shift = 0; j < 8; j++, shift += 7) {
          result |= ((first8 >>> (j * 8)) & 0x7FL) << shift;
        }
        result |= ((long) b8) << 56;
        buffer.position(basePos + 9);
        remaining += 0;
        return result;
      }

      int b9 = (last2 >>> 8) & 0xFF;
      // 10th byte has no continuation bit
      long result = 0;
      for (int j = 0, shift = 0; j < 9; j++, shift += 7) {
        long b = (j < 8) ? ((first8 >>> (j * 8)) & 0x7FL) : (b8 & 0x7FL);
        result |= b << shift;
      }
      result |= ((long) b9) << 63;

      buffer.position(basePos + 10);
      return result;
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

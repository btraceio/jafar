package io.jafar.hdump.internal;

import io.jafar.utils.CustomByteBuffer;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Low-level reader for HPROF binary format. Uses memory-mapped I/O for efficient access to large
 * heap dumps.
 *
 * <p>HPROF file format:
 *
 * <pre>
 * Header:
 *   [u1]* - null-terminated format string (e.g., "JAVA PROFILE 1.0.2")
 *   u4    - identifier size (4 or 8)
 *   u8    - timestamp (milliseconds since epoch)
 *
 * Records (repeated):
 *   u1    - tag
 *   u4    - timestamp offset (microseconds)
 *   u4    - record length
 *   [u1]* - record body
 * </pre>
 */
public final class HprofReader implements Closeable {

  private static final String FORMAT_PREFIX = "JAVA PROFILE ";
  private static final int SPLICE_SIZE = 256 * 1024 * 1024; // 256MB splices

  private final Path path;
  private final CustomByteBuffer buffer;
  private final String formatVersion;
  private final int idSize;
  private final long timestamp;
  private final int headerSize;

  /**
   * Opens an HPROF file for reading.
   *
   * @param path path to the HPROF file
   * @throws IOException if the file cannot be read or has invalid format
   */
  public HprofReader(Path path) throws IOException {
    this.path = path;

    // Use SplicedMappedByteBuffer to handle files of any size
    this.buffer = CustomByteBuffer.map(path, SPLICE_SIZE);
    this.buffer.order(ByteOrder.BIG_ENDIAN); // HPROF is always big-endian

    // Parse header
    this.formatVersion = readFormatString();
    this.idSize = buffer.getInt();
    if (idSize != 4 && idSize != 8) {
      throw new IOException("Invalid identifier size: " + idSize);
    }
    this.timestamp = buffer.getLong();
    this.headerSize = (int) buffer.position();
  }

  private String readFormatString() throws IOException {
    StringBuilder sb = new StringBuilder();
    byte b;
    while ((b = buffer.get()) != 0) {
      sb.append((char) b);
    }
    String format = sb.toString();
    if (!format.startsWith(FORMAT_PREFIX)) {
      throw new IOException("Invalid HPROF format: " + format);
    }
    return format.substring(FORMAT_PREFIX.length());
  }

  /** Returns the format version string (e.g., "1.0.2"). */
  public String getFormatVersion() {
    return formatVersion;
  }

  /** Returns the size of object identifiers (4 or 8 bytes). */
  public int getIdSize() {
    return idSize;
  }

  /** Returns the timestamp when the dump was created (milliseconds since epoch). */
  public long getTimestamp() {
    return timestamp;
  }

  /** Returns the file path. */
  public Path getPath() {
    return path;
  }

  /** Returns the total file size in bytes. */
  public long getFileSize() {
    return buffer.limit();
  }

  /** Resets the reader to the beginning of the records (after the header). */
  public void reset() {
    buffer.position(headerSize);
  }

  /** Returns whether there are more records to read. */
  public boolean hasMoreRecords() {
    return buffer.remaining() > 0;
  }

  /** Returns the current position in the file. */
  public long position() {
    return buffer.position();
  }

  /** Sets the current position in the file. */
  public void position(long pos) {
    buffer.position(pos);
  }

  /**
   * Reads the next record header.
   *
   * @return the record header, or null if end of file
   */
  public RecordHeader readRecordHeader() {
    if (buffer.remaining() < 9) {
      return null; // Not enough data for a header
    }
    int tag = buffer.get() & 0xFF;
    int timeOffset = buffer.getInt();
    int length = buffer.getInt();
    return new RecordHeader(tag, timeOffset, length, buffer.position());
  }

  /** Skips the body of the current record (assumes header was just read). */
  public void skipRecordBody(RecordHeader header) {
    buffer.position(header.bodyPosition() + header.length());
  }

  /** Reads a single byte. */
  public int readU1() {
    return buffer.get() & 0xFF;
  }

  /** Reads a 2-byte unsigned integer. */
  public int readU2() {
    return buffer.getShort() & 0xFFFF;
  }

  /** Reads a 4-byte signed integer. */
  public int readI4() {
    return buffer.getInt();
  }

  /** Reads a 4-byte unsigned integer as long. */
  public long readU4() {
    return buffer.getInt() & 0xFFFFFFFFL;
  }

  /** Reads an 8-byte signed integer. */
  public long readI8() {
    return buffer.getLong();
  }

  /** Reads an object ID (4 or 8 bytes depending on idSize). */
  public long readId() {
    return idSize == 4 ? readU4() : readI8();
  }

  /** Reads a float value. */
  public float readFloat() {
    return buffer.getFloat();
  }

  /** Reads a double value. */
  public double readDouble() {
    return buffer.getDouble();
  }

  /** Reads raw bytes into the given array. */
  public void readBytes(byte[] dest) {
    buffer.get(dest, 0, dest.length);
  }

  /** Reads a value of the given basic type. */
  public Object readValue(int basicType) {
    return switch (basicType) {
      case BasicType.OBJECT -> readId();
      case BasicType.BOOLEAN -> readU1() != 0;
      case BasicType.BYTE -> (byte) readU1();
      case BasicType.CHAR -> (char) readU2();
      case BasicType.SHORT -> (short) readU2();
      case BasicType.INT -> readI4();
      case BasicType.LONG -> readI8();
      case BasicType.FLOAT -> readFloat();
      case BasicType.DOUBLE -> readDouble();
      default -> throw new IllegalArgumentException("Unknown basic type: " + basicType);
    };
  }

  /** Skips bytes in the buffer. */
  public void skip(int bytes) {
    buffer.position(buffer.position() + bytes);
  }

  @Override
  public void close() throws IOException {
    buffer.close();
  }

  /** Record header information. */
  public record RecordHeader(int tag, int timeOffset, int length, long bodyPosition) {
    public String tagName() {
      return HprofTag.nameOf(tag);
    }
  }
}

package io.jafar.shell.core.proto;

import java.io.IOException;

/** Low-level protobuf binary decoding utilities shared by pprof and OTLP profile readers. */
public final class ProtoUtil {

  private ProtoUtil() {}

  // ---- Wire types ----

  public static final int WIRE_VARINT = 0;
  public static final int WIRE_I64 = 1;
  public static final int WIRE_LEN = 2;
  public static final int WIRE_I32 = 5;

  // ---- Decoding methods ----

  /** Reads a varint (up to 64 bits) from {@code buf[pos]}. */
  public static long readVarint(byte[] buf, int pos) throws IOException {
    long result = 0;
    int shift = 0;
    while (true) {
      if (pos >= buf.length)
        throw new IOException("Truncated protobuf: varint extends past buffer end");
      byte b = buf[pos++];
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) break;
      shift += 7;
      if (shift > 63) throw new IOException("Malformed protobuf: varint exceeds 64 bits");
    }
    return result;
  }

  /** Reads a varint length field and validates it fits in a non-negative {@code int}. */
  public static int readSafeLen(byte[] buf, int pos) throws IOException {
    long raw = readVarint(buf, pos);
    if (raw < 0 || raw > Integer.MAX_VALUE) {
      throw new IOException("Protobuf field length out of range: " + raw);
    }
    return (int) raw;
  }

  /** Returns the byte length of the varint at {@code buf[pos]}. */
  public static int varintLen(byte[] buf, int pos) throws IOException {
    int len = 1;
    while (true) {
      if (pos >= buf.length)
        throw new IOException("Truncated protobuf: varint extends past buffer end");
      if ((buf[pos++] & 0x80) == 0) break;
      len++;
      if (len > 10) throw new IOException("Malformed protobuf: varint exceeds 64 bits");
    }
    return len;
  }

  /** Reads a little-endian 64-bit fixed value from {@code buf[pos]}. */
  public static long readFixed64(byte[] buf, int pos) throws IOException {
    if (pos + 8 > buf.length)
      throw new IOException("Truncated protobuf: fixed64 extends past buffer end");
    long result = 0;
    for (int i = 0; i < 8; i++) {
      result |= (long) (buf[pos + i] & 0xFF) << (8 * i);
    }
    return result;
  }

  /** Skips a field of the given wire type; returns the new position after the field. */
  public static int skipField(byte[] buf, int pos, int wireType) throws IOException {
    return switch (wireType) {
      case WIRE_VARINT -> pos + varintLen(buf, pos);
      case WIRE_I64 -> {
        if (pos + 8 > buf.length)
          throw new IOException("Truncated protobuf: fixed64 extends past buffer end");
        yield pos + 8;
      }
      case WIRE_LEN -> {
        int lenBytes = varintLen(buf, pos);
        int len = readSafeLen(buf, pos);
        yield pos + lenBytes + len;
      }
      case WIRE_I32 -> {
        if (pos + 4 > buf.length)
          throw new IOException("Truncated protobuf: fixed32 extends past buffer end");
        yield pos + 4;
      }
      default -> throw new IOException("Cannot skip unknown wire type " + wireType);
    };
  }
}

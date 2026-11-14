package io.jafar.parser;

import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ConstantPools;
import io.jafar.parser.api.MetadataLookup;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for parsing operations in JFR recordings.
 *
 * <p>This class provides static utility methods for common parsing operations such as string
 * handling and byte array processing.
 */
public final class ParsingUtils {
  /**
   * Public constructor for ParsingUtils.
   *
   * <p>This class provides utility methods for parsing operations in JFR recordings.
   */
  public ParsingUtils() {}

  /**
   * Converts a byte array to a string representation.
   *
   * @param array the byte array to convert
   * @param offset the starting offset in the array
   * @param len the number of bytes to convert
   * @return a string representation of the byte array
   */
  public static String bytesToString(byte[] array, int offset, int len) {
    StringBuilder sb = new StringBuilder("[");
    boolean comma = false;
    for (int i = 0; i < len; i++) {
      if (comma) {
        sb.append(", ");
      } else {
        comma = true;
      }
      sb.append(array[i + offset]);
    }
    sb.append(']');
    return sb.toString();
  }

  /**
   * Reads a UTF-8 encoded string from the recording stream.
   *
   * <p>This method handles various string encoding formats used in JFR recordings: - ID 0: null
   * string - ID 1: empty string - ID 2: string constant reference - ID 3: UTF-8 encoded string - ID
   * 4: UTF-16 encoded string - ID 5: LATIN1 encoded string
   *
   * @param stream the recording stream to read from
   * @return the decoded string, or null if the string is null
   * @throws IOException if an I/O error occurs during reading
   */
  public static String readUTF8(RecordingStream stream, long stringTypeId) throws IOException {
    byte id = stream.read();
    if (id == 0) {
      return null;
    } else if (id == 1) {
      return "";
    } else if (id == 2) {
      // string constant reference
      int ptr = (int) stream.readVarint();

      // Try constant pool first (for JMC Writer compatibility)
      // JMC Writer stores string field values in java.lang.String constant pools
      // instead of adding them to the metadata string table
      ConstantPools constantPools = stream.getContext().getConstantPools();
      if (stringTypeId == -1) {
        // lookup from metadata string constants
        return metadataString(stream.getContext().getMetadataLookup(), ptr);
      }
      ConstantPool stringPool =
          constantPools != null ? constantPools.getConstantPool(stringTypeId) : null;
      if (stringPool == null) {
        throw new IOException(
            "String constant " + ptr + " referenced, but the chunk has no java.lang.String pool");
      }
      Object value = stringPool.get(ptr);
      if (value instanceof String) {
        return (String) value;
      }
      // A dangling reference used to read as null, indistinguishable from a null string; it means
      // a corrupted recording (or a pool that could not be indexed), so say so.
      throw new IOException(
          value == null
              ? "String constant " + ptr + " not found in the java.lang.String pool"
              : "String constant " + ptr + " is not a string: " + value.getClass().getName());
    } else if (id == 3) {
      // UTF8
      int size = byteLength(stream);
      if (size == 0) {
        return "";
      }
      byte[] content =
          size <= stream.getContext().byteBuffer.length
              ? stream.getContext().byteBuffer
              : new byte[size];
      stream.read(content, 0, size);
      return stream.getContext().utf8Parser.parse(content, size, StandardCharsets.UTF_8);
    } else if (id == 4) {
      int size = charCount(stream);
      if (size == 0) {
        return "";
      }
      char[] chars =
          size <= stream.getContext().charBuffer.length
              ? stream.getContext().charBuffer
              : new char[size];
      for (int i = 0; i < size; i++) {
        chars[i] = (char) stream.readVarint();
      }
      return stream.getContext().charParser.parse(chars, size);
    } else if (id == 5) {
      // LATIN1
      int size = byteLength(stream);
      if (size == 0) {
        return "";
      }
      byte[] content =
          size <= stream.getContext().byteBuffer.length
              ? stream.getContext().byteBuffer
              : new byte[size];
      stream.read(content, 0, size);
      return stream.getContext().utf8Parser.parse(content, size, StandardCharsets.ISO_8859_1);
    } else {
      throw new IOException("Unexpected string constant id: " + id);
    }
  }

  /**
   * Reads the byte length of a UTF-8 or Latin-1 string payload and checks it against the data that
   * is left.
   *
   * <p>The length is a varint from untrusted input. Unchecked, a corrupted one allocates a buffer
   * of that size before the read finds there is nothing behind it, and one past {@code
   * Integer.MAX_VALUE} narrows to a negative array size.
   */
  private static int byteLength(RecordingStream stream) throws IOException {
    long size = stream.readVarint();
    long left = stream.available();
    if (size < 0 || size > left) {
      throw new IOException(
          "String of " + size + " byte(s) exceeds the remaining " + left + " byte(s)");
    }
    return (int) size;
  }

  /**
   * Reads the entry count of a char-array string and checks it against the data that is left: each
   * char is a varint of at least one byte.
   */
  private static int charCount(RecordingStream stream) throws IOException {
    long size = stream.readVarint();
    long left = stream.available();
    if (size < 0 || size > left) {
      throw new IOException(
          "Char array of " + size + " entries exceeds the remaining " + left + " byte(s)");
    }
    return (int) size;
  }

  /**
   * Reads the length of an array field and checks it against the data that is left.
   *
   * <p>The length is a varint from untrusted input, and consumers size their storage from it. No
   * honest array is longer than the bytes remaining in its chunk; the Go parser applies the same
   * bound ("implausible array length").
   *
   * @param stream the recording stream, positioned at the array length
   * @param owner the type declaring the field
   * @param field the array field
   * @return the array length
   * @throws IOException if the length is negative or exceeds the remaining data
   */
  public static int arrayLength(RecordingStream stream, MetadataClass owner, MetadataField field)
      throws IOException {
    long length = stream.readVarint();
    if (length < 0 || length > stream.available()) {
      throw new IOException(
          owner.getName() + "." + field.getName() + ": implausible array length " + length);
    }
    return (int) length;
  }

  /**
   * Looks up a metadata string table entry on behalf of recording data.
   *
   * <p>{@link MetadataLookup#getString(int)} rejects an index outside the table with an {@code
   * IllegalArgumentException}, which is right for a caller passing a bad argument. Here the index
   * was read from the recording, so it is the recording that is wrong.
   *
   * @param lookup the chunk's metadata lookup
   * @param index the string table index read from the recording
   * @return the string
   * @throws IOException if the index is outside the string table
   */
  public static String metadataString(MetadataLookup lookup, int index) throws IOException {
    try {
      return lookup.getString(index);
    } catch (IllegalArgumentException e) {
      throw new IOException(e.getMessage());
    }
  }

  /**
   * Skips over a UTF-8 encoded string in the recording stream without decoding it.
   *
   * <p>This method is useful when the string content is not needed and only the position in the
   * stream needs to be advanced.
   *
   * @param stream the recording stream to skip over
   * @throws IOException if an I/O error occurs during skipping, or the encoding id is not one JFR
   *     defines (0 to 5)
   */
  public static void skipUTF8(RecordingStream stream) throws IOException {
    byte id = stream.read();
    switch (id) {
      case 3:
      case 5:
        {
          stream.skip(byteLength(stream));
          break;
        }
      case 4:
        {
          int size = charCount(stream);
          for (int i = 0; i < size; i++) {
            stream.readVarint();
          }
          break;
        }
      case 2:
        {
          stream.readVarint();
          break;
        }
      case 0: // null
      case 1: // empty
        break;
      default:
        // Not "no payload": an unknown encoding has a payload of unknown length, and skipping just
        // the id byte would misalign everything after it - every later constant pool entry when
        // indexing a pool, the rest of the value when skipping a field (Verification.md R3).
        throw new IOException("Unexpected string constant id: " + id);
    }
  }

  private static final long POW10_8 = 100_000_000L;

  public static long parseLongSWAR(CharSequence s) {
    int n = s.length();
    if (n == 0) throw nfe(s);

    int i = 0;
    boolean neg = false;
    char c0 = s.charAt(0);
    if (c0 == '+' || c0 == '-') {
      neg = c0 == '-';
      i++;
      if (i == n) throw nfe(s);
    }

    // skip leading zeros (keeps overflow checks simple)
    while (i < n && s.charAt(i) == '0') i++;
    int start = i;
    int digits = n - start;
    if (digits == 0) return 0L;

    if (digits > 19) throw nfe(s); // exceeds long range (19 digits max)

    long acc = 0;

    // process full 8-digit blocks
    for (; digits >= 8; digits -= 8, i += 8) {
      long block = parse8(s, i); // returns 0..99_999_999 or throws
      // overflow check: acc*10^8 + block <= MAX (or MIN for neg)
      long limit = neg ? -(Long.MIN_VALUE / POW10_8) : Long.MAX_VALUE / POW10_8;
      if (acc > limit) throw nfe(s);
      acc *= POW10_8;
      if (neg) {
        // For negative: ensure (acc - block) >= Long.MIN_VALUE
        // Rearrange to: acc >= Long.MIN_VALUE + block (safe, no overflow)
        if (acc < Long.MIN_VALUE + block) throw nfe(s);
        acc -= block;
      } else {
        if (block > Long.MAX_VALUE - acc) throw nfe(s);
        acc += block;
      }
    }

    // tail (0..7 digits)
    while (digits-- > 0) {
      int d = s.charAt(i++) - '0';
      if ((d | (9 - d)) < 0) throw nfe(s); // branchless 0..9 check
      long lim = neg ? Long.MIN_VALUE : Long.MAX_VALUE;
      // overflow check before acc*10 + d
      if (!neg) {
        if (acc > (Long.MAX_VALUE - d) / 10) throw nfe(s);
        acc = acc * 10 + d;
      } else {
        if (acc < (Long.MIN_VALUE + d) / 10) throw nfe(s);
        acc = acc * 10 - d;
      }
    }
    return acc; // Already has correct sign from accumulation
  }

  // SWAR-ish 8-digit block: subtract '0', validate, multiply by powers of 10
  private static long parse8(CharSequence s, int off) {
    long v0 = s.charAt(off) - '0';
    long v1 = s.charAt(off + 1) - '0';
    long v2 = s.charAt(off + 2) - '0';
    long v3 = s.charAt(off + 3) - '0';
    long v4 = s.charAt(off + 4) - '0';
    long v5 = s.charAt(off + 5) - '0';
    long v6 = s.charAt(off + 6) - '0';
    long v7 = s.charAt(off + 7) - '0';

    // validate 0..9 without branches
    if ((v0 | (9 - v0)) < 0
        | (v1 | (9 - v1)) < 0
        | (v2 | (9 - v2)) < 0
        | (v3 | (9 - v3)) < 0
        | (v4 | (9 - v4)) < 0
        | (v5 | (9 - v5)) < 0
        | (v6 | (9 - v6)) < 0
        | (v7 | (9 - v7)) < 0) throw nfe(null);

    // dot product with powers of 10 (fits in 32 bits)
    return ((((((v0 * 10 + v1) * 10 + v2) * 10 + v3) * 10 + v4) * 10 + v5) * 10 + v6) * 10 + v7;
    // (The above is intentionally simple; JVM fuses mul-adds well.)
  }

  private static NumberFormatException nfe(CharSequence s) {
    return new NumberFormatException(s == null ? "invalid digits" : "For input: \"" + s + '"');
  }
}

package io.jafar.parser;

import io.jafar.parser.internal_api.RecordingStream;
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
  public static String readUTF8(RecordingStream stream) throws IOException {
    byte id = stream.read();
    if (id == 0) {
      return null;
    } else if (id == 1) {
      return "";
    } else if (id == 2) {
      // string constant
      int ptr = (int) stream.readVarint();
      return stream.getContext().getMetadataLookup().getString(ptr);
    } else if (id == 3) {
      // UTF8
      int size = (int) stream.readVarint();
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
      int size = (int) stream.readVarint();
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
      int size = (int) stream.readVarint();
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
   * Skips over a UTF-8 encoded string in the recording stream without decoding it.
   *
   * <p>This method is useful when the string content is not needed and only the position in the
   * stream needs to be advanced.
   *
   * @param stream the recording stream to skip over
   * @throws IOException if an I/O error occurs during skipping
   */
  public static void skipUTF8(RecordingStream stream) throws IOException {
    byte id = stream.read();
    switch (id) {
      case 3:
      case 5:
        {
          int size = (int) stream.readVarint();
          stream.skip(size);
          break;
        }
      case 4:
        {
          int size = (int) stream.readVarint();
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
      default:
        // nothing to skip for null/empty or unknown here
        break;
    }
  }
}

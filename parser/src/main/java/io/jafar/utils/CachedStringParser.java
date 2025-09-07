package io.jafar.utils;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Utility class for parsing strings with caching for performance optimization.
 *
 * <p>This class provides parsers that cache previously parsed strings to avoid repeated string
 * creation when the same data is encountered multiple times.
 */
@SuppressWarnings("UnstableApiUsage")
public class CachedStringParser {
  /**
   * Public constructor for CachedStringParser.
   *
   * <p>This class provides utility methods for parsing strings with caching and does not maintain
   * state.
   */
  public CachedStringParser() {}

  /**
   * Parser for byte arrays with string caching.
   *
   * <p>This parser caches the last parsed string and byte array to avoid recreating strings when
   * the same data is encountered.
   */
  public static final class ByteArrayParser {
    /**
     * Public constructor for ByteArrayParser.
     *
     * <p>This parser caches the last parsed string and byte array to avoid recreating strings when
     * the same data is encountered.
     */
    public ByteArrayParser() {}

    private byte[] previousData = new byte[4096];
    private int previousLen = 0;
    private String lastString = null;

    /**
     * Parses a byte array into a string using the specified charset.
     *
     * <p>If the same byte array with the same length was previously parsed, the cached string is
     * returned instead of creating a new one.
     *
     * @param data the byte array to parse
     * @param len the length of data to use
     * @param charset the charset to use for parsing
     * @return the parsed string, either from cache or newly created
     */
    public String parse(byte[] data, int len, Charset charset) {
      if (lastString != null && previousLen == len && equalsRange(data, previousData, len)) {
        return lastString;
      }
      if (len > previousData.length) {
        previousData = Arrays.copyOf(data, len);
      } else {
        System.arraycopy(data, 0, previousData, 0, len);
      }
      previousLen = len;
      lastString = new String(data, 0, len, charset);
      return lastString;
    }

    private static boolean equalsRange(byte[] a, byte[] b, int len) {
      if (a == b) return true;
      if (a == null || b == null) return false;
      if (a.length < len || b.length < len) return false;
      for (int i = 0; i < len; i++) {
        if (a[i] != b[i]) return false;
      }
      return true;
    }
  }

  /**
   * Parser for char arrays with string caching.
   *
   * <p>This parser caches the last parsed string and char array to avoid recreating strings when
   * the same data is encountered.
   */
  public static final class CharArrayParser {
    /**
     * Public constructor for CharArrayParser.
     *
     * <p>This parser caches the last parsed string and char array to avoid recreating strings when
     * the same data is encountered.
     */
    public CharArrayParser() {}

    private char[] previousData = new char[4096];
    private int previousLen = 0;
    private String lastString = null;

    /**
     * Parses a char array into a string.
     *
     * <p>If the same char array with the same length was previously parsed, the cached string is
     * returned instead of creating a new one.
     *
     * @param data the char array to parse
     * @param len the length of data to use
     * @return the parsed string, either from cache or newly created
     */
    public String parse(char[] data, int len) {
      if (lastString != null && previousLen == len && equalsRange(data, previousData, len)) {
        return lastString;
      }
      if (len > previousData.length) {
        previousData = Arrays.copyOf(data, len);
      } else {
        System.arraycopy(data, 0, previousData, 0, len);
      }
      previousLen = len;
      lastString = new String(data, 0, len);
      return lastString;
    }

    private static boolean equalsRange(char[] a, char[] b, int len) {
      if (a == b) return true;
      if (a == null || b == null) return false;
      if (a.length < len || b.length < len) return false;
      for (int i = 0; i < len; i++) {
        if (a[i] != b[i]) return false;
      }
      return true;
    }
  }

  /**
   * Creates a new ByteArrayParser instance.
   *
   * @return a new ByteArrayParser
   */
  public static ByteArrayParser byteParser() {
    return new ByteArrayParser();
  }

  /**
   * Creates a new CharArrayParser instance.
   *
   * @return a new CharArrayParser
   */
  public static CharArrayParser charParser() {
    return new CharArrayParser();
  }
}

package io.jafar.utils;

import java.nio.charset.Charset;
import java.util.Arrays;

@SuppressWarnings("UnstableApiUsage")
public class CachedStringParser {
  public CachedStringParser() {}

  public static final class ByteArrayParser {
    public ByteArrayParser() {}

    private byte[] previousData = new byte[4096];
    private int previousLen = 0;
    private String lastString = null;

    public String parse(byte[] data, int len, Charset charset) {
      if (lastString != null
          && previousLen == len
          && Arrays.equals(data, 0, len, previousData, 0, len)) {
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
  }

  public static final class CharArrayParser {
    public CharArrayParser() {}

    private char[] previousData = new char[4096];
    private int previousLen = 0;
    private String lastString = null;

    public String parse(char[] data, int len) {
      if (lastString != null
          && previousLen == len
          && Arrays.equals(data, 0, len, previousData, 0, len)) {
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
  }

  public static ByteArrayParser byteParser() {
    return new ByteArrayParser();
  }

  public static CharArrayParser charParser() {
    return new CharArrayParser();
  }
}

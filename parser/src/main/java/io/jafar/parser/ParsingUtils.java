package io.jafar.parser;

import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.internal_api.RecordingStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ParsingUtils {
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

    public static String readUTF8(RecordingStream stream) throws IOException {
      byte id = stream.read();
      if (id == 0) {
        return null;
      } else if (id == 1) {
        return "";
      } else if (id == 2) {
        // string constant
        int ptr = (int)stream.readVarint();
        return stream.getContext().getMetadataLookup().getString(ptr);
      } else if (id == 3) {
        // UTF8
        int size = (int) stream.readVarint();
        if (size == 0) {
          return "";
        }
        byte[] content = size <= stream.getContext().byteBuffer.length ? stream.getContext().byteBuffer : new byte[size];
        stream.read(content, 0, size);
        return stream.getContext().utf8Parser.parse(content, size, StandardCharsets.UTF_8);
      } else if (id == 4) {
        int size = (int) stream.readVarint();
        if (size == 0) {
          return "";
        }
        char[] chars = size <= stream.getContext().charBuffer.length ? stream.getContext().charBuffer : new char[size];
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
        byte[] content = size <= stream.getContext().byteBuffer.length ? stream.getContext().byteBuffer : new byte[size];
        stream.read(content, 0, size);
        return stream.getContext().utf8Parser.parse(content, size, StandardCharsets.ISO_8859_1);
      } else {
        throw new IOException("Unexpected string constant id: " + id);
      }
    }

  public static void skipUTF8(RecordingStream stream) throws IOException {
    byte id = stream.read();
    switch (id) {
      case 3, 5 -> {
        int size = (int) stream.readVarint();
        stream.skip(size);
      }
      case 4 -> {
        int size = (int) stream.readVarint();
        for (int i = 0; i < size; i++) {
          stream.readVarint();
        }
      }
      case 2 -> {
        stream.readVarint();
      }
    }
  }
}

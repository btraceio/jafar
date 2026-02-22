package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.RecordingStreamReader;
import java.io.IOException;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

public class ParsingUtilsFuzzTest {

  private static RecordingStream streamOf(byte... bytes) throws IOException {
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("bytes-", ".bin");
    java.nio.file.Files.write(tmp, bytes);
    return new RecordingStream(
        RecordingStreamReader.mapped(tmp), new io.jafar.parser.impl.TypedParserContext());
  }

  @Test
  void readUTF8KnownTags() throws Exception {
    // id=1 empty
    try (RecordingStream s = streamOf((byte) 1)) {
      assertEquals("", io.jafar.parser.ParsingUtils.readUTF8(s, s.getContext().getStringTypeId()));
    }
    // id=0 null
    try (RecordingStream s = streamOf((byte) 0)) {
      assertNull(io.jafar.parser.ParsingUtils.readUTF8(s, s.getContext().getStringTypeId()));
    }
  }

  @RepeatedTest(30)
  void fuzzSkipUTF8ValidPatterns() throws Exception {
    java.util.Random rnd = new java.util.Random();
    int tag = new int[] {2, 3, 4, 5}[rnd.nextInt(4)];
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    out.write((byte) tag);
    switch (tag) {
      case 2:
        {
          // constant pool reference: one varint
          out.write(0x01);
          break;
        }
      case 3: // UTF8
      case 5:
        { // LATIN1
          int size = rnd.nextInt(8);
          out.write(size); // single-byte varint for small sizes
          byte[] payload = new byte[size];
          rnd.nextBytes(payload);
          out.write(payload);
          break;
        }
      case 4:
        { // char array varint-encoded chars
          int size = rnd.nextInt(8);
          out.write(size);
          for (int i = 0; i < size; i++) {
            out.write(rnd.nextInt(0x7F)); // one-byte varint
          }
          break;
        }
    }
    try (RecordingStream s = streamOf(out.toByteArray())) {
      assertDoesNotThrow(() -> io.jafar.parser.ParsingUtils.skipUTF8(s));
    }
  }
}

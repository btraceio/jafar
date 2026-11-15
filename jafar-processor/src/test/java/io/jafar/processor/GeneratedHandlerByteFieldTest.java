package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.call;
import static io.jafar.processor.ProcessorTestSupport.compileAndLoad;
import static io.jafar.processor.ProcessorTestSupport.parse;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * A JFR {@code byte} is one raw byte: the JDK's reader ({@code ParserFactory.ByteParser}) reads it
 * with {@code readByte()} and skips it as 1 byte, and the JMC writer writes it with {@code
 * writeByte}. Generated handlers read it as a varint, so a byte with the high bit set (any negative
 * value) also consumed the next byte as a varint continuation: the byte came back wrong and every
 * field after it was misread.
 */
class GeneratedHandlerByteFieldTest {
  private static final byte[] VALUES = {-1, -128, 127, 0, 5};

  @TempDir Path dir;

  @Test
  void byteFieldsAndByteArraysAreReadAsRawBytes() throws Exception {
    Path jfr = dir.resolve("bytes.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      // arrays: the JMC writer writes them only inside pooled types
      Type holder =
          r.registerType(
              "test.ByteHolder",
              t ->
                  t.addField("bs", Types.Builtin.BYTE, b -> b.asArray())
                      .addField("after", Types.Builtin.INT));
      Type event =
          r.registerEventType(
              "test.Bytes",
              t ->
                  t.addField("b", Types.Builtin.BYTE)
                      .addField("next", Types.Builtin.INT)
                      .addField("holder", holder));
      for (int i = 0; i < VALUES.length; i++) {
        int n = i;
        r.writeEvent(
            event.asValue(
                v -> {
                  v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                  v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                  v.putField("startTime", System.nanoTime());
                  v.putField("b", VALUES[n]);
                  v.putField("next", 1000 + n);
                  v.putField(
                      "holder",
                      holder.asValue(
                          h -> {
                            h.putField(
                                "bs", new byte[] {VALUES[n], VALUES[(n + 1) % VALUES.length]});
                            h.putField("after", 2000 + n);
                          }));
                }));
      }
    }
    ClassLoader loader =
        compileAndLoad(
            source(
                "PByteHolder",
                "@JfrType(\"test.ByteHolder\")\npublic interface PByteHolder {\n"
                    + "  byte[] bs();\n  int after();\n}\n"),
            source(
                "PBytes",
                "@JfrType(\"test.Bytes\")\npublic interface PBytes {\n"
                    + "  long startTime();\n  byte b();\n  int next();\n  PByteHolder holder();\n}\n"));
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.PBytes"),
        e -> {
          Object h = call(e, "holder");
          seen.add(
              call(e, "b")
                  + " "
                  + call(e, "next")
                  + " "
                  + Arrays.toString((byte[]) call(h, "bs"))
                  + " "
                  + call(h, "after"));
        },
        "PBytes",
        "PByteHolder");
    List<String> expected = new ArrayList<>();
    for (int n = 0; n < VALUES.length; n++) {
      expected.add(
          VALUES[n]
              + " "
              + (1000 + n)
              + " "
              + Arrays.toString(new byte[] {VALUES[n], VALUES[(n + 1) % VALUES.length]})
              + " "
              + (2000 + n));
    }
    assertEquals(expected, seen);
  }
}

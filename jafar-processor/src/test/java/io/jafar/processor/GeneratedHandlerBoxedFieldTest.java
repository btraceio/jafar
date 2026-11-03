package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.call;
import static io.jafar.processor.ProcessorTestSupport.compileAndLoad;
import static io.jafar.processor.ProcessorTestSupport.parse;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Boxed accessors ({@code Long}, {@code Integer}, ...) are read like their primitives. The
 * generator used to emit {@code null} for them, which consumed none of the field's bytes, so the
 * accessor returned null and every later field was read from the wrong place.
 */
class GeneratedHandlerBoxedFieldTest {
  @TempDir Path dir;

  @Test
  void boxedAccessorsAreRead() throws Exception {
    Path jfr = dir.resolve("boxed.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      Type event =
          r.registerEventType(
              "test.Boxed",
              t ->
                  t.addField("l", Types.Builtin.LONG)
                      .addField("i", Types.Builtin.INT)
                      .addField("b", Types.Builtin.BOOLEAN)
                      .addField("d", Types.Builtin.DOUBLE)
                      .addField("last", Types.Builtin.INT));
      r.writeEvent(
          event.asValue(
              v -> {
                v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                v.putField("startTime", System.nanoTime());
                v.putField("l", 1L << 40);
                v.putField("i", -5);
                v.putField("b", true);
                v.putField("d", 2.5);
                v.putField("last", 42);
              }));
    }
    ClassLoader loader =
        compileAndLoad(
            source(
                "PBoxed",
                "@JfrType(\"test.Boxed\")\npublic interface PBoxed {\n"
                    + "  long startTime();\n  Long l();\n  Integer i();\n  Boolean b();\n"
                    + "  Double d();\n  int last();\n}\n"));
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.PBoxed"),
        e ->
            seen.add(
                call(e, "l")
                    + " "
                    + call(e, "i")
                    + " "
                    + call(e, "b")
                    + " "
                    + call(e, "d")
                    + " "
                    + call(e, "last")),
        "PBoxed");
    assertEquals(List.of((1L << 40) + " -5 true 2.5 42"), seen);
  }
}

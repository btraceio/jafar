package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.call;
import static io.jafar.processor.ProcessorTestSupport.compileAndLoad;
import static io.jafar.processor.ProcessorTestSupport.parse;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * {@code @JfrField(raw = true)} on a primitive accessor for a constant pool field returns the pool
 * index, as the runtime-generated handlers do (parser-codegen's {@code JFRExecutionSample} declares
 * {@code stackTraceId()} this way). The storage check must accept the pooled field for such an
 * accessor; it used to reject it as "declared as an inline value".
 */
class GeneratedHandlerRawFieldTest {
  @TempDir Path dir;

  @Test
  void rawAccessorReturnsThePoolIndex() throws Exception {
    Path jfr = dir.resolve("raw.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      Type thread = types.getType(Types.JDK.THREAD);
      Type event = r.registerEventType("test.Raw", t -> t.addField("seq", Types.Builtin.INT));
      TypedValue a = thread.asValue(t -> t.putField("javaName", "A"));
      TypedValue b = thread.asValue(t -> t.putField("javaName", "B"));
      TypedValue[] order = {a, b, a, b};
      for (int i = 0; i < order.length; i++) {
        int n = i;
        r.writeEvent(
            event.asValue(
                v -> {
                  v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                  v.putField("eventThread", order[n]);
                  v.putField("startTime", System.nanoTime());
                  v.putField("seq", n);
                }));
      }
    }
    ClassLoader loader =
        compileAndLoad(
            source(
                "PRaw",
                "import io.jafar.parser.api.JfrField;\n"
                    + "@JfrType(\"test.Raw\")\npublic interface PRaw {\n"
                    + "  @JfrField(value = \"eventThread\", raw = true)\n  long threadRef();\n"
                    + "  int seq();\n}\n"));
    List<long[]> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.PRaw"),
        e -> seen.add(new long[] {(Integer) call(e, "seq"), (Long) call(e, "threadRef")}),
        "PRaw");
    assertEquals(4, seen.size());
    for (int i = 0; i < 4; i++) {
      assertEquals(i, seen.get(i)[0], "seq after the raw field");
    }
    long refA = seen.get(0)[1];
    long refB = seen.get(1)[1];
    assertTrue(refA > 0 && refB > 0, "pool indexes: " + refA + ", " + refB);
    assertNotEquals(refA, refB);
    assertEquals(refA, seen.get(2)[1]);
    assertEquals(refB, seen.get(3)[1]);
  }
}

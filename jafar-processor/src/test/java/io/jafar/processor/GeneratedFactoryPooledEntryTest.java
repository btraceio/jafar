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
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * A constant pool caches each decoded entry and returns it for every later reference. A build-time
 * factory's {@code get} returns one reused handler per thread, so the pool cached that one instance
 * for every entry of the type: each cached thread read as whichever thread had been decoded last.
 * Pooled entries now come from {@code HandlerFactory.create}, a new instance each.
 */
class GeneratedFactoryPooledEntryTest {
  @TempDir Path dir;

  @Test
  void repeatedReferencesResolveToTheirOwnEntries() throws Exception {
    Path jfr = dir.resolve("repeated.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      Type thread = types.getType(Types.JDK.THREAD);
      Type event = r.registerEventType("test.Rep", t -> t.addField("seq", Types.Builtin.INT));
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
                "PThread",
                "@JfrType(\"java.lang.Thread\")\npublic interface PThread {\n  String javaName();\n}\n"),
            source(
                "PRep",
                "@JfrType(\"test.Rep\")\npublic interface PRep {\n"
                    + "  int seq();\n  PThread eventThread();\n}\n"));
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.PRep"),
        e -> seen.add(call(e, "seq") + " " + call(call(e, "eventThread"), "javaName")),
        "PRep",
        "PThread");
    assertEquals(List.of("0 A", "1 B", "2 A", "3 B"), seen);
  }
}

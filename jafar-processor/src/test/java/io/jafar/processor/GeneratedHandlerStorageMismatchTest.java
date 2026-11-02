package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.call;
import static io.jafar.processor.ProcessorTestSupport.compileAndLoad;
import static io.jafar.processor.ProcessorTestSupport.parse;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * A generated handler is built without the recording's metadata: it reads a complex-typed accessor
 * as a constant pool reference and a primitive or String accessor as an inline value. When the
 * recording stores the field differently it used to misread that field and every field after it
 * without an error - a {@code jdk.types.StackTrace}'s {@code frames} are inline structures, and a
 * {@code jdk.types.Method}'s {@code name} is a pooled {@code jdk.types.Symbol}. It now fails,
 * naming the field and both storages.
 */
class GeneratedHandlerStorageMismatchTest {
  @TempDir Path dir;

  /** An event recorded by this JVM with a stack trace; the JVM writes the frames inline. */
  @jdk.jfr.Name("test.InlineFrames")
  @jdk.jfr.StackTrace(true)
  static final class InlineFramesEvent extends jdk.jfr.Event {
    int seq;
  }

  @Test
  void complexAccessorForAnInlineArrayFails() throws Exception {
    // The JMC writer pools stack frames; the JVM writes them inside the stack trace entry.
    Path jfr = dir.resolve("jvm.jfr");
    try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
      r.enable(InlineFramesEvent.class).withStackTrace();
      r.start();
      InlineFramesEvent e = new InlineFramesEvent();
      e.seq = 1;
      e.commit();
      r.stop();
      r.dump(jfr);
    }
    ClassLoader loader =
        compileAndLoad(
            source(
                "PFrame",
                "@JfrType(\"jdk.types.StackFrame\")\npublic interface PFrame {\n  int lineNumber();\n}\n"),
            source(
                "PStack",
                "@JfrType(\"jdk.types.StackTrace\")\npublic interface PStack {\n  PFrame[] frames();\n}\n"),
            source(
                "PEv",
                "@JfrType(\"test.InlineFrames\")\npublic interface PEv {\n"
                    + "  int seq();\n  PStack stackTrace();\n}\n"));
    Exception thrown =
        assertThrows(
            Exception.class,
            () ->
                parse(
                    jfr,
                    loader,
                    loader.loadClass("test.PEv"),
                    ev -> call(call(ev, "stackTrace"), "frames"),
                    "PEv",
                    "PStack",
                    "PFrame"));
    assertChain(
        thrown,
        "PStackHandler cannot read jdk.types.StackTrace.frames: the recording stores it as an array"
            + " of inline values of type jdk.types.StackFrame, but PStack declares it as an array of"
            + " constant pool references of type jdk.types.StackFrame");
  }

  @Test
  void stringAccessorForAPooledSymbolFails() throws Exception {
    Path jfr = dir.resolve("symbol.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      Type symbol = types.getType(Types.JDK.SYMBOL);
      Type event =
          r.registerEventType(
              "test.Sym", t -> t.addField("sym", symbol).addField("last", Types.Builtin.INT));
      r.writeEvent(
          event.asValue(
              v -> {
                v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                v.putField("startTime", System.nanoTime());
                v.putField("sym", symbol.asValue(sv -> sv.putField("string", "some.Symbol")));
                v.putField("last", 9);
              }));
    }
    ClassLoader loader =
        compileAndLoad(
            source(
                "PSym",
                "@JfrType(\"test.Sym\")\npublic interface PSym {\n  String sym();\n  int last();\n}\n"));
    Exception thrown =
        assertThrows(
            Exception.class,
            () -> parse(jfr, loader, loader.loadClass("test.PSym"), e -> call(e, "last"), "PSym"));
    assertChain(
        thrown,
        "PSymHandler cannot read test.Sym.sym: the recording stores it as a constant pool"
            + " reference of type jdk.types.Symbol, but PSym declares it as an inline value of type"
            + " java.lang.String");
  }

  /**
   * The field is inline, as declared, but of another type: a {@code float} accessor reads 4 raw
   * bytes where the recording has an {@code int} varint. The storage check did not look at types.
   */
  @Test
  void accessorOfAnotherTypeFails() throws Exception {
    Path jfr = dir.resolve("types.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      Type event =
          r.registerEventType(
              "test.Typed",
              t -> t.addField("n", Types.Builtin.INT).addField("last", Types.Builtin.INT));
      r.writeEvent(
          event.asValue(
              v -> {
                v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                v.putField("startTime", System.nanoTime());
                v.putField("n", 7);
                v.putField("last", 9);
              }));
    }
    ClassLoader loader =
        compileAndLoad(
            source(
                "PTyped",
                "@JfrType(\"test.Typed\")\npublic interface PTyped {\n  float n();\n  int last();\n}\n"));
    Exception thrown =
        assertThrows(
            Exception.class,
            () ->
                parse(
                    jfr, loader, loader.loadClass("test.PTyped"), e -> call(e, "last"), "PTyped"));
    assertChain(
        thrown,
        "PTypedHandler cannot read test.Typed.n: the recording stores it as an inline value of type"
            + " int, but PTyped declares it as an inline value of type float");
  }

  private static void assertChain(Throwable t, String text) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append("\n  ").append(c);
    }
    assertTrue(sb.toString().contains(text), "missing '" + text + "' in" + sb);
  }
}

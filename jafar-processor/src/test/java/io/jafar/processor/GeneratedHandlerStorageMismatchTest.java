package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.call;
import static io.jafar.processor.ProcessorTestSupport.compileAndLoad;
import static io.jafar.processor.ProcessorTestSupport.parse;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
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
 * {@code jdk.types.Method}'s {@code name} is a pooled {@code jdk.types.Symbol}. Both are now read,
 * choosing the read from the metadata once per metadata class; a storage no read handles fails,
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

  private static final String FRAME =
      "@JfrType(\"jdk.types.StackFrame\")\npublic interface PFrame {\n"
          + "  int lineNumber();\n  PMethod method();\n}\n";
  private static final String METHOD =
      "@JfrType(\"jdk.types.Method\")\npublic interface PMethod {\n"
          + "  String name();\n  PClass type();\n}\n";
  private static final String CLASS =
      "@JfrType(\"java.lang.Class\")\npublic interface PClass {\n  String name();\n}\n";
  private static final String STACK =
      "@JfrType(\"jdk.types.StackTrace\")\npublic interface PStack {\n  PFrame[] frames();\n}\n";
  private static final String EVENT =
      "@JfrType(\"test.InlineFrames\")\npublic interface PEv {\n"
          + "  int seq();\n  PStack stackTrace();\n}\n";

  /** Records {@code n} events from two call sites, so their stack traces differ. */
  private Path recordInlineFrames(int n) throws Exception {
    // The JMC writer pools stack frames; the JVM writes them inside the stack trace entry.
    Path jfr = dir.resolve("jvm.jfr");
    try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
      r.enable(InlineFramesEvent.class).withStackTrace();
      r.start();
      for (int i = 0; i < n; i++) {
        if (i % 2 == 0) {
          commitFromHere(i);
        } else {
          commitFromThere(i);
        }
      }
      r.stop();
      r.dump(jfr);
    }
    return jfr;
  }

  private static void commitFromHere(int seq) {
    InlineFramesEvent e = new InlineFramesEvent();
    e.seq = seq;
    e.commit();
  }

  private static void commitFromThere(int seq) {
    InlineFramesEvent e = new InlineFramesEvent();
    e.seq = seq;
    e.commit();
  }

  /** Each event's frames as the JDK's own reader sees them: "seq: class.method:line ...". */
  private static List<String> expectedFrames(Path jfr) throws Exception {
    List<String> out = new ArrayList<>();
    for (RecordedEvent e : RecordingFile.readAllEvents(jfr)) {
      if (!e.getEventType().getName().equals("test.InlineFrames")) {
        continue;
      }
      StringBuilder sb = new StringBuilder().append(e.getInt("seq")).append(':');
      for (RecordedFrame f : e.getStackTrace().getFrames()) {
        sb.append(' ')
            .append(f.getMethod().getType().getName())
            .append('.')
            .append(f.getMethod().getName())
            .append(':')
            .append(f.getLineNumber());
      }
      out.add(sb.toString());
    }
    return out;
  }

  /** One event's frames through the generated handlers, formatted like {@link #expectedFrames}. */
  private static String describeFrames(Object seq, Object[] frames) {
    StringBuilder sb = new StringBuilder().append(seq).append(':');
    for (Object f : frames) {
      Object method = call(f, "method");
      // the JVM writes class names in internal form; the JDK's reader converts them
      String type = ((String) call(call(method, "type"), "name")).replace('/', '.');
      sb.append(' ')
          .append(type)
          .append('.')
          .append(call(method, "name"))
          .append(':')
          .append(call(f, "lineNumber"));
    }
    return sb.toString();
  }

  /**
   * Inline frames, and the pooled {@code jdk.types.Symbol} names of their methods and classes read
   * through {@code String} accessors, match the JDK's reader frame by frame.
   */
  @Test
  void inlineFramesAndPooledSymbolsAreRead() throws Exception {
    Path jfr = recordInlineFrames(4);
    ClassLoader loader =
        compileAndLoad(
            source("PFrame", FRAME),
            source("PMethod", METHOD),
            source("PClass", CLASS),
            source("PStack", STACK),
            source("PEv", EVENT));
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.PEv"),
        ev ->
            seen.add(
                describeFrames(call(ev, "seq"), (Object[]) call(call(ev, "stackTrace"), "frames"))),
        "PEv",
        "PStack",
        "PFrame",
        "PMethod",
        "PClass");
    List<String> expected = expectedFrames(jfr);
    assertEquals(4, expected.size());
    assertTrue(expected.get(0).contains(".commitFromHere:"), expected.get(0));
    assertTrue(expected.get(1).contains(".commitFromThere:"), expected.get(1));
    assertEquals(expected, seen);
  }

  /**
   * Inline values are read by a new handler each, not the instance a factory reuses per thread:
   * frames kept past the event that read them keep their own values.
   */
  @Test
  void inlineValuesOutliveTheirEvent() throws Exception {
    Path jfr = recordInlineFrames(6);
    ClassLoader loader =
        compileAndLoad(
            source("PFrame", FRAME),
            source("PMethod", METHOD),
            source("PClass", CLASS),
            source("PStack", STACK),
            source("PEv", EVENT));
    List<Object[]> kept = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.PEv"),
        ev -> kept.add(new Object[] {call(ev, "seq"), call(call(ev, "stackTrace"), "frames")}),
        "PEv",
        "PStack",
        "PFrame",
        "PMethod",
        "PClass");
    List<String> seen = new ArrayList<>();
    for (Object[] k : kept) {
      seen.add(describeFrames(k[0], (Object[]) k[1]));
    }
    assertEquals(expectedFrames(jfr), seen);
  }

  /**
   * An inline value is read by the generated handler of the accessor's type; a nested interface
   * gets none, so such a field still fails, naming it.
   */
  @Test
  void inlineValueWithoutAGeneratedHandlerFails() throws Exception {
    Path jfr = recordInlineFrames(1);
    ClassLoader loader =
        compileAndLoad(
            source(
                "PStack",
                "@JfrType(\"jdk.types.StackTrace\")\npublic interface PStack {\n"
                    + "  Frame[] frames();\n"
                    + "  @JfrType(\"jdk.types.StackFrame\")\n"
                    + "  interface Frame {\n    int lineNumber();\n  }\n}\n"),
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
                    "PStack"));
    assertChain(
        thrown,
        "PStackHandler cannot read jdk.types.StackTrace.frames: the recording stores it as an array"
            + " of inline values of type jdk.types.StackFrame, which needs the generated handler of"
            + " the accessor's type, and PStack declares it with a type that has none");
  }

  /** A {@code String} accessor for a pooled {@code jdk.types.Symbol} resolves and unwraps it. */
  @Test
  void stringAccessorForAPooledSymbolIsRead() throws Exception {
    Path jfr = dir.resolve("symbol.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      Type symbol = types.getType(Types.JDK.SYMBOL);
      Type event =
          r.registerEventType(
              "test.Sym", t -> t.addField("sym", symbol).addField("last", Types.Builtin.INT));
      String[] values = {"some.Symbol", "other.Symbol", "some.Symbol"};
      for (int i = 0; i < values.length; i++) {
        String value = values[i];
        int last = 9 + i;
        r.writeEvent(
            event.asValue(
                v -> {
                  v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                  v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                  v.putField("startTime", System.nanoTime());
                  v.putField("sym", symbol.asValue(sv -> sv.putField("string", value)));
                  v.putField("last", last);
                }));
      }
    }
    ClassLoader loader =
        compileAndLoad(
            source(
                "PSym",
                "@JfrType(\"test.Sym\")\npublic interface PSym {\n  String sym();\n  int last();\n}\n"));
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.PSym"),
        e -> seen.add(call(e, "sym") + " " + call(e, "last")),
        "PSym");
    assertEquals(List.of("some.Symbol 9", "other.Symbol 10", "some.Symbol 11"), seen);
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

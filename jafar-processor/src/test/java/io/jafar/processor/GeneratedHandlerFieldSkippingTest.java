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
 * Build-time generated handlers read only the fields their interface declares and skip the rest
 * with {@code TypeSkipper.skip(MetadataField, ...)}. That method skipped nothing for a primitive
 * field and skipped an array of constant pool references as whole values, so every declared field
 * after such a field was read from the wrong bytes.
 */
class GeneratedHandlerFieldSkippingTest {
  @TempDir Path dir;

  /**
   * Declared fields come after one undeclared field of every encoding: on the event, a varint
   * ({@code long}), a byte ({@code boolean}), a float, a double, an inline string and a constant
   * pool reference; inside the pooled {@code test.Holder} entry the event points at, an array of
   * constant pool references and an array of primitives. (The JMC writer writes array fields only
   * inside pooled types: on an event it throws {@code IllegalArgumentException}.)
   */
  @Test
  void declaredFieldAfterEveryKindOfUndeclaredFieldIsReadCorrectly() throws Exception {
    Path jfr = dir.resolve("skips.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      Type thread = types.getType(Types.JDK.THREAD);
      Type holder =
          r.registerType(
              "test.Holder",
              t ->
                  t.addField("refs", thread, TypedFieldBuilderArray.INSTANCE)
                      .addField("nums", Types.Builtin.LONG, TypedFieldBuilderArray.INSTANCE)
                      .addField("hlast", Types.Builtin.INT));
      Type event =
          r.registerEventType(
              "test.Skips",
              t ->
                  t.addField("l", Types.Builtin.LONG)
                      .addField("b", Types.Builtin.BOOLEAN)
                      .addField("f", Types.Builtin.FLOAT)
                      .addField("d", Types.Builtin.DOUBLE)
                      .addField("s", Types.Builtin.STRING)
                      .addField("t", thread)
                      .addField("holder", holder)
                      .addField("last", Types.Builtin.INT));
      for (int i = 0; i < 3; i++) {
        int n = i;
        TypedValue th =
            thread.asValue(
                v -> {
                  v.putField("javaName", "thread-" + n);
                  v.putField("osThreadId", 100L + n);
                });
        TypedValue h =
            holder.asValue(
                v -> {
                  v.putField("refs", th, th);
                  v.putField("nums", new long[] {1L << 40, 2L << 40, n});
                  v.putField("hlast", 777 + n);
                });
        r.writeEvent(
            event.asValue(
                v -> {
                  // implicit fields first: the JMC writer rejects the event otherwise (see
                  // JfrTestHelper)
                  v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                  v.putField("eventThread", thread.nullValue());
                  v.putField("startTime", System.nanoTime());
                  v.putField("l", 1_000_000_000_000L * (n + 1));
                  v.putField("b", n % 2 == 0);
                  v.putField("f", 1.5f * n);
                  v.putField("d", 2.5 * n);
                  v.putField("s", "string number " + n);
                  v.putField("t", th);
                  v.putField("holder", h);
                  v.putField("last", 4242 + n);
                }));
      }
    }

    ClassLoader loader =
        compileAndLoad(
            source(
                "PHolder",
                "@JfrType(\"test.Holder\")\npublic interface PHolder {\n  int hlast();\n}\n"),
            source(
                "PSkips",
                "@JfrType(\"test.Skips\")\npublic interface PSkips {\n"
                    + "  PHolder holder();\n  int last();\n}\n"));
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.PSkips"),
        e -> seen.add(call(e, "last") + " " + call(call(e, "holder"), "hlast")),
        "PSkips",
        "PHolder");
    assertEquals(List.of("4242 777", "4243 778", "4244 779"), seen);
  }

  /**
   * A chunk without a pool of a declared reference type: the getter returns {@code null}, and an
   * empty array for an array field, as runtime-generated handlers do. It threw a {@code
   * NullPointerException} from {@code getConstantPool(...).get(...)}. No thread is ever written, so
   * the chunk has no {@code java.lang.Thread} pool.
   */
  @Test
  void getterForATypeWithoutAPoolReturnsNull() throws Exception {
    Path jfr = dir.resolve("nopool.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      Type thread = types.getType(Types.JDK.THREAD);
      Type holder =
          r.registerType(
              "test.NHolder",
              t ->
                  t.addField("ts", thread, TypedFieldBuilderArray.INSTANCE)
                      .addField("x", Types.Builtin.INT));
      Type event =
          r.registerEventType(
              "test.NoPool",
              t ->
                  t.addField("t", thread)
                      .addField("h", holder)
                      .addField("last", Types.Builtin.INT));
      r.writeEvent(
          event.asValue(
              v -> {
                v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                v.putField("eventThread", thread.nullValue());
                v.putField("startTime", System.nanoTime());
                v.putField("t", thread.nullValue());
                v.putField(
                    "h",
                    holder.asValue(
                        hv -> {
                          hv.putField("ts", thread.nullValue(), thread.nullValue());
                          hv.putField("x", 5);
                        }));
                v.putField("last", 7);
              }));
    }
    ClassLoader loader =
        compileAndLoad(
            source(
                "PThread",
                "@JfrType(\"java.lang.Thread\")\npublic interface PThread {\n"
                    + "  String osName();\n  long osThreadId();\n  String javaName();\n}\n"),
            source(
                "PNH",
                "@JfrType(\"test.NHolder\")\npublic interface PNH {\n"
                    + "  PThread[] ts();\n  int x();\n}\n"),
            source(
                "PNoPool",
                "@JfrType(\"test.NoPool\")\npublic interface PNoPool {\n"
                    // startTime declared so that this test does not depend on field skipping
                    + "  long startTime();\n  PThread t();\n  PNH h();\n  int last();\n}\n"));
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.PNoPool"),
        e -> {
          Object h = call(e, "h");
          seen.add(
              call(e, "last")
                  + " t="
                  + call(e, "t")
                  + " ts="
                  + ((Object[]) call(h, "ts")).length
                  + " x="
                  + call(h, "x"));
        },
        "PNoPool",
        "PNH",
        "PThread");
    assertEquals(List.of("7 t=null ts=0 x=5"), seen);
  }

  /** {@code builder.asArray()}, as a reusable field customizer. */
  private enum TypedFieldBuilderArray
      implements
          java.util.function.Consumer<org.openjdk.jmc.flightrecorder.writer.api.TypedFieldBuilder> {
    INSTANCE;

    @Override
    public void accept(org.openjdk.jmc.flightrecorder.writer.api.TypedFieldBuilder b) {
      b.asArray();
    }
  }
}

package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.call;
import static io.jafar.processor.ProcessorTestSupport.compileAndLoad;
import static io.jafar.processor.ProcessorTestSupport.parse;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedFieldBuilder;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Build-time handlers on a recording with more than one chunk, where the chunks give the same types
 * different ids.
 *
 * <p>Metadata is per chunk and type ids are chunk-local. The parser attached the registered
 * factories to the metadata classes of one chunk only (whichever chunk read its metadata first),
 * and the generated handlers kept the id of each referenced type's constant pool in a static field
 * bound once, from that same chunk. So every other chunk's events reached the handler as {@code
 * null} where no runtime handler could stand in, and once the factories were attached to every
 * chunk, a chunk with different ids looked its references up in the wrong pool: {@code label()} was
 * {@code null}.
 *
 * <p>Which chunk was the lucky one depended on timing, so one chunk's events were always right. The
 * assertion is on the events of both.
 */
class GeneratedFactoryMultiChunkTest {
  private static final String LABEL = "test.Label";

  @TempDir Path dir;

  @Test
  void everyChunkIsReadByTheFactoriesWithItsOwnTypeIds() throws Exception {
    assertEveryChunkReadWithItsOwnPools(ParsingContext.create());
  }

  /**
   * One chunk thread: both chunks are read by the same thread, so by the same thread-local event
   * handler. A handler keeps the constant pool its getters resolved ({@code label_cp}); kept past
   * the chunk it belongs to, {@code label()} of the second chunk's events looks its reference up in
   * the first chunk's pool and answers {@code A0} for {@code B0}. With the default parallelism each
   * chunk usually gets a thread, and a handler, of its own, which hides that.
   */
  @Test
  void aHandlerReusedForTheNextChunkResolvesThatChunksPools() throws Exception {
    assertEveryChunkReadWithItsOwnPools(ParsingContext.create().withParallelism(1));
  }

  private void assertEveryChunkReadWithItsOwnPools(ParsingContext context) throws Exception {
    Path first = dir.resolve("first.jfr");
    Path second = dir.resolve("second.jfr");
    write(first, "A", 0, false);
    write(second, "B", 10, true);
    Path recording = dir.resolve("both.jfr");
    try (OutputStream os = Files.newOutputStream(recording)) {
      Files.copy(first, os);
      Files.copy(second, os);
    }

    // The premise: two chunks, and the referenced type does not have the same id in both.
    Map<Integer, Long> labelIds = labelTypeIds(recording);
    assertEquals(2, labelIds.size(), "chunks: " + labelIds);
    assertNotEquals(
        labelIds.values().toArray()[0], labelIds.values().toArray()[1], LABEL + " ids per chunk");

    ClassLoader loader =
        compileAndLoad(
            source(
                "PLabel",
                "@JfrType(\"" + LABEL + "\")\npublic interface PLabel {\n  String name();\n}\n"),
            source(
                "PRep",
                "@JfrType(\"test.Rep\")\npublic interface PRep {\n"
                    + "  int seq();\n  PLabel label();\n  PHolder holder();\n}\n"),
            source(
                "PHolder",
                "@JfrType(\"test.Holder\")\npublic interface PHolder {\n  PLabel[] labels();\n}\n"));
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        context,
        recording,
        loader,
        loader.loadClass("test.PRep"),
        e -> seen.add(e == null ? "null event" : describe(e)),
        "PRep",
        "PLabel",
        "PHolder");

    Collections.sort(seen);
    assertEquals(
        List.of(
            "00 A0 [A0, A1]",
            "01 A1 [A1, A2]",
            "02 A2 [A2, A0]",
            "10 B0 [B0, B1]",
            "11 B1 [B1, B2]",
            "12 B2 [B2, B0]"),
        seen);
  }

  private static String describe(Object event) {
    Object label = call(event, "label");
    Object holder = call(event, "holder");
    Object[] labels = holder == null ? null : (Object[]) call(holder, "labels");
    List<Object> names = new ArrayList<>();
    if (labels != null) {
      for (Object l : labels) {
        names.add(l == null ? null : call(l, "name"));
      }
    }
    return String.format(
        "%02d %s %s",
        (Integer) call(event, "seq"),
        label == null ? null : call(label, "name"),
        labels == null ? null : names);
  }

  /**
   * One chunk with three {@code test.Rep} events referring to {@value #LABEL} entries. With {@code
   * shift}, two more types are registered first, so the types after them get other ids.
   */
  private static void write(Path jfr, String prefix, int base, boolean shift) throws Exception {
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      if (shift) {
        types.getOrAdd("test.Dummy", t -> t.addField("x", Types.Builtin.INT));
        r.registerEventType("test.Other", t -> t.addField("y", Types.Builtin.INT));
      }
      Type label = types.getOrAdd(LABEL, t -> t.addField("name", Types.Builtin.STRING));
      Type holder =
          types.getOrAdd(
              "test.Holder", false, t -> t.addField("labels", label, TypedFieldBuilder::asArray));
      Type event =
          r.registerEventType(
              "test.Rep",
              t ->
                  t.addField("seq", Types.Builtin.INT)
                      .addField("label", label)
                      .addField("holder", holder));
      TypedValue[] entries = new TypedValue[3];
      for (int i = 0; i < entries.length; i++) {
        String name = prefix + i;
        entries[i] = label.asValue(t -> t.putField("name", name));
      }
      for (int i = 0; i < entries.length; i++) {
        int n = i;
        r.writeEvent(
            event.asValue(
                v -> {
                  v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                  v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                  v.putField("startTime", System.nanoTime());
                  v.putField("seq", base + n);
                  v.putField("label", entries[n]);
                  v.putField(
                      "holder",
                      h ->
                          h.putFields(
                              "labels",
                              e -> e.putField("name", prefix + n),
                              e -> e.putField("name", prefix + (n + 1) % entries.length)));
                }));
      }
    }
  }

  /** The id {@value #LABEL} has in each chunk's metadata. */
  private static Map<Integer, Long> labelTypeIds(Path recording) throws Exception {
    Map<Integer, Long> ids = Collections.synchronizedMap(new TreeMap<>());
    ThreadLocal<Integer> chunk = new ThreadLocal<>();
    ChunkParserListener listener =
        new ChunkParserListener() {
          @Override
          public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
            chunk.set(chunkIndex);
            return true;
          }

          @Override
          public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
            ids.put(chunk.get(), context.getMetadataLookup().getClass(LABEL).getId());
            return true;
          }
        };
    try (UntypedJafarParser parser =
        ParsingContext.create()
            .newUntypedParser(recording)
            .<UntypedJafarParser>withParserListener(listener)) {
      parser.handle((type, event, ctl) -> {});
      parser.run();
    }
    return ids;
  }
}

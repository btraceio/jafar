package io.jafar.shell.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.ValueProcessor;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import io.jafar.shell.JFRSession;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@code crossref}'s per-event-type unique count is a count of constant pool entries, and CP ids
 * only mean something inside their chunk.
 *
 * <p>It used to put every chunk's ids into one set per event type, so an id that appears in two
 * chunks -- two different entries -- was counted once. In {@code tck-test.jfr}, {@code
 * jdk.ActiveRecording} and {@code jdk.ActiveSetting} each reference one stack trace per chunk, both
 * with the same id, and the table said 1 for each instead of 2.
 *
 * <p>The oracle decodes every event itself and counts distinct direct {@code jdk.types.StackTrace}
 * references per chunk. That is the whole answer only because no constant pool type has a field of
 * that type, so nothing can reach it transitively; the test checks that first.
 */
class CrossrefPerChunkTest {

  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  private static final String TARGET = "jdk.types.StackTrace";

  @Test
  void uniqueCountsAreSummedPerChunk() throws Exception {
    Map<String, Long> expected = new TreeMap<>(oracle());
    // The fixture must exercise the bug: some id referenced from two chunks.
    assertEquals(2L, expected.get("jdk.ActiveRecording"), "oracle: jdk.ActiveRecording");
    assertEquals(2L, expected.get("jdk.ActiveSetting"), "oracle: jdk.ActiveSetting");

    Map<String, Long> shown = new TreeMap<>(uniqueColumn(crossrefOutput()));
    assertEquals(expected, shown);
  }

  /** Event type -> distinct direct TARGET references, counted per chunk and summed. */
  private static Map<String, Long> oracle() throws Exception {
    Map<Integer, Map<String, Set<Long>>> byChunk = new ConcurrentSkipListMap<>();
    Set<String> cpTypesReferencingTarget = ConcurrentHashMap.newKeySet();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          TCK_RECORDING,
          new ChunkParserListener() {
            @Override
            public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
              for (MetadataClass c : metadata.getClasses()) {
                if ("jdk.jfr.Event".equals(c.getSuperType())) continue;
                for (MetadataField f : c.getFields()) {
                  if (f.getType() != null && TARGET.equals(f.getType().getName())) {
                    cpTypesReferencingTarget.add(c.getName());
                  }
                }
              }
              return true;
            }

            @Override
            public boolean onEvent(
                ParserContext context, long typeId, long start, long raw, long payload) {
              MetadataClass eventType = context.getMetadataLookup().getClass(typeId);
              if (eventType == null) return true;
              Set<Long> ids = new HashSet<>();
              ValueProcessor collector =
                  new ValueProcessor() {
                    @Override
                    public void onConstantPoolIndex(
                        MetadataClass owner, String fld, MetadataClass type, long ptr) {
                      if (TARGET.equals(type.getName())) ids.add(ptr);
                    }
                  };
              try {
                new GenericValueReader(collector)
                    .readValue(context.get(RecordingStream.class), eventType);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              if (!ids.isEmpty()) {
                byChunk
                    .computeIfAbsent(context.getChunkIndex(), k -> new HashMap<>())
                    .computeIfAbsent(eventType.getName(), k -> new HashSet<>())
                    .addAll(ids);
              }
              return true;
            }
          });
    }
    assertTrue(
        cpTypesReferencingTarget.isEmpty(),
        "oracle covers direct references only, but these reach "
            + TARGET
            + ": "
            + cpTypesReferencingTarget);
    Map<String, Long> sum = new HashMap<>();
    byChunk
        .values()
        .forEach(m -> m.forEach((et, ids) -> sum.merge(et, (long) ids.size(), Long::sum)));
    return sum;
  }

  /** Runs {@code constants/TARGET | crossref()} through the shell's dispatcher. */
  private static String crossrefOutput() throws Exception {
    ParsingContext ctx = ParsingContext.create();
    SessionManager<JFRSession> sessions =
        new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    SessionManager.SessionRef<JFRSession> ref = sessions.open(TCK_RECORDING, null);
    StringBuilder out = new StringBuilder();
    StringBuilder err = new StringBuilder();
    AtomicReference<SessionManager.SessionRef<? extends Session>> current =
        new AtomicReference<>(ref);
    try {
      CommandDispatcher dispatcher =
          new CommandDispatcher(
              sessions,
              new CommandDispatcher.IO() {
                @Override
                public void println(String s) {
                  out.append(s).append('\n');
                }

                @Override
                public void printf(String fmt, Object... args) {
                  out.append(String.format(fmt, args));
                }

                @Override
                public void error(String s) {
                  err.append(s).append('\n');
                }
              },
              current::set);
      assertTrue(dispatcher.dispatch("constants/" + TARGET + " | crossref()"), "handled");
      assertEquals("", err.toString(), "errors");
      return out.toString();
    } finally {
      sessions.closeAll();
    }
  }

  /** Parses the "Referencing event types" table: eventType -> uniqueStackTrace. */
  private static Map<String, Long> uniqueColumn(String output) {
    Map<String, Long> unique = new HashMap<>();
    int col = -1;
    for (String line : output.split("\n")) {
      if (!line.startsWith("|")) continue;
      String[] cells = line.split("\\|");
      if (col < 0) {
        for (int i = 0; i < cells.length; i++) {
          if (cells[i].trim().equals("uniqueStackTrace")) col = i;
        }
        assertTrue(col > 0, "no uniqueStackTrace column in: " + line);
        continue;
      }
      unique.put(cells[1].trim(), Long.parseLong(cells[col].trim()));
    }
    assertTrue(!unique.isEmpty(), "no table rows in output:\n" + output);
    return unique;
  }
}

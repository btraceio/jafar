package io.jafar.shell.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Metadata is per chunk, and chunks can disagree about a class. {@code loadAllClasses} returned one
 * row per class per chunk (340 rows for 304 classes in {@code tck-test.jfr}), and {@code loadClass}
 * returned the last chunk's definition, which for {@code java.lang.Thread} lacks {@code group} and
 * {@code virtual}: chunk 1 declares them, chunk 2 does not.
 *
 * <p>Both now return one class per name whose fields are the union over the chunks, each field
 * saying which chunks define it. The oracle reads every chunk's metadata straight from the parser.
 */
class MetadataMergeTest {

  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  /** class name -> field name -> chunks defining it; class names in first-seen order. */
  private static final Map<String, Map<String, List<Integer>>> fieldChunks = new LinkedHashMap<>();

  /** class name -> chunks defining it. */
  private static final Map<String, List<Integer>> classChunks = new LinkedHashMap<>();

  @BeforeAll
  static void readEveryChunksMetadata() throws Exception {
    Map<Integer, Map<String, List<String>>> fieldsByChunk = new ConcurrentSkipListMap<>();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          TCK_RECORDING,
          new ChunkParserListener() {
            @Override
            public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
              Map<String, List<String>> classes = new LinkedHashMap<>();
              for (MetadataClass c : metadata.getClasses()) {
                List<String> names = new ArrayList<>();
                for (MetadataField f : c.getFields()) names.add(f.getName());
                classes.put(c.getName(), names);
              }
              fieldsByChunk.put(context.getChunkIndex(), classes);
              return false;
            }
          });
    }
    fieldsByChunk.forEach(
        (chunk, classes) ->
            classes.forEach(
                (cls, names) -> {
                  classChunks.computeIfAbsent(cls, k -> new ArrayList<>()).add(chunk);
                  Map<String, List<Integer>> byField =
                      fieldChunks.computeIfAbsent(cls, k -> new LinkedHashMap<>());
                  for (String n : names)
                    byField.computeIfAbsent(n, k -> new ArrayList<>()).add(chunk);
                }));
    // The fixture must exercise the merge: a class whose chunks declare different fields.
    assertEquals(List.of(1, 2), classChunks.get("java.lang.Thread"));
    assertEquals(List.of(1), fieldChunks.get("java.lang.Thread").get("group"));
  }

  @Test
  void eachClassAppearsOnce() throws Exception {
    List<Object> names = new ArrayList<>();
    for (Map<String, Object> c : MetadataProvider.loadAllClasses(TCK_RECORDING)) {
      names.add(c.get("name"));
    }
    assertEquals(new ArrayList<>(classChunks.keySet()), names);
  }

  @Test
  void fieldsAreTheUnionOverChunksAndSayWhereTheyComeFrom() throws Exception {
    Map<String, Map<String, List<Integer>>> shown = new TreeMap<>();
    Map<String, Object> shownClassChunks = new TreeMap<>();
    for (Map<String, Object> c : MetadataProvider.loadAllClasses(TCK_RECORDING)) {
      Map<String, List<Integer>> byField = new LinkedHashMap<>();
      ((Map<?, ?>) c.get("fieldsByName"))
          .forEach((n, fm) -> byField.put((String) n, chunksOf(((Map<?, ?>) fm).get("chunks"))));
      shown.put((String) c.get("name"), byField);
      shownClassChunks.put((String) c.get("name"), chunksOf(c.get("chunks")));
    }
    assertEquals(new TreeMap<>(fieldChunks), shown);
    assertEquals(new TreeMap<>(classChunks), shownClassChunks);
  }

  @Test
  void loadClassKeepsFieldsOnlyAnEarlierChunkDeclares() throws Exception {
    Map<String, Object> thread = MetadataProvider.loadClass(TCK_RECORDING, "java.lang.Thread");
    Map<?, ?> fields = (Map<?, ?>) thread.get("fieldsByName");
    assertEquals(List.of(1), chunksOf(((Map<?, ?>) fields.get("group")).get("chunks")));
    assertEquals(List.of(1, 2), chunksOf(((Map<?, ?>) fields.get("javaName")).get("chunks")));
    assertTrue(
        ((List<?>) thread.get("fields"))
            .stream()
                .map(String::valueOf)
                .anyMatch(
                    l -> l.startsWith("group:jdk.types.ThreadGroup") && l.endsWith(" [chunks: 1]")),
        "display: " + thread.get("fields"));
  }

  @Test
  void loadClassAgreesWithLoadAllClasses() throws Exception {
    for (Map<String, Object> c : MetadataProvider.loadAllClasses(TCK_RECORDING)) {
      String name = (String) c.get("name");
      assertEquals(c, MetadataProvider.loadClass(TCK_RECORDING, name), name);
    }
  }

  private static List<Integer> chunksOf(Object o) {
    List<Integer> out = new ArrayList<>();
    if (o instanceof List<?> l) for (Object x : l) out.add(((Number) x).intValue());
    return out;
  }
}

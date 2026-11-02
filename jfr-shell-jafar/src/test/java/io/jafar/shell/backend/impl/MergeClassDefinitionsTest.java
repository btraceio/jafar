package io.jafar.shell.backend.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The merge's conflict paths, which the checked-in recording does not exercise: its chunks only add
 * fields, never retype one. Recording-level behaviour is covered by {@code MetadataMergeTest} in
 * jfr-shell.
 */
class MergeClassDefinitionsTest {

  @Test
  void conflictingFieldTypesAreReportedNotResolved() {
    SortedMap<Integer, Map<String, Object>> defs = new TreeMap<>();
    defs.put(1, def(10, "x.Event", "jdk.jfr.Event", field("id", "long", -1)));
    defs.put(3, def(42, "x.Event", "jdk.jfr.Event", field("id", "java.lang.String", 1)));

    Map<String, Object> merged = JafarSources.JafarMetadataSource.mergeClassDefinitions(defs);

    Map<?, ?> id = (Map<?, ?>) ((Map<?, ?>) merged.get("fieldsByName")).get("id");
    assertEquals("long", id.get("type"), "first chunk's type is kept");
    assertEquals(Map.of(1, "long", 3, "java.lang.String[]"), id.get("typesByChunk"));
    assertEquals(List.of(1, 3), id.get("chunks"));
    assertEquals(
        List.of("id:long [types by chunk: 1=long, 3=java.lang.String[]]"), merged.get("fields"));
    assertEquals(10L, merged.get("id"), "first chunk's id");
    assertEquals(List.of(1, 3), merged.get("chunks"));
    assertFalse(merged.containsKey("superTypesByChunk"));
  }

  @Test
  void conflictingSuperTypesAreReported() {
    SortedMap<Integer, Map<String, Object>> defs = new TreeMap<>();
    defs.put(1, def(1, "x.T", null));
    defs.put(2, def(2, "x.T", "jdk.jfr.Event"));

    Map<String, Object> merged = JafarSources.JafarMetadataSource.mergeClassDefinitions(defs);

    assertEquals(null, merged.get("superType"));
    Map<Integer, Object> expected = new HashMap<>();
    expected.put(1, null);
    expected.put(2, "jdk.jfr.Event");
    assertEquals(expected, merged.get("superTypesByChunk"));
  }

  @Test
  void fieldsAreUnitedInDeclarationOrder() {
    SortedMap<Integer, Map<String, Object>> defs = new TreeMap<>();
    defs.put(1, def(1, "x.T", null, field("a", "int", -1), field("b", "int", -1)));
    defs.put(2, def(2, "x.T", null, field("c", "int", -1), field("a", "int", -1)));

    Map<String, Object> merged = JafarSources.JafarMetadataSource.mergeClassDefinitions(defs);

    assertEquals(List.of("a:int", "b:int [chunks: 1]", "c:int [chunks: 2]"), merged.get("fields"));
    assertEquals(3, merged.get("fieldCount"));
  }

  @Test
  void aSingleChunkIsItsDefinitionPlusChunks() {
    SortedMap<Integer, Map<String, Object>> defs = new TreeMap<>();
    defs.put(7, def(5, "x.T", null, field("a", "int", -1)));

    Map<String, Object> merged = JafarSources.JafarMetadataSource.mergeClassDefinitions(defs);

    assertEquals(List.of("a:int"), merged.get("fields"));
    assertEquals(List.of(7), merged.get("chunks"));
    Map<?, ?> a = (Map<?, ?>) ((Map<?, ?>) merged.get("fieldsByName")).get("a");
    assertEquals(List.of(7), a.get("chunks"));
    assertFalse(a.containsKey("typesByChunk"));
  }

  /** The shape {@code convertClass} produces, minus settings and annotations. */
  @SafeVarargs
  private static Map<String, Object> def(
      long id, String name, String superType, Map<String, Object>... fields) {
    Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
    for (Map<String, Object> f : fields) byName.put((String) f.get("name"), f);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("name", name);
    m.put("superType", superType);
    m.put("fieldsByName", byName);
    return m;
  }

  private static Map<String, Object> field(String name, String type, int dimension) {
    Map<String, Object> f = new HashMap<>();
    f.put("name", name);
    f.put("type", type);
    f.put("dimension", dimension);
    f.put("annotations", List.of());
    f.put("annotationsFull", List.of());
    return f;
  }
}

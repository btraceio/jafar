package io.jafar.shell.live;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * JSON-lines validity for streamed events ({@code tail --format json}): non-finite doubles/floats
 * serialize to literal {@code null} (never a raw {@code NaN}/{@code Infinity} token), control
 * characters escape byte-identically to the previous per-character {@code String.format} output,
 * and every map entry is emitted (no dead {@code __eventType} skip block).
 */
class JsonLinesTest {

  private static String json(Map<String, Object> m) {
    return JsonLines.toJson("jdk.FileRead", m);
  }

  @Test
  void nonFiniteNumbersSerializeAsNull() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("d", Double.NaN);
    String line = json(m);
    assertEquals("{\"type\":\"jdk.FileRead\",\"d\":null}", line);
    assertFalse(line.contains("NaN"), line);

    m.put("d", Double.POSITIVE_INFINITY);
    line = json(m);
    assertFalse(line.contains("Infinity"), line);

    m.put("f", Float.NEGATIVE_INFINITY);
    line = json(m);
    assertFalse(line.contains("-Infinity"), line);
    assertTrue(line.contains("\"f\":null"), line);
  }

  @Test
  void nonFiniteInsideNestedStructuresAlsoSerializeAsNull() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("ratio", Double.NaN);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nested", inner);
    String line = json(m);
    assertTrue(line.contains("\"ratio\":null"), line);
    assertFalse(line.contains("NaN"), line);
  }

  @Test
  void controlCharactersEscapeByteIdentically() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("s", "\u0001\u001f");
    assertEquals("{\"type\":\"jdk.FileRead\",\"s\":\"\\u0001\\u001f\"}", json(m));
  }

  @Test
  void everyMapEntryIsEmitted() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("a", 1);
    m.put("__eventType", "user-data"); // a real field of that name is plain data now
    String line = json(m);
    assertTrue(line.contains("\"a\":1"), line);
    assertTrue(line.contains("\"__eventType\":\"user-data\""), line);
  }

  @Test
  void finiteOutputIsByteIdentical() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("i", 42);
    m.put("d", 1.5);
    m.put("b", true);
    m.put("s", "text");
    m.put("n", null);
    assertEquals(
        "{\"type\":\"jdk.FileRead\",\"i\":42,\"d\":1.5,\"b\":true,\"s\":\"text\",\"n\":null}",
        json(m));
  }
}

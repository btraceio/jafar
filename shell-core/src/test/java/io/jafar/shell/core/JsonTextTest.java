package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #266: {@code show --format json} wrote a Java array, such as a stack trace's {@code frames}, as a
 * string of its {@code toString} text instead of a JSON array.
 */
class JsonTextTest {

  @Test
  void anObjectArrayOfMapsIsAJsonArrayOfObjects() throws IOException {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("method", Map.of("name", "libmPow"));
    frame.put("lineNumber", 42);
    Map<String, Object> row = Map.of("stackTrace", Map.of("frames", new Object[] {frame, frame}));

    JsonElement json = strict(JsonText.pretty(List.of(row)));

    JsonElement frames =
        json.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("stackTrace").get("frames");
    assertTrue(frames.isJsonArray(), frames.toString());
    assertEquals(2, frames.getAsJsonArray().size());
    assertEquals(
        "libmPow",
        frames
            .getAsJsonArray()
            .get(1)
            .getAsJsonObject()
            .getAsJsonObject("method")
            .get("name")
            .getAsString());
  }

  @Test
  void primitiveArraysAreArraysOfNumbersOrBooleans() throws IOException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ints", new int[] {1, 2});
    row.put("longs", new long[] {3L});
    row.put("flags", new boolean[] {true, false});
    row.put("bytes", new byte[] {-1});
    row.put("chars", new char[] {'a'});

    assertEquals(
        "{\"ints\":[1,2],\"longs\":[3],\"flags\":[true,false],\"bytes\":[-1],\"chars\":[\"a\"]}",
        compact(row));
    strict(JsonText.pretty(row));
  }

  @Test
  void nonFiniteNumbersAreNull() throws IOException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("nan", Double.NaN);
    row.put("inf", Float.POSITIVE_INFINITY);
    row.put("values", new double[] {1.5, Double.NEGATIVE_INFINITY});

    assertEquals("{\"nan\":null,\"inf\":null,\"values\":[1.5,null]}", compact(row));
    strict(JsonText.pretty(row));
  }

  @Test
  void theIndentedLayoutIsUnchanged() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", "a\"b\n");
    row.put("list", List.of(1, Map.of("k", true)));

    assertEquals(
        String.join(
            "\n",
            "[",
            "  {",
            "    \"name\": \"a\\\"b\\n\",",
            "    \"list\": [",
            "      1,",
            "      {",
            "        \"k\": true",
            "      }",
            "    ]",
            "  }",
            "]"),
        JsonText.pretty(List.of(row)));
  }

  @Test
  void parserWrappersAreWrittenAsWhatTheyHold() {
    ArrayType frames =
        new ArrayType() {
          @Override
          public Class<?> getType() {
            return Object.class;
          }

          @Override
          public Object getArray() {
            return new Object[] {(ComplexType) () -> Map.of("lineNumber", 7)};
          }

          @Override
          public String toString() {
            return "[toString text]";
          }
        };

    assertEquals("{\"frames\":[{\"lineNumber\":7}]}", compact(Map.of("frames", frames)));
  }

  @Test
  void emptyContainersAndEscapedKeysKeepTheOldLayout() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("a\"b", List.of());
    row.put("empty", Map.of());

    assertEquals("{\n  \"a\\\"b\": [\n\n  ],\n  \"empty\": {\n\n  }\n}", JsonText.pretty(row));
  }

  @Test
  void anIterableThatIsNotACollectionIsItsString() {
    // A one-name Path iterates to itself: as an array it would never end
    assertEquals("{\"p\":\"a\"}", compact(Map.of("p", java.nio.file.Path.of("a"))));
    assertEquals("{\n  \"p\": \"a\"\n}", JsonText.pretty(Map.of("p", java.nio.file.Path.of("a"))));
  }

  @Test
  void nestedKeysAreEscapedOnOneLineToo() {
    assertEquals("{\"m\":{\"a\\\"b\":1}}", compact(Map.of("m", Map.of("a\"b", 1))));
  }

  @Test
  void controlCharactersAreEscaped() {
    assertEquals("a\\u0001\\tb\\\\", JsonText.escape("a\u0001\tb\\"));
  }

  private static String compact(Object value) {
    StringBuilder sb = new StringBuilder();
    JsonText.appendCompact(sb, value);
    return sb.toString();
  }

  /** Parses {@code json} strictly: NaN, a bare word or a trailing comma fails. */
  private static JsonElement strict(String json) throws IOException {
    JsonReader reader = new JsonReader(new StringReader(json));
    reader.setLenient(false);
    return new Gson().getAdapter(JsonElement.class).read(reader);
  }
}

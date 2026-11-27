package io.jafar.mcp.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlainValuesTest {

  @Test
  void wrappersBecomeWhatTheyHold() {
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
        };

    Map<String, Object> plain = PlainValues.of(Map.of("frames", frames));

    assertEquals(List.of(Map.of("lineNumber", 7)), plain.get("frames"));
  }

  @Test
  void arraysAreListsAndNonFiniteNumbersNull() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ints", new int[] {1, 2});
    data.put("values", new double[] {1.5, Double.NaN});
    data.put("inf", Float.POSITIVE_INFINITY);
    data.put("nested", List.of(Map.of("x", Double.NEGATIVE_INFINITY)));

    Map<String, Object> plain = PlainValues.of(data);

    assertEquals(List.of(1, 2), plain.get("ints"));
    assertEquals(Arrays.asList(1.5, null), plain.get("values"));
    assertNull(plain.get("inf"));
    assertNull(((Map<?, ?>) ((List<?>) plain.get("nested")).get(0)).get("x"));
  }

  @Test
  void nestedMapsKeepTheirKeyOrder() {
    Map<String, Object> inner = new LinkedHashMap<>();
    List<String> keys = new java.util.ArrayList<>();
    for (int i = 40; i > 0; i--) {
      keys.add("k" + i);
      inner.put("k" + i, i);
    }

    Map<?, ?> plain = (Map<?, ?>) PlainValues.of(Map.of("inner", inner)).get("inner");

    assertEquals(keys, List.copyOf(plain.keySet().stream().map(String::valueOf).toList()));
  }

  @Test
  void largeLongsAndNullValuesAreKept() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("startTime", 1_732_700_000_000_000_000L);
    row.put("classLoader", null);

    Map<String, Object> plain = PlainValues.of(row);

    assertEquals(1_732_700_000_000_000_000L, plain.get("startTime"));
    assertEquals(true, plain.containsKey("classLoader"));
    assertNull(plain.get("classLoader"));
  }
}

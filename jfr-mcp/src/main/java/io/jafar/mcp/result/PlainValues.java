package io.jafar.mcp.result;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool result as plain JSON values (#283). Rows from the untyped parser hold its wrappers: an
 * {@link ArrayType} for an array (a stack trace's {@code frames}) and a {@link ComplexType} for a
 * constant-pool reference. Jackson writes those as beans ({@code {"array": ..., "type": ...}},
 * {@code {"simpleType": ..., "value": ...}}), and the MCP SDK writes the structured content the
 * same way. Here a wrapper becomes what it holds, a map stays a map, a collection or Java array
 * becomes a list, and a non-finite {@code double} or {@code float} becomes {@code null} (Jackson
 * wrote it as the string {@code "NaN"}, a string where a number belongs). Constants nested inside
 * keep their wrapper maps ({@code {"string": "libmPow"}}). Map keys keep their order.
 */
final class PlainValues {

  private PlainValues() {}

  /** {@code data} with every value made plain; a new map, {@code data} is not changed. */
  static Map<String, Object> of(Map<String, Object> data) {
    Map<String, Object> out = new LinkedHashMap<>(Math.max(16, data.size() * 2));
    for (Map.Entry<String, Object> e : data.entrySet()) {
      out.put(e.getKey(), plain(e.getValue()));
    }
    return out;
  }

  static Object plain(Object value) {
    if (value instanceof ArrayType array) {
      value = array.getArray();
    } else if (value instanceof ComplexType complex) {
      value = complex.getValue();
    }
    if (value == null) {
      return null;
    }
    if (value instanceof Double d) {
      return Double.isFinite(d) ? d : null;
    }
    if (value instanceof Float f) {
      return Float.isFinite(f) ? f : null;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>(Math.max(16, map.size() * 2));
      for (Map.Entry<?, ?> e : map.entrySet()) {
        out.put(String.valueOf(e.getKey()), plain(e.getValue()));
      }
      return out;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> out = new ArrayList<>(collection.size());
      for (Object element : collection) {
        out.add(plain(element));
      }
      return out;
    }
    if (value.getClass().isArray()) {
      int n = Array.getLength(value);
      List<Object> out = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        out.add(plain(Array.get(value, i)));
      }
      return out;
    }
    return value;
  }
}

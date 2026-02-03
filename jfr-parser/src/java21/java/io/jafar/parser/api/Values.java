package io.jafar.parser.api;

import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Values {
  private Values() {}

  @SuppressWarnings("unchecked")
  public static Object get(Map<String, Object> root, Object... path) {
    Objects.requireNonNull(root, "root");
    Object current = root;
    if (path == null || path.length == 0) {
      return current;
    }
    for (Object segment : path) {
      if (current instanceof ComplexType complex) {
        current = complex.getValue();
      }
      if (segment instanceof CharSequence) {
        String key = segment.toString();
        if (current instanceof Map<?, ?> map) {
          current = ((Map<String, Object>) map).get(key);
        } else {
          return null;
        }
      } else if (segment instanceof Integer idx) {
        Object array = unwrapArray(current);
        if (array == null) return null;
        int length = Array.getLength(array);
        if (idx < 0 || idx >= length) return null;
        current = Array.get(array, idx);
      } else {
        throw new IllegalArgumentException("Unsupported path segment type: " + segment);
      }
    }
    return unwrapArray(unwrapComplex(current));
  }

  public static <T> Optional<T> as(Map<String, Object> root, Class<T> type, Object... path) {
    Object value = get(root, path);
    return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
  }

  public static Map<String, Object> resolvedShallow(Map<String, Object> root) {
    Map<String, Object> out = new HashMap<>(root.size());
    for (Map.Entry<String, Object> e : root.entrySet()) {
      Object v = e.getValue();
      if (v instanceof ComplexType c) {
        v = c.getValue();
      }
      v = unwrapArray(v);
      out.put(e.getKey(), v);
    }
    return out;
  }

  public static Map<String, Object> resolvedDeep(Map<String, Object> root) {
    Map<String, Object> out = new HashMap<>(root.size());
    for (Map.Entry<String, Object> e : root.entrySet()) {
      Object v = e.getValue();
      v = resolveDeepValue(v);
      out.put(e.getKey(), v);
    }
    return out;
  }

  private static Object resolveDeepValue(Object v) {
    if (v instanceof ComplexType c) {
      return resolvedDeep(c.getValue());
    }
    Object array = unwrapArray(v);
    if (array != null && array.getClass().isArray()) {
      Class<?> componentType = array.getClass().getComponentType();
      if (componentType.isPrimitive()) {
        return array;
      }
      Object[] in = (Object[]) array;
      Object[] out = new Object[in.length];
      for (int i = 0; i < in.length; i++) {
        out[i] = resolveDeepValue(in[i]);
      }
      return out;
    }
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> res = new HashMap<>(((Map<?, ?>) m).size());
      for (Map.Entry<?, ?> me : ((Map<?, ?>) m).entrySet()) {
        res.put(String.valueOf(me.getKey()), resolveDeepValue(me.getValue()));
      }
      return res;
    }
    return v;
  }

  private static Object unwrapArray(Object value) {
    if (value instanceof ArrayType at) {
      return at.getArray();
    }
    return value;
  }

  private static Object unwrapComplex(Object value) {
    if (value instanceof ComplexType ct) {
      return ct.getValue();
    }
    return value;
  }
}

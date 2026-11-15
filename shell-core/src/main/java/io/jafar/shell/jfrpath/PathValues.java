package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.Values;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Path-token parsing and array-aware value extraction shared by projection, aggregation and
 * expression evaluation.
 */
final class PathValues {
  // Indexing and tail-slice support for projection. Slice [start:end] supported only on last
  // segment.
  static void extractWithIndexing(Map<String, Object> root, List<String> proj, List<Object> out) {
    if (proj.isEmpty()) return;
    String last = proj.get(proj.size() - 1);
    Slice sl = parseSlice(last);
    if (sl != null) {
      // Resolve up to base of last segment
      String base = baseName(last);
      List<String> head = new java.util.ArrayList<>(proj);
      head.set(head.size() - 1, base);
      Object arrVal = Values.get(root, buildPathTokens(head).toArray());
      Object arr = unwrapArrayLike(arrVal);
      if (arr == null) return;
      int len = java.lang.reflect.Array.getLength(arr);
      int from = Math.max(0, sl.start());
      int to = sl.end() < 0 ? len : Math.min(len, sl.end());
      for (int i = from; i < to; i++) {
        out.add(java.lang.reflect.Array.get(arr, i));
      }
      return;
    }
    // Try new recursive array iteration approach
    extractWithArrayIteration(root, buildPathTokens(proj), out);
  }

  /**
   * Recursively extract values with automatic array iteration. When navigating a path encounters an
   * array and the next segment is a field name (not an Integer index), automatically iterates the
   * array and continues navigating on each element.
   *
   * @param current the current value to navigate from
   * @param pathTokens remaining path tokens (Strings for fields, Integers for indices)
   * @param out output list to collect results
   */
  @SuppressWarnings("unchecked")
  static void extractWithArrayIteration(Object current, List<Object> pathTokens, List<Object> out) {
    if (pathTokens.isEmpty()) {
      // Reached end of path
      if (current != null) {
        // Unwrap ComplexType and ArrayType for final result
        if (current instanceof ComplexType ct) {
          current = ct.getValue();
        }
        Object unwrapped = unwrapArrayLike(current);
        if (unwrapped != null) {
          out.add(unwrapped);
        } else {
          out.add(current);
        }
      }
      return;
    }

    // Unwrap ComplexType
    if (current instanceof ComplexType ct) {
      current = ct.getValue();
    }

    Object firstToken = pathTokens.get(0);
    List<Object> remainingTokens = pathTokens.subList(1, pathTokens.size());

    // Handle slice - a range of elements, fanned out like array iteration
    if (firstToken instanceof Slice sl) {
      Object arr = unwrapArrayLike(current);
      if (arr != null) {
        int len = java.lang.reflect.Array.getLength(arr);
        int from = Math.max(0, sl.start());
        int to = sl.end() < 0 ? len : Math.min(len, sl.end());
        for (int i = from; i < to; i++) {
          extractWithArrayIteration(java.lang.reflect.Array.get(arr, i), remainingTokens, out);
        }
      }
      return;
    }

    // Handle Integer index - explicit array access
    if (firstToken instanceof Integer idx) {
      Object arr = unwrapArrayLike(current);
      if (arr != null) {
        int len = java.lang.reflect.Array.getLength(arr);
        if (idx >= 0 && idx < len) {
          Object element = java.lang.reflect.Array.get(arr, idx);
          extractWithArrayIteration(element, remainingTokens, out);
        }
      }
      return;
    }

    // Handle String key - field access
    if (firstToken instanceof String key) {
      // Check if current is an array WITHOUT an explicit index
      Object arr = unwrapArrayLike(current);
      if (arr != null) {
        // Automatic array iteration: navigate remaining path on each element
        int len = java.lang.reflect.Array.getLength(arr);
        for (int i = 0; i < len; i++) {
          Object element = java.lang.reflect.Array.get(arr, i);
          // Continue with the FULL remaining path (including current key)
          extractWithArrayIteration(element, pathTokens, out);
        }
        return;
      }

      // Current is not an array, try map access
      if (current instanceof Map<?, ?> map) {
        Map<String, Object> m = (Map<String, Object>) map;
        Object value = m.get(key);
        extractWithArrayIteration(value, remainingTokens, out);
        return;
      }
    }
  }

  /**
   * Helper to extract all values from a map using a path with automatic array iteration. Returns a
   * list of all matching values (may be empty if path doesn't match).
   *
   * @param map the root map
   * @param pathTokens path tokens (Strings for fields, Integers for indices)
   * @return list of extracted values (may contain multiple results if path traverses arrays)
   */
  static List<Object> extractAllValues(Map<String, Object> map, List<Object> pathTokens) {
    List<Object> results = new ArrayList<>();
    extractWithArrayIteration(map, pathTokens, results);
    return results;
  }

  /**
   * Resolve exactly one value for a path of raw segments: {@code name[i]} steps into the element, a
   * tail slice {@code name[a:b]} yields a List of the ranged elements, and an unmatched path yields
   * {@code null}. Unlike {@link #extractAllValues} this never fans out over unindexed arrays —
   * select() and expression field references keep one value per row.
   */
  static Object resolveSingle(Map<String, Object> root, List<String> segments) {
    return resolveSingleValue(root, buildPathTokens(segments));
  }

  private static Object resolveSingleValue(Map<String, Object> root, List<Object> tokens) {
    if (tokens.isEmpty()) return null;
    Object current = root;
    for (int i = 0; i < tokens.size(); i++) {
      Object t = tokens.get(i);
      if (current instanceof ComplexType ct) {
        current = ct.getValue();
      }
      if (t instanceof Slice sl) {
        // buildPathTokens only emits slices as the tail token
        Object arr = unwrapArrayLike(current);
        if (arr == null) return null;
        int len = java.lang.reflect.Array.getLength(arr);
        int from = Math.max(0, sl.start());
        int to = sl.end() < 0 ? len : Math.min(len, sl.end());
        List<Object> out = new ArrayList<>(Math.max(0, to - from));
        for (int j = from; j < to; j++) {
          out.add(java.lang.reflect.Array.get(arr, j));
        }
        return out;
      }
      if (t instanceof Integer idx) {
        // unwrapArrayLike, not Values.get: rows may hold Collections (JDK backend shape)
        Object arr = unwrapArrayLike(current);
        if (arr == null) return null;
        int len = java.lang.reflect.Array.getLength(arr);
        if (idx < 0 || idx >= len) return null;
        current = java.lang.reflect.Array.get(arr, idx);
      } else {
        if (!(current instanceof Map)) return null;
        current = ((Map<?, ?>) current).get(t.toString());
      }
    }
    if (current instanceof ComplexType ct) {
      current = ct.getValue();
    }
    if (current instanceof ArrayType at) {
      return at.getArray();
    }
    return current;
  }

  static List<Object> buildPathTokens(List<String> segs) {
    List<Object> tokens = new ArrayList<>();
    for (int si = 0; si < segs.size(); si++) {
      String seg = segs.get(si);
      int b = seg.indexOf('[');
      if (b < 0) {
        // No brackets - check if segment is a numeric index
        try {
          int index = Integer.parseInt(seg);
          tokens.add(index);
        } catch (NumberFormatException e) {
          // Not a number, add as string
          tokens.add(seg);
        }
        continue;
      }
      String name = seg.substring(0, b);
      if (!name.isEmpty()) tokens.add(name);
      // Scan consecutive [..] groups. A slice is honoured only in the last group of the last
      // segment; a slice elsewhere is rejected with an error instead of being silently ignored.
      int i = b;
      while (i < seg.length() && seg.charAt(i) == '[') {
        int e = seg.indexOf(']', i);
        if (e < 0) break;
        String inside = seg.substring(i + 1, e).trim();
        boolean lastGroup = e == seg.length() - 1 && si == segs.size() - 1;
        if (inside.contains(":")) {
          if (!lastGroup) {
            throw new IllegalArgumentException(
                "slices are only supported on the last bracket group of the last path segment: got '"
                    + seg
                    + "' in '"
                    + String.join("/", segs)
                    + "'");
          }
          Slice sl = parseSlice("[" + inside + "]");
          if (sl != null) tokens.add(sl);
        } else {
          try {
            tokens.add(Integer.parseInt(inside));
          } catch (Exception ignore) {
          }
        }
        i = e + 1;
      }
    }
    return tokens;
  }

  static String baseName(String seg) {
    int b = seg.indexOf('[');
    return b < 0 ? seg : seg.substring(0, b);
  }

  private static Slice parseSlice(String seg) {
    int b = seg.indexOf('[');
    int e = seg.endsWith("]") ? seg.lastIndexOf(']') : -1;
    if (b < 0 || e < b) return null;
    String inside = seg.substring(b + 1, e);
    int c = inside.indexOf(':');
    if (c < 0) return null;
    int from = 0, to = -1;
    String a = inside.substring(0, c).trim();
    String bb = inside.substring(c + 1).trim();
    if (!a.isEmpty())
      try {
        from = Integer.parseInt(a);
      } catch (Exception ignore) {
      }
    if (!bb.isEmpty())
      try {
        to = Integer.parseInt(bb);
      } catch (Exception ignore) {
      }
    return new Slice(from, to);
  }

  static Object unwrapArrayLike(Object v) {
    if (v == null) return null;
    if (v.getClass().isArray()) return v;
    if (v instanceof java.util.Collection<?> c) return c.toArray();
    if (v instanceof ArrayType at) return at.getArray();
    return null;
  }

  /**
   * Recursively unwraps "simple types" - maps with exactly one attribute. Continues unwrapping
   * until reaching a non-map value or a map with multiple attributes.
   */
  static Object unwrapSimpleType(Object v) {
    if (System.getProperty("jfr.shell.query.debug") != null) {
      System.err.println("[DEBUG] unwrapSimpleType() called");
      System.err.println("[DEBUG]   input: " + v);
      System.err.println("[DEBUG]   input type: " + (v != null ? v.getClass().getName() : "null"));
      if (v instanceof java.util.Map<?, ?> m) {
        System.err.println("[DEBUG]   is Map: true, size=" + m.size() + ", keys=" + m.keySet());
      }
    }

    while (v instanceof java.util.Map<?, ?> m) {
      if (m.size() == 1) {
        // Get the single value and recurse
        Object next = m.values().iterator().next();
        if (System.getProperty("jfr.shell.query.debug") != null) {
          System.err.println("[DEBUG]   unwrapping single-key map, next value: " + next);
        }
        v = next;
      } else {
        // Multiple attributes - stop unwrapping
        if (System.getProperty("jfr.shell.query.debug") != null) {
          System.err.println("[DEBUG]   stopping unwrap: map has " + m.size() + " keys");
        }
        break;
      }
    }

    if (System.getProperty("jfr.shell.query.debug") != null) {
      System.err.println("[DEBUG]   unwrapped result: " + v);
      System.err.println("[DEBUG]   result type: " + (v != null ? v.getClass().getName() : "null"));
    }

    return v;
  }

  private record Slice(int start, int end) {}

  static Integer valueLength(Object val) {
    if (val == null) return null;
    if (val instanceof CharSequence s) return s.length();
    if (val instanceof java.util.Collection<?> c) return c.size();
    Object arr = val;
    if (arr.getClass().isArray()) return java.lang.reflect.Array.getLength(arr);
    return null;
  }
}

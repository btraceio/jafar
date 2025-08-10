package io.jafar.parser.api;

import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Utilities to navigate and extract values from untyped event maps.
 *
 * - Transparently unwraps {@link ComplexType} to its {@code Map<String, Object>} value
 * - Transparently unwraps {@link ArrayType} to its backing Java array when returned
 * - Supports nested navigation using a path of keys/indices
 */
public final class Values {
    private Values() {}

    /**
     * Navigate a nested value using a sequence of keys/indices.
     * Each path element may be a {@link String} key (for maps) or an {@link Integer} index (for arrays).
     *
     * - {@link ComplexType} nodes are unwrapped to {@code Map<String, Object>} automatically.
     * - {@link ArrayType} nodes are unwrapped to the backing array automatically when returned or indexed.
     *
     * @param root the root event map
     * @param path the path of keys/indices to follow
     * @return the located value or {@code null} if the path cannot be followed
     */
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

    /**
     * Typed extraction using {@link #get(Map, Object...)}.
     * Returns an empty optional if the resolved value is null or not of the requested type.
     */
    public static <T> Optional<T> as(Map<String, Object> root, Class<T> type, Object... path) {
        Object value = get(root, path);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    /**
     * Shallowly resolve a map: unwraps {@link ComplexType} to maps and {@link ArrayType} to arrays at the top level only.
     */
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

    /**
     * Deeply resolve a map: recursively unwraps {@link ComplexType}; unwraps {@link ArrayType} to arrays;
     * recursively resolves elements of {@code Object[]} arrays. Primitive arrays are left as-is.
     */
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
                return array; // leave primitive arrays as-is to avoid boxing
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

    /**
     * If the value is an {@link ArrayType}, return its backing array; otherwise return the value unchanged.
     */
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



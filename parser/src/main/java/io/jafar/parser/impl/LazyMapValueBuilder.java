package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.ValueProcessor;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.HashMap;
import java.util.Map;

/**
 * Value processor that builds a lazy event map (Tier 2 optimization with TLS array reuse).
 *
 * <p>This builder collects field names and values into reusable thread-local arrays during parsing,
 * then creates a {@link LazyEventMap} that defers HashMap materialization until fields are
 * accessed.
 *
 * <p>Key optimizations:
 *
 * <ul>
 *   <li>Reuses pre-sized arrays from ThreadLocal pool (no allocation per event)
 *   <li>No ArrayList overhead (direct array indexing)
 *   <li>No array resizing (pre-sized to typical event field count)
 * </ul>
 */
public final class LazyMapValueBuilder implements ValueProcessor {
  // Thread-local reusable arrays for field collection
  public static final ThreadLocal<ArrayPool> ARRAY_POOL = ThreadLocal.withInitial(ArrayPool::new);

  private final ParserContext context;
  private final MultiTypeStack stack = new MultiTypeStack(20);
  private Map<String, Object> root;

  LazyMapValueBuilder(ParserContext context) {
    this.context = context;
  }

  /** Thread-local array pool for reusable field collection arrays. */
  public static class ArrayPool {
    // Pre-sized for typical JFR event (10-20 fields)
    private static final int INITIAL_CAPACITY = 24;

    public String[] keys = new String[INITIAL_CAPACITY];
    public Object[] values = new Object[INITIAL_CAPACITY];
    public int size = 0;

    public void reset() {
      // Clear references to allow GC
      for (int i = 0; i < size; i++) {
        keys[i] = null;
        values[i] = null;
      }
      size = 0;
    }

    public void add(String key, Object value) {
      if (size >= keys.length) {
        // Grow arrays if needed (rare case for events with many fields)
        int newCapacity = keys.length * 2;
        String[] newKeys = new String[newCapacity];
        Object[] newValues = new Object[newCapacity];
        System.arraycopy(keys, 0, newKeys, 0, keys.length);
        System.arraycopy(values, 0, newValues, 0, values.length);
        keys = newKeys;
        values = newValues;
      }
      keys[size] = key;
      values[size] = value;
      size++;
    }

    String[] getKeys() {
      String[] result = new String[size];
      System.arraycopy(keys, 0, result, 0, size);
      return result;
    }

    Object[] getValues() {
      Object[] result = new Object[size];
      System.arraycopy(values, 0, result, 0, size);
      return result;
    }
  }

  void reset() {
    root = null;
  }

  Map<String, Object> getRoot() {
    return root;
  }

  @Override
  public void onStringValue(MetadataClass owner, String fld, String value) {
    addFieldValue(fld, value);
  }

  @Override
  public void onShortValue(MetadataClass type, String fld, short value) {
    addFieldValue(fld, value);
  }

  @Override
  public void onCharValue(MetadataClass type, String fld, char value) {
    addFieldValue(fld, value);
  }

  @Override
  public void onIntValue(MetadataClass owner, String fld, long value) {
    addFieldValue(fld, value);
  }

  @Override
  public void onLongValue(MetadataClass type, String fld, long value) {
    addFieldValue(fld, value);
  }

  @Override
  public void onByteValue(MetadataClass type, String fld, byte value) {
    addFieldValue(fld, value);
  }

  @Override
  public void onBooleanValue(MetadataClass owner, String fld, boolean value) {
    addFieldValue(fld, value);
  }

  @Override
  public void onDoubleValue(MetadataClass owner, String fld, double value) {
    addFieldValue(fld, value);
  }

  @Override
  public void onFloatValue(MetadataClass owner, String fld, float value) {
    addFieldValue(fld, value);
  }

  @Override
  public void onComplexValueStart(MetadataClass owner, String fld, MetadataClass type) {
    // Check if this is the root event (owner and fld are null for root)
    if (owner == null && fld == null) {
      // Root event - use ArrayPool for lazy map
      ArrayPool pool = ARRAY_POOL.get();
      pool.reset();
      stack.push(pool);
    } else {
      // Nested complex object - use regular HashMap
      Map<String, Object> value = new HashMap<>();
      stack.push(value);
    }
  }

  @Override
  public void onComplexValueEnd(MetadataClass owner, String fld, MetadataClass type) {
    // Try to pop as ArrayPool first, then as Map
    ArrayPool pool = stack.pop(ArrayPool.class);
    Object value = pool;
    if (value == null) {
      value = stack.pop(Map.class);
    }
    assert value != null;

    // Convert ArrayPool to LazyEventMap (ultra-lazy: arrays allocated on first access)
    if (value instanceof ArrayPool) {
      ArrayPool ap = (ArrayPool) value;
      Map<String, Object> lazyMap = new LazyEventMap(ap, ap.size);
      value = lazyMap;
    }

    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      if (owner != null && fld != null) {
        Object parent = stack.peek();
        if (parent instanceof ArrayPool) {
          ((ArrayPool) parent).add(fld, value);
        } else if (parent instanceof Map) {
          ((Map<String, Object>) parent).put(fld, value);
        }
      } else {
        // top-level complex value
        root = (Map<String, Object>) value;
      }
    }
  }

  @Override
  public void onArrayStart(MetadataClass owner, String fld, MetadataClass type, int len) {
    ArrayHolder arr = new ArrayHolder(type.getName(), len);
    stack.push(arr);
  }

  @Override
  public void onArrayEnd(MetadataClass owner, String fld, MetadataClass type) {
    ArrayHolder arr = stack.pop(ArrayHolder.class);
    assert arr != null;
    Object parent = stack.peek();
    if (parent instanceof ArrayPool) {
      ((ArrayPool) parent).add(fld, arr);
    } else if (parent instanceof Map) {
      ((Map<String, Object>) parent).put(fld, arr);
    }
  }

  @Override
  public void onConstantPoolIndex(
      MetadataClass owner, String fld, MetadataClass type, long pointer) {
    ConstantPoolAccessor cpAccessor = new ConstantPoolAccessor(context, type, pointer);
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(cpAccessor);
    } else {
      Object parent = stack.peek();
      if (parent instanceof ArrayPool) {
        ((ArrayPool) parent).add(fld, cpAccessor);
      } else if (parent instanceof Map) {
        ((Map<String, Object>) parent).put(fld, cpAccessor);
      }
    }
  }

  private void addFieldValue(String fld, Object value) {
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      Object parent = stack.peek();
      if (parent instanceof ArrayPool) {
        ((ArrayPool) parent).add(fld, value);
      } else if (parent instanceof Map) {
        ((Map<String, Object>) parent).put(fld, value);
      }
    }
  }
}

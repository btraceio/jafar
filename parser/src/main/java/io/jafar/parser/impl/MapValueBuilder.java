package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.ValueProcessor;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Value processor that builds a Java Map representation for complex values while keeping
 * constant-pool references lazy via {@link ConstantPoolAccessor}.
 *
 * <p>Uses flyweight pattern to share field name arrays across events of the same type, reducing
 * memory allocations from ~300 bytes to ~50 bytes per event.
 */
final class MapValueBuilder implements ValueProcessor {
  /**
   * Cache of sorted field name arrays. Keyed by both type ID and field names to prevent collisions
   * when different JFR recordings reuse the same type IDs. Shared across all MapValueBuilder
   * instances to enable field name array reuse.
   *
   * <p>Uses {@link MapValueBuilderCacheKey} which has optimized record-based implementation for
   * Java 21+.
   */
  private static final ConcurrentHashMap<MapValueBuilderCacheKey, String[]> FIELD_NAME_CACHE =
      new ConcurrentHashMap<>();

  private final ParserContext context;
  private final MultiTypeStack stack = new MultiTypeStack(20);
  private Map<String, Object> root;
  private MetadataClass currentEventType;

  MapValueBuilder(ParserContext context) {
    this.context = context;
  }

  void reset() {
    root = null;
    currentEventType = null;
  }

  Map<String, Object> getRoot() {
    return root;
  }

  /**
   * Sets the current event type for flyweight map creation.
   *
   * @param type the metadata class of the event being processed
   */
  void setEventType(MetadataClass type) {
    this.currentEventType = type;
  }

  @Override
  public void onStringValue(MetadataClass owner, String fld, String value) {
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      Map<String, Object> parent = stack.peek(Map.class);
      assert parent != null;
      parent.put(fld, value);
    }
  }

  @Override
  public void onShortValue(MetadataClass type, String fld, short value) {
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      Map<String, Object> parent = stack.peek(Map.class);
      assert parent != null;
      parent.put(fld, value);
    }
  }

  @Override
  public void onCharValue(MetadataClass type, String fld, char value) {
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      Map<String, Object> parent = stack.peek(Map.class);
      assert parent != null;
      parent.put(fld, value);
    }
  }

  @Override
  public void onIntValue(MetadataClass owner, String fld, long value) {
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      Map<String, Object> parent = stack.peek(Map.class);
      assert parent != null;
      parent.put(fld, value);
    }
  }

  @Override
  public void onLongValue(MetadataClass type, String fld, long value) {
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      Map<String, Object> parent = stack.peek(Map.class);
      assert parent != null;
      parent.put(fld, value);
    }
  }

  @Override
  public void onByteValue(MetadataClass type, String fld, byte value) {
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      Map<String, Object> parent = stack.peek(Map.class);
      assert parent != null;
      parent.put(fld, value);
    }
  }

  @Override
  public void onBooleanValue(MetadataClass owner, String fld, boolean value) {
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      Map<String, Object> parent = stack.peek(Map.class);
      assert parent != null;
      parent.put(fld, value);
    }
  }

  @Override
  public void onDoubleValue(MetadataClass owner, String fld, double value) {
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      Map<String, Object> parent = stack.peek(Map.class);
      assert parent != null;
      parent.put(fld, value);
    }
  }

  @Override
  public void onFloatValue(MetadataClass owner, String fld, float value) {
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      Map<String, Object> parent = stack.peek(Map.class);
      assert parent != null;
      parent.put(fld, value);
    }
  }

  @Override
  public void onComplexValueStart(MetadataClass owner, String fld, MetadataClass type) {
    Map<String, Object> value = new HashMap<>();
    stack.push(value);
  }

  @Override
  public void onComplexValueEnd(MetadataClass owner, String fld, MetadataClass type) {
    Map<String, Object> value = stack.pop(Map.class);
    assert value != null;
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(value);
    } else {
      if (owner != null && fld != null) {
        Map<String, Object> parent = stack.peek(Map.class);
        if (parent != null) {
          parent.put(fld, value);
        }
      } else {
        // top-level complex value (event) - convert to flyweight map
        if (currentEventType != null && !value.isEmpty()) {
          long typeId = currentEventType.getId();
          MapValueBuilderCacheKey cacheKey = new MapValueBuilderCacheKey(typeId, value.keySet());
          String[] keys =
              FIELD_NAME_CACHE.computeIfAbsent(
                  cacheKey, k -> value.keySet().stream().sorted().toArray(String[]::new));

          Object[] values = new Object[keys.length];
          for (int i = 0; i < keys.length; i++) {
            values[i] = value.get(keys[i]);
          }

          root = new FlyweightEventMap(keys, values);
        } else {
          root = value;
        }
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
    Map<String, Object> parent = stack.peek(Map.class);
    assert parent != null;
    parent.put(fld, arr);
  }

  @Override
  public void onConstantPoolIndex(
      MetadataClass owner, String fld, MetadataClass type, long pointer) {
    ConstantPoolAccessor cpAccessor = new ConstantPoolAccessor(context, type, pointer);
    ArrayHolder ah = stack.peek(ArrayHolder.class);
    if (ah != null) {
      ah.add(cpAccessor);
    } else {
      Map<String, Object> parent = stack.peek(Map.class);
      assert parent != null;
      parent.put(fld, cpAccessor);
    }
  }
}

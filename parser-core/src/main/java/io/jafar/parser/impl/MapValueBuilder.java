package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.ValueProcessor;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.HashMap;
import java.util.Map;

/**
 * Value processor that builds a Java Map representation for complex values while keeping
 * constant-pool references lazy via {@link ConstantPoolAccessor}.
 */
public final class MapValueBuilder implements ValueProcessor {
  private final ParserContext context;
  private final MultiTypeStack stack = new MultiTypeStack(20);
  private Map<String, Object> root;

  public MapValueBuilder(ParserContext context) {
    this.context = context;
  }

  void reset() {
    root = null;
  }

  public Map<String, Object> getRoot() {
    return root;
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
        // top-level complex value
        root = value;
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

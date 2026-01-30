package io.jafar.hdump.impl;

import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapField;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.internal.BasicType;
import io.jafar.hdump.internal.HprofReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Implementation of HeapObject with lazy field reading. */
final class HeapObjectImpl implements HeapObject {

  private final long id;
  private final HeapClassImpl heapClass;
  private final int dataPosition; // Position in file where instance data starts
  private final int dataSize;
  private final HeapDumpImpl dump;

  private int shallowSize;
  private long retainedSize = -1;
  private int arrayLength = -1;
  private int primitiveArrayType = -1; // -1 = not primitive array
  private boolean isObjectArray = false;

  // Cached field values (lazily populated)
  private Map<String, Object> fieldValues;

  HeapObjectImpl(
      long id, HeapClassImpl heapClass, int dataPosition, int dataSize, HeapDumpImpl dump) {
    this.id = id;
    this.heapClass = heapClass;
    this.dataPosition = dataPosition;
    this.dataSize = dataSize;
    this.dump = dump;
  }

  void setShallowSize(int size) {
    this.shallowSize = size;
  }

  void setRetainedSize(long size) {
    this.retainedSize = size;
  }

  void setArrayLength(int length) {
    this.arrayLength = length;
  }

  void setPrimitiveArrayType(int type) {
    this.primitiveArrayType = type;
  }

  void setObjectArray(boolean isObjectArray) {
    this.isObjectArray = isObjectArray;
  }

  @Override
  public long getId() {
    return id;
  }

  @Override
  public HeapClass getHeapClass() {
    return heapClass;
  }

  @Override
  public int getShallowSize() {
    return shallowSize;
  }

  @Override
  public long getRetainedSize() {
    return retainedSize;
  }

  @Override
  public Object getFieldValue(String fieldName) {
    ensureFieldsLoaded();
    return fieldValues.get(fieldName);
  }

  @Override
  public Map<String, Object> getFieldValues() {
    ensureFieldsLoaded();
    return Collections.unmodifiableMap(fieldValues);
  }

  private void ensureFieldsLoaded() {
    if (fieldValues != null) {
      return;
    }
    fieldValues = new LinkedHashMap<>();

    if (heapClass == null || isArray()) {
      return; // Arrays don't have fields
    }

    // Get all fields including inherited
    List<HeapField> allFields = heapClass.getAllInstanceFields();
    if (allFields.isEmpty()) {
      return;
    }

    HprofReader reader = dump.getReader();
    int idSize = reader.getIdSize();
    int savedPos = reader.position();
    try {
      reader.position(dataPosition);

      for (HeapField field : allFields) {
        int type = field.getType();
        Object value = reader.readValue(type);

        // Resolve object references to HeapObject instances
        if (type == BasicType.OBJECT && value instanceof Long refId) {
          if (refId != 0) {
            HeapObject refObj = dump.getObjectByIdInternal(refId);
            value = refObj; // May be null if object not found
          } else {
            value = null;
          }
        }

        fieldValues.put(field.getName(), value);
      }
    } finally {
      reader.position(savedPos);
    }
  }

  @Override
  public Stream<HeapObject> getOutboundReferences() {
    if (isArray()) {
      if (isObjectArray) {
        Object[] elements = getArrayElements();
        if (elements == null) return Stream.empty();
        return Stream.of(elements).filter(e -> e instanceof HeapObject).map(e -> (HeapObject) e);
      }
      return Stream.empty(); // Primitive arrays have no references
    }

    ensureFieldsLoaded();
    return fieldValues.values().stream()
        .filter(v -> v instanceof HeapObject)
        .map(v -> (HeapObject) v);
  }

  @Override
  public Stream<HeapObject> getInboundReferences() {
    // TODO: Implement when trackInboundRefs is enabled
    return Stream.empty();
  }

  @Override
  public boolean isArray() {
    return arrayLength >= 0;
  }

  @Override
  public int getArrayLength() {
    return arrayLength;
  }

  @Override
  public Object[] getArrayElements() {
    if (!isArray()) {
      return null;
    }

    HprofReader reader = dump.getReader();
    int idSize = reader.getIdSize();
    int savedPos = reader.position();
    try {
      reader.position(dataPosition);
      Object[] elements = new Object[arrayLength];

      if (primitiveArrayType >= 0) {
        // Primitive array
        for (int i = 0; i < arrayLength; i++) {
          elements[i] = reader.readValue(primitiveArrayType);
        }
      } else if (isObjectArray) {
        // Object array
        for (int i = 0; i < arrayLength; i++) {
          long refId = reader.readId();
          if (refId != 0) {
            elements[i] = dump.getObjectByIdInternal(refId);
          } else {
            elements[i] = null;
          }
        }
      }

      return elements;
    } finally {
      reader.position(savedPos);
    }
  }

  @Override
  public String getStringValue() {
    if (heapClass == null || !"java.lang.String".equals(heapClass.getName())) {
      return null;
    }

    ensureFieldsLoaded();

    // In JDK 9+, String uses byte[] value with coder field
    // In JDK 8 and earlier, String uses char[] value
    Object valueField = fieldValues.get("value");
    if (valueField instanceof HeapObject valueArray) {
      return extractStringFromArray(valueArray);
    }

    return null;
  }

  private String extractStringFromArray(HeapObject valueArray) {
    if (!valueArray.isArray()) {
      return null;
    }

    Object[] elements = valueArray.getArrayElements();
    if (elements == null || elements.length == 0) {
      return "";
    }

    // Check if byte array (JDK 9+) or char array (JDK 8)
    HeapClass arrayClass = valueArray.getHeapClass();
    if (arrayClass != null) {
      String arrayTypeName = arrayClass.getName();
      if ("[B".equals(arrayTypeName)) {
        // byte[] - JDK 9+ String
        return extractStringFromByteArray(elements);
      } else if ("[C".equals(arrayTypeName)) {
        // char[] - JDK 8 String
        return extractStringFromCharArray(elements);
      }
    }

    return null;
  }

  private String extractStringFromByteArray(Object[] elements) {
    // Check coder field for encoding: 0 = Latin1, 1 = UTF16
    Object coderValue = fieldValues.get("coder");
    int coder = (coderValue instanceof Byte b) ? b : 0;

    byte[] bytes = new byte[elements.length];
    for (int i = 0; i < elements.length; i++) {
      if (elements[i] instanceof Byte b) {
        bytes[i] = b;
      }
    }

    if (coder == 0) {
      // Latin1
      return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    } else {
      // UTF16
      return new String(bytes, java.nio.charset.StandardCharsets.UTF_16);
    }
  }

  private String extractStringFromCharArray(Object[] elements) {
    char[] chars = new char[elements.length];
    for (int i = 0; i < elements.length; i++) {
      if (elements[i] instanceof Character c) {
        chars[i] = c;
      }
    }
    return new String(chars);
  }

  @Override
  public String toString() {
    if (heapClass == null) {
      return "HeapObject[unknown@" + Long.toHexString(id) + "]";
    }
    return "HeapObject[" + heapClass.getName() + "@" + Long.toHexString(id) + "]";
  }
}

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
  private final long dataPosition; // Position in file where instance data starts
  private final int dataSize;
  private final HeapDumpImpl dump;

  private int shallowSize;
  private long retainedSize = -1;
  private HeapObjectImpl dominator; // Immediate dominator in dominator tree
  private boolean hasExactRetainedSize = false; // True if exact dominator computed
  private int arrayLength = -1;
  private int primitiveArrayType = -1; // -1 = not primitive array
  private boolean isObjectArray = false;

  // Cached field values (lazily populated)
  private Map<String, Object> fieldValues;

  // Cached outbound reference IDs (null = not cached, empty array = no refs)
  private long[] cachedOutboundRefIds;

  // Constant for objects with no references
  private static final long[] EMPTY_LONG_ARRAY = new long[0];

  HeapObjectImpl(
      long id, HeapClassImpl heapClass, long dataPosition, int dataSize, HeapDumpImpl dump) {
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

  void setHasExactRetainedSize(boolean hasExact) {
    this.hasExactRetainedSize = hasExact;
  }

  boolean hasExactRetainedSize() {
    return hasExactRetainedSize;
  }

  void setDominator(HeapObjectImpl dominator) {
    this.dominator = dominator;
  }

  HeapObjectImpl getDominator() {
    return dominator;
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
    if (retainedSize < 0) {
      // Auto-trigger approximate retained size computation on first access
      dump.ensureDominatorsComputed();
    }
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
    long savedPos = reader.position();
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

  /**
   * Extracts outbound reference IDs from instance fields.
   * Only reads object reference fields, skipping primitives for efficiency.
   */
  private long[] extractInstanceReferences() {
    if (heapClass == null) {
      return EMPTY_LONG_ARRAY;
    }

    // Get all fields including inherited
    List<HeapField> allFields = heapClass.getAllInstanceFields();
    if (allFields.isEmpty()) {
      return EMPTY_LONG_ARRAY;
    }

    // Count object reference fields first
    int refFieldCount = 0;
    for (HeapField field : allFields) {
      if (field.getType() == BasicType.OBJECT) {
        refFieldCount++;
      }
    }

    if (refFieldCount == 0) {
      return EMPTY_LONG_ARRAY;
    }

    // Extract reference IDs
    HprofReader reader = dump.getReader();
    long savedPos = reader.position();
    try {
      reader.position(dataPosition);

      long[] refIds = new long[refFieldCount];
      int refIndex = 0;

      for (HeapField field : allFields) {
        int type = field.getType();
        if (type == BasicType.OBJECT) {
          long refId = reader.readId();
          if (refId != 0) {
            refIds[refIndex++] = refId;
          }
        } else {
          // Skip primitive field
          reader.skip(BasicType.sizeOf(type, reader.getIdSize()));
        }
      }

      // Return array trimmed to actual non-null refs
      if (refIndex < refFieldCount) {
        long[] trimmed = new long[refIndex];
        System.arraycopy(refIds, 0, trimmed, 0, refIndex);
        return trimmed;
      }
      return refIds;
    } finally {
      reader.position(savedPos);
    }
  }

  /**
   * Extracts outbound reference IDs from object array elements.
   * Reads the contiguous block of object IDs from the array.
   */
  private long[] extractArrayReferences() {
    if (!isObjectArray || arrayLength == 0) {
      return EMPTY_LONG_ARRAY;
    }

    HprofReader reader = dump.getReader();
    long savedPos = reader.position();
    try {
      reader.position(dataPosition);

      // Count non-null references first
      long[] tempIds = new long[arrayLength];
      int refCount = 0;

      for (int i = 0; i < arrayLength; i++) {
        long refId = reader.readId();
        if (refId != 0) {
          tempIds[refCount++] = refId;
        }
      }

      // Return trimmed array
      if (refCount == 0) {
        return EMPTY_LONG_ARRAY;
      }
      if (refCount < arrayLength) {
        long[] trimmed = new long[refCount];
        System.arraycopy(tempIds, 0, trimmed, 0, refCount);
        return trimmed;
      }
      return tempIds;
    } finally {
      reader.position(savedPos);
    }
  }

  /**
   * Extracts and caches outbound reference IDs.
   * Called on first access to getOutboundReferences() or eagerly during parse.
   */
  void extractOutboundReferences() {
    if (cachedOutboundRefIds != null) {
      return; // Already cached
    }

    if (isArray()) {
      if (isObjectArray) {
        cachedOutboundRefIds = extractArrayReferences();
      } else {
        cachedOutboundRefIds = EMPTY_LONG_ARRAY; // Primitive array
      }
    } else {
      cachedOutboundRefIds = extractInstanceReferences();
    }
  }

  /**
   * Returns cached outbound reference IDs as primitive array.
   * For use in hot paths (dominator computation, graph traversal).
   * Avoids Stream API overhead - use this instead of getOutboundReferences() in tight loops.
   *
   * @return array of object IDs referenced by this object (never null, may be empty)
   */
  public long[] getOutboundReferenceIds() {
    if (cachedOutboundRefIds == null) {
      extractOutboundReferences();
    }
    return cachedOutboundRefIds;
  }

  @Override
  public Stream<HeapObject> getOutboundReferences() {
    // Use getOutboundReferenceIds() to avoid duplication
    long[] refIds = getOutboundReferenceIds();

    if (refIds.length == 0) {
      return Stream.empty();
    }

    return java.util.Arrays.stream(refIds)
        .mapToObj(id -> (HeapObject) dump.getObjectByIdInternal(id))
        .filter(obj -> obj != null);
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
    long savedPos = reader.position();
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
      // UTF16 - JVM uses big-endian internally
      return new String(bytes, java.nio.charset.StandardCharsets.UTF_16BE);
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

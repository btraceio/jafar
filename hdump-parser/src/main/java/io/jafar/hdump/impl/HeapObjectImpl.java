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
  private final HeapDumpImpl dump;

  private int shallowSize;
  private long retainedSize = -1;
  private HeapObjectImpl dominator; // Immediate dominator in dominator tree
  private boolean hasExactRetainedSize = false; // True if exact dominator computed
  private int arrayLength = -1;
  private int primitiveArrayType = -1; // -1 = not primitive array
  private boolean isObjectArray = false;

  // Cached field values (lazily populated, volatile for double-checked locking)
  private volatile Map<String, Object> fieldValues;

  // Cached outbound reference IDs (null = not cached, empty array = no refs)
  private long[] cachedOutboundRefIds;

  // Cached strong outbound reference IDs (excludes Reference.referent); null = not computed
  private long[] cachedStrongOutboundRefIds;

  // Constant for objects with no references
  private static final long[] EMPTY_LONG_ARRAY = new long[0];

  HeapObjectImpl(
      long id, HeapClassImpl heapClass, long dataPosition, int dataSize, HeapDumpImpl dump) {
    this.id = id;
    this.heapClass = heapClass;
    this.dataPosition = dataPosition;
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
    if (dominator == null && dump != null) {
      return dump.indexedDominatorOf(id); // INDEXED full tree: held by the dump, not the object
    }
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
    // Auto-compute retained sizes on first access if not available
    if (retainedSize == -1 && dump != null) {
      dump.ensureRetainedSizesComputed();
      // After computation, read from index in indexed mode
      retainedSize = dump.getRetainedSizeFromIndex(id);
    }
    return retainedSize;
  }

  @Override
  public long getRetainedSizeIfAvailable() {
    return retainedSize; // Returns -1 if not computed; never triggers computation
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
    synchronized (this) {
      if (fieldValues != null) {
        return;
      }
      if (heapClass == null || isArray()) {
        fieldValues = new LinkedHashMap<>();
        return;
      }

      List<HeapField> allFields = heapClass.getAllInstanceFields();
      if (allFields.isEmpty()) {
        fieldValues = new LinkedHashMap<>();
        return;
      }

      // Thread-local reader has its own position state — no lock needed
      HprofReader reader = dump.getThreadLocalReader();
      Map<String, Object> values = new LinkedHashMap<>();
      long savedPos = reader.position();
      try {
        reader.position(dataPosition);
        for (HeapField field : allFields) {
          int type = field.getType();
          Object value = reader.readValue(type);
          if (type == BasicType.OBJECT && value instanceof Long refId) {
            value = refId != 0 ? dump.getObjectByIdInternal(refId) : null;
          }
          values.put(field.getName(), value);
        }
      } finally {
        reader.position(savedPos);
      }
      fieldValues = values;
    }
  }

  /**
   * Reads the non-null object ids at {@code offsets} (relative to the instance data) with absolute
   * reads, in offset order. The reader's position is not used or changed.
   */
  private long[] readIds(HprofReader reader, int[] offsets) {
    int count = offsets.length;
    if (count == 0) {
      return EMPTY_LONG_ARRAY;
    }
    long[] ids = new long[count];
    int k = 0;
    for (int off : offsets) {
      long id = reader.readIdAt(dataPosition + off);
      if (id != 0) {
        ids[k++] = id;
      }
    }
    if (k == count) {
      return ids;
    }
    return k == 0 ? EMPTY_LONG_ARRAY : java.util.Arrays.copyOf(ids, k);
  }

  /** Object ids referenced by the elements of an object array, nulls skipped, in index order. */
  private long[] readArrayIds(HprofReader reader) {
    if (!isObjectArray || arrayLength <= 0) {
      return EMPTY_LONG_ARRAY;
    }
    int idSize = reader.getIdSize();
    long[] ids = new long[arrayLength];
    int k = 0;
    long pos = dataPosition;
    for (int i = 0; i < arrayLength; i++, pos += idSize) {
      long id = reader.readIdAt(pos);
      if (id != 0) {
        ids[k++] = id;
      }
    }
    if (k == arrayLength) {
      return ids;
    }
    return k == 0 ? EMPTY_LONG_ARRAY : java.util.Arrays.copyOf(ids, k);
  }

  /**
   * Extracts and caches outbound reference IDs. Called on first access to getOutboundReferences()
   * or eagerly during parse.
   */
  void extractOutboundReferences() {
    if (cachedOutboundRefIds == null) {
      extractOutboundReferences(dump.getThreadLocalReader());
    }
  }

  /**
   * As {@link #extractOutboundReferences()}, reading through {@code reader} (absolute reads only),
   * so the parser can pass its own reader without a thread-local lookup per object.
   */
  void extractOutboundReferences(HprofReader reader) {
    if (cachedOutboundRefIds != null) {
      return; // Already cached
    }
    if (isArray()) {
      cachedOutboundRefIds = isObjectArray ? readArrayIds(reader) : EMPTY_LONG_ARRAY;
    } else if (heapClass == null) {
      cachedOutboundRefIds = EMPTY_LONG_ARRAY;
    } else {
      cachedOutboundRefIds = readIds(reader, heapClass.refOffsets(reader.getIdSize()));
    }
  }

  /**
   * Returns cached outbound reference IDs as primitive array. For use in hot paths (dominator
   * computation, graph traversal). Avoids Stream API overhead - use this instead of
   * getOutboundReferences() in tight loops.
   *
   * @return array of object IDs referenced by this object (never null, may be empty)
   */
  public long[] getOutboundReferenceIds() {
    if (cachedOutboundRefIds == null) {
      extractOutboundReferences();
    }
    return cachedOutboundRefIds;
  }

  /**
   * Returns outbound reference IDs excluding {@code java.lang.ref.Reference.referent}. For
   * non-Reference objects this returns the same array as {@link #getOutboundReferenceIds()}.
   */
  public long[] getStrongOutboundReferenceIds() {
    if (cachedStrongOutboundRefIds != null) return cachedStrongOutboundRefIds;
    if (heapClass == null || !heapClass.isReferenceSubclass()) {
      cachedStrongOutboundRefIds = getOutboundReferenceIds();
      return cachedStrongOutboundRefIds;
    }
    HprofReader reader = dump.getThreadLocalReader();
    cachedStrongOutboundRefIds = readIds(reader, heapClass.strongRefOffsets(reader.getIdSize()));
    return cachedStrongOutboundRefIds;
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
  public Stream<HeapObject> getStrongOutboundReferences() {
    long[] refIds = getStrongOutboundReferenceIds();
    if (refIds.length == 0) return Stream.empty();
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

    // Thread-local reader has its own position state — no lock needed
    HprofReader reader = dump.getThreadLocalReader();
    long savedPos = reader.position();
    try {
      reader.position(dataPosition);
      Object[] elements = new Object[arrayLength];

      if (primitiveArrayType >= 0) {
        for (int i = 0; i < arrayLength; i++) {
          elements[i] = reader.readValue(primitiveArrayType);
        }
      } else if (isObjectArray) {
        for (int i = 0; i < arrayLength; i++) {
          long refId = reader.readId();
          elements[i] = refId != 0 ? dump.getObjectByIdInternal(refId) : null;
        }
      }

      return elements;
    } finally {
      reader.position(savedPos);
    }
  }

  /**
   * Index of the first element of this object array that references {@code id}, or -1 (also for
   * instances and primitive arrays). Reads the element ids without resolving them to objects.
   */
  int indexOfArrayElement(long id) {
    if (!isObjectArray || arrayLength <= 0 || id == 0) {
      return -1;
    }
    HprofReader reader = dump.getThreadLocalReader();
    long savedPos = reader.position();
    try {
      reader.position(dataPosition);
      for (int i = 0; i < arrayLength; i++) {
        if (reader.readId() == id) {
          return i;
        }
      }
      return -1;
    } finally {
      reader.position(savedPos);
    }
  }

  @Override
  public String getStringValue() {
    if (heapClass == null || !"java/lang/String".equals(heapClass.getName())) {
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

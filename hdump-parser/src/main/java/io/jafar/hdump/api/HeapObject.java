package io.jafar.hdump.api;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Represents an object instance in the heap dump. Provides access to the object's class, field
 * values, and memory metrics.
 */
public interface HeapObject {

  /** Returns the object ID (unique within this heap dump). */
  long getId();

  /** Returns the class of this object. */
  HeapClass getHeapClass();

  /**
   * Returns the shallow size of this object in bytes. This is the memory directly consumed by this
   * object, not including referenced objects.
   */
  int getShallowSize();

  /**
   * Returns the retained size of this object in bytes. This is the total memory that would be freed
   * if this object became unreachable. Computing retained size requires dominator analysis and may
   * be expensive.
   *
   * <p>This method triggers retained size computation on first access if not yet available.
   *
   * @return retained size, or -1 if not yet computed
   */
  long getRetainedSize();

  /**
   * Returns the retained size if already computed, or -1 without triggering computation.
   *
   * <p>Use this instead of {@link #getRetainedSize()} when building intermediate representations
   * (e.g. result maps) where triggering a heap-wide computation would be an unintended side-effect.
   *
   * @return retained size if available, or -1 if not yet computed
   */
  default long getRetainedSizeIfAvailable() {
    return -1L;
  }

  /**
   * Returns the value of a field by name. For object references, returns the HeapObject. For
   * primitives, returns the boxed value.
   *
   * @param fieldName the field name
   * @return the field value, or null if not found or if the field value is null
   */
  Object getFieldValue(String fieldName);

  /**
   * Returns all field values as a map from field name to value.
   *
   * @return map of field values
   */
  Map<String, Object> getFieldValues();

  /**
   * Returns objects that this object directly references.
   *
   * @return stream of referenced objects
   */
  Stream<HeapObject> getOutboundReferences();

  /**
   * Returns objects that this object strongly references, excluding {@code
   * java.lang.ref.Reference.referent} fields. For non-Reference objects this is identical to {@link
   * #getOutboundReferences()}.
   *
   * @return stream of strongly referenced objects
   */
  default Stream<HeapObject> getStrongOutboundReferences() {
    return getOutboundReferences();
  }

  /**
   * Returns objects that directly reference this object.
   *
   * <p><strong>Note:</strong> This method requires the heap dump to be parsed with {@link
   * HeapDumpParser.ParserOptions#trackInboundRefs()} enabled. If inbound reference tracking was not
   * enabled, this method returns an empty stream.
   *
   * <p><strong>Current status:</strong> Inbound reference tracking is not yet implemented. This
   * method always returns an empty stream regardless of parser options. Use {@link
   * HeapDump#getGcRoots()} and outbound reference traversal to build inbound edges manually.
   *
   * @return stream of referencing objects, or empty stream if tracking not enabled
   */
  Stream<HeapObject> getInboundReferences();

  /** Returns true if this is an array object. */
  boolean isArray();

  /**
   * For array objects, returns the array length. For non-arrays, returns -1.
   *
   * @return array length or -1
   */
  int getArrayLength();

  /**
   * For array objects, returns the array elements. For primitive arrays, returns boxed primitives.
   * For object arrays, returns HeapObjects.
   *
   * @return array elements, or null for non-arrays
   */
  Object[] getArrayElements();

  /**
   * For String objects, returns the string value.
   *
   * @return string value, or null if not a String
   */
  String getStringValue();

  /** Returns a short description of this object for display. */
  default String getDescription() {
    HeapClass cls = getHeapClass();
    if (cls == null) return "unknown@" + Long.toHexString(getId());

    String name = cls.getName();
    if ("java.lang.String".equals(name)) {
      String val = getStringValue();
      if (val != null) {
        if (val.length() > 50) val = val.substring(0, 47) + "...";
        return "\"" + val + "\"";
      }
    }
    return cls.getSimpleName() + "@" + Long.toHexString(getId());
  }
}

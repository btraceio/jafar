package io.jafar.hdump.api;

import java.util.List;

/**
 * Represents a Java class in the heap dump. Provides access to class metadata including fields,
 * superclass, and instance information.
 */
public interface HeapClass {

  /** Returns the class ID (unique within this heap dump). */
  long getId();

  /** Returns the fully qualified class name (e.g., "java.lang.String"). */
  String getName();

  /** Returns the simple class name (e.g., "String"). */
  default String getSimpleName() {
    String name = getName();
    int lastDot = name.lastIndexOf('.');
    return lastDot >= 0 ? name.substring(lastDot + 1) : name;
  }

  /** Returns the superclass, or null for java.lang.Object. */
  HeapClass getSuperClass();

  /** Returns the class loader ID, or 0 if loaded by bootstrap loader. */
  long getClassLoaderId();

  /** Returns the list of instance fields declared by this class (not inherited). */
  List<HeapField> getInstanceFields();

  /**
   * Returns all instance fields including inherited fields, ordered from superclass to subclass.
   */
  List<HeapField> getAllInstanceFields();

  /** Returns the list of static fields declared by this class. */
  List<HeapField> getStaticFields();

  /** Returns the size of an instance of this class in bytes (shallow size). */
  int getInstanceSize();

  /** Returns the number of instances of this class in the heap. */
  int getInstanceCount();

  /** Returns true if this is an array class. */
  boolean isArray();

  /** Returns true if this is a primitive array class (e.g., int[]). */
  boolean isPrimitiveArray();

  /** Returns true if this is an object array class (e.g., Object[]). */
  boolean isObjectArray();

  /** For array classes, returns the component type. Returns null for non-array classes. */
  HeapClass getComponentType();
}

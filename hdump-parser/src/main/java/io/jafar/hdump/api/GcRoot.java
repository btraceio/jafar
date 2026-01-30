package io.jafar.hdump.api;

/**
 * Represents a GC root in the heap dump. GC roots are entry points into the object graph - objects
 * reachable from GC roots are considered live.
 */
public interface GcRoot {

  /** GC root types. */
  enum Type {
    /** Unknown root type. */
    UNKNOWN,
    /** JNI global reference. */
    JNI_GLOBAL,
    /** JNI local reference. */
    JNI_LOCAL,
    /** Reference from a Java stack frame. */
    JAVA_FRAME,
    /** Reference from native stack. */
    NATIVE_STACK,
    /** System class (loaded by bootstrap class loader). */
    STICKY_CLASS,
    /** Thread block. */
    THREAD_BLOCK,
    /** Monitor (synchronization) reference. */
    MONITOR_USED,
    /** Thread object. */
    THREAD_OBJ
  }

  /** Returns the type of this GC root. */
  Type getType();

  /** Returns the object ID this root points to. */
  long getObjectId();

  /** Returns the object this root points to, or null if not found. */
  HeapObject getObject();

  /** For JAVA_FRAME roots, returns the thread serial number. Returns -1 otherwise. */
  int getThreadSerial();

  /** For JAVA_FRAME roots, returns the frame number in the stack. Returns -1 otherwise. */
  int getFrameNumber();

  /** For THREAD_OBJ roots, returns the thread serial number. Returns -1 otherwise. */
  int getThreadObjSerial();
}

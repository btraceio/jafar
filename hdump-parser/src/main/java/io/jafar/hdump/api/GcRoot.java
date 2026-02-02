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

  /**
   * Returns the thread serial number for this root.
   *
   * <p>This value is available for JAVA_FRAME, JNI_LOCAL, NATIVE_STACK, and THREAD_BLOCK root
   * types. For other types, returns -1.
   *
   * @return thread serial number, or -1 if not applicable
   */
  int getThreadSerial();

  /**
   * Returns the frame number in the stack for this root.
   *
   * <p>This value is available for JAVA_FRAME and JNI_LOCAL root types. For other types, returns
   * -1.
   *
   * @return frame number, or -1 if not applicable
   */
  int getFrameNumber();

  /** For THREAD_OBJ roots, returns the thread serial number. Returns -1 otherwise. */
  int getThreadObjSerial();
}

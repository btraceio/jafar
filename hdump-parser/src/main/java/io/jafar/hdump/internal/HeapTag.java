package io.jafar.hdump.internal;

/**
 * HPROF heap dump sub-record tags. These appear within HEAP_DUMP and HEAP_DUMP_SEGMENT records.
 *
 * @see <a href="https://hg.openjdk.org/jdk/jdk/file/tip/src/hotspot/share/services/heapDumper.cpp">
 *     HotSpot heapDumper.cpp</a>
 */
public final class HeapTag {
  private HeapTag() {}

  // GC root types
  public static final int ROOT_UNKNOWN = 0xFF;
  public static final int ROOT_JNI_GLOBAL = 0x01;
  public static final int ROOT_JNI_LOCAL = 0x02;
  public static final int ROOT_JAVA_FRAME = 0x03;
  public static final int ROOT_NATIVE_STACK = 0x04;
  public static final int ROOT_STICKY_CLASS = 0x05;
  public static final int ROOT_THREAD_BLOCK = 0x06;
  public static final int ROOT_MONITOR_USED = 0x07;
  public static final int ROOT_THREAD_OBJ = 0x08;

  // Heap dump records
  public static final int CLASS_DUMP = 0x20;
  public static final int INSTANCE_DUMP = 0x21;
  public static final int OBJ_ARRAY_DUMP = 0x22;
  public static final int PRIM_ARRAY_DUMP = 0x23;

  /** Returns a human-readable name for the given tag. */
  public static String nameOf(int tag) {
    return switch (tag) {
      case ROOT_UNKNOWN -> "ROOT_UNKNOWN";
      case ROOT_JNI_GLOBAL -> "ROOT_JNI_GLOBAL";
      case ROOT_JNI_LOCAL -> "ROOT_JNI_LOCAL";
      case ROOT_JAVA_FRAME -> "ROOT_JAVA_FRAME";
      case ROOT_NATIVE_STACK -> "ROOT_NATIVE_STACK";
      case ROOT_STICKY_CLASS -> "ROOT_STICKY_CLASS";
      case ROOT_THREAD_BLOCK -> "ROOT_THREAD_BLOCK";
      case ROOT_MONITOR_USED -> "ROOT_MONITOR_USED";
      case ROOT_THREAD_OBJ -> "ROOT_THREAD_OBJ";
      case CLASS_DUMP -> "CLASS_DUMP";
      case INSTANCE_DUMP -> "INSTANCE_DUMP";
      case OBJ_ARRAY_DUMP -> "OBJ_ARRAY_DUMP";
      case PRIM_ARRAY_DUMP -> "PRIM_ARRAY_DUMP";
      default -> "UNKNOWN(0x" + Integer.toHexString(tag) + ")";
    };
  }

  /** Returns true if this tag represents a GC root. */
  public static boolean isGcRoot(int tag) {
    return tag == ROOT_UNKNOWN
        || tag == ROOT_JNI_GLOBAL
        || tag == ROOT_JNI_LOCAL
        || tag == ROOT_JAVA_FRAME
        || tag == ROOT_NATIVE_STACK
        || tag == ROOT_STICKY_CLASS
        || tag == ROOT_THREAD_BLOCK
        || tag == ROOT_MONITOR_USED
        || tag == ROOT_THREAD_OBJ;
  }
}

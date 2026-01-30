package io.jafar.hdump.internal;

/**
 * HPROF top-level record tags. These identify the type of each record in the heap dump file.
 *
 * @see <a href="https://hg.openjdk.org/jdk/jdk/file/tip/src/hotspot/share/services/heapDumper.cpp">
 *     HotSpot heapDumper.cpp</a>
 */
public final class HprofTag {
  private HprofTag() {}

  // Top-level record tags
  public static final int UTF8 = 0x01;
  public static final int LOAD_CLASS = 0x02;
  public static final int UNLOAD_CLASS = 0x03;
  public static final int FRAME = 0x04;
  public static final int TRACE = 0x05;
  public static final int ALLOC_SITES = 0x06;
  public static final int HEAP_SUMMARY = 0x07;
  public static final int START_THREAD = 0x0A;
  public static final int END_THREAD = 0x0B;
  public static final int HEAP_DUMP = 0x0C;
  public static final int CPU_SAMPLES = 0x0D;
  public static final int CONTROL_SETTINGS = 0x0E;
  public static final int HEAP_DUMP_SEGMENT = 0x1C;
  public static final int HEAP_DUMP_END = 0x2C;

  /** Returns a human-readable name for the given tag. */
  public static String nameOf(int tag) {
    return switch (tag) {
      case UTF8 -> "UTF8";
      case LOAD_CLASS -> "LOAD_CLASS";
      case UNLOAD_CLASS -> "UNLOAD_CLASS";
      case FRAME -> "FRAME";
      case TRACE -> "TRACE";
      case ALLOC_SITES -> "ALLOC_SITES";
      case HEAP_SUMMARY -> "HEAP_SUMMARY";
      case START_THREAD -> "START_THREAD";
      case END_THREAD -> "END_THREAD";
      case HEAP_DUMP -> "HEAP_DUMP";
      case CPU_SAMPLES -> "CPU_SAMPLES";
      case CONTROL_SETTINGS -> "CONTROL_SETTINGS";
      case HEAP_DUMP_SEGMENT -> "HEAP_DUMP_SEGMENT";
      case HEAP_DUMP_END -> "HEAP_DUMP_END";
      default -> "UNKNOWN(0x" + Integer.toHexString(tag) + ")";
    };
  }
}

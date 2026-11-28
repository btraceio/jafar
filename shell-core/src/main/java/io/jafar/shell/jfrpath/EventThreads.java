package io.jafar.shell.jfrpath;

import io.jafar.parser.api.Values;
import java.util.Map;

/**
 * The thread an event is about. Samples ({@code jdk.ExecutionSample}, {@code
 * jdk.NativeMethodSample}) carry it in {@code sampledThread} and have no {@code eventThread};
 * others, such as {@code jdk.JavaMonitorEnter} and {@code datadog.ExecutionSample}, carry it in
 * {@code eventThread}. Code that read only {@code eventThread} saw no thread on any sample (#222).
 *
 * <p>Id and name are read from the same field, so an event carrying both cannot get one thread's id
 * and the other's name. Everything that attributes an event to a thread goes through here, so the
 * analyses, profiles and {@code decorateByTime} agree on it.
 */
public final class EventThreads {
  private EventThreads() {}

  /** {@code sampledThread} when the event has one, else {@code eventThread}. */
  public static String field(Map<String, Object> event) {
    return Values.get(event, "sampledThread") != null ? "sampledThread" : "eventThread";
  }

  /** The thread's {@code javaThreadId}, or {@code null} when the event has no thread or no id. */
  public static Object id(Map<String, Object> event) {
    return Values.get(event, field(event), "javaThreadId");
  }

  /**
   * The thread's {@code javaName}, else its {@code osName}, or {@code null}. Threads the JVM did
   * not start from Java have only an OS name.
   */
  public static Object name(Map<String, Object> event) {
    String thread = field(event);
    Object name = Values.get(event, thread, "javaName");
    return name != null ? name : Values.get(event, thread, "osName");
  }
}

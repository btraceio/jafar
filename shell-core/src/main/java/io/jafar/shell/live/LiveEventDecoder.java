package io.jafar.shell.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;

/**
 * Converts JDK JFR API events into the untyped map shape the query pipeline consumes ({@code
 * EventSource.Event}: type name + field map). One conversion, two consumers:
 *
 * <ul>
 *   <li>the {@code jdk} file backend ({@code JdkJfrBackend}), which uses {@link
 *       #decodeFields(RecordedEvent)} verbatim — file-backend output must stay byte-identical;
 *   <li>the live {@code tail} path, which uses {@link #decode(RecordedEvent)}: the same field map
 *       but with {@code startTime} replaced by absolute epoch nanos (matching the {@code timeRange}
 *       decorator contract) and {@code duration} by nanos. Reason: {@code
 *       RecordedEvent.getFields()} includes the BUILT-IN {@code startTime}/{@code duration} fields
 *       whose raw values are chunk-relative ticks — meaningless across chunks and across events; no
 *       custom event type can declare its own fields with those names (JFR reserves them), so the
 *       overwrite cannot clobber user data.
 * </ul>
 *
 * <p>Thread identity needs no special handling: {@code RecordedThread.getFields()} already includes
 * {@code javaName}, {@code osThreadId} and {@code javaThreadId}, so the generic {@code
 * RecordedObject} flattening carries it (verified against a purpose-built recording).
 */
public final class LiveEventDecoder {
  private LiveEventDecoder() {}

  /** Full live conversion: field map with startTime/duration rewritten to absolute values. */
  public static Map<String, Object> decode(RecordedEvent event) {
    Map<String, Object> map = decodeFields(event);
    // The built-in startTime/duration field values from the raw flatten are chunk-relative
    // ticks; rewrite them with the absolute values the query pipeline's time handling expects.
    // JFR reserves these field names — no custom event type can declare its own.
    map.put(
        "startTime",
        event.getStartTime().getEpochSecond() * 1_000_000_000L + event.getStartTime().getNano());
    map.put("duration", event.getDuration().toNanos());
    return map;
  }

  /**
   * The plain field conversion shared with the {@code jdk} file backend: every field through {@link
   * #convertValue}. No decorations — the backend's output shape depends on this.
   */
  public static Map<String, Object> decodeFields(RecordedEvent event) {
    Map<String, Object> map = new java.util.LinkedHashMap<>();
    for (ValueDescriptor field : event.getFields()) {
      String name = field.getName();
      Object value = event.getValue(name);
      map.put(name, convertValue(value));
    }
    return map;
  }

  /** Recursive RecordedObject/list flattening; primitives and strings pass through. */
  public static Object convertValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof RecordedObject ro) {
      Map<String, Object> map = new java.util.LinkedHashMap<>();
      for (ValueDescriptor field : ro.getFields()) {
        String name = field.getName();
        map.put(name, convertValue(ro.getValue(name)));
      }
      return map;
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      for (Object item : list) {
        result.add(convertValue(item));
      }
      return result;
    }
    // Primitives and strings pass through
    return value;
  }
}

package io.jafar.shell.backend.impl;

import io.jafar.shell.backend.BackendCapability;
import io.jafar.shell.backend.BackendContext;
import io.jafar.shell.backend.ChunkSource;
import io.jafar.shell.backend.ConstantPoolSource;
import io.jafar.shell.backend.EventSource;
import io.jafar.shell.backend.JfrBackend;
import io.jafar.shell.backend.MetadataSource;
import io.jafar.shell.backend.UnsupportedCapabilityException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import jdk.jfr.EventType;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

/**
 * JDK JFR API-based backend implementation. Uses the standard jdk.jfr.consumer API for parsing JFR
 * files.
 *
 * <p>Capabilities:
 *
 * <ul>
 *   <li>EVENT_STREAMING - Full support via RecordingFile
 *   <li>METADATA_CLASSES - Event types only (no internal types like jdk.types.Symbol)
 *   <li>STREAMING_PARSE - Streaming event iteration
 *   <li>UNTYPED_HANDLERS - Events converted to Map format
 * </ul>
 *
 * <p>Not supported:
 *
 * <ul>
 *   <li>CHUNK_INFO - JDK API doesn't expose chunk-level details
 *   <li>CONSTANT_POOLS - JDK API doesn't provide direct constant pool access
 *   <li>TYPED_HANDLERS - No compile-time type generation
 *   <li>CONTEXT_REUSE - Each parsing is independent
 * </ul>
 */
public final class JdkJfrBackend implements JfrBackend {

  private static final Set<BackendCapability> CAPABILITIES =
      EnumSet.of(
          BackendCapability.EVENT_STREAMING,
          BackendCapability.METADATA_CLASSES,
          BackendCapability.STREAMING_PARSE,
          BackendCapability.UNTYPED_HANDLERS);

  @Override
  public String getId() {
    return "jdk";
  }

  @Override
  public String getName() {
    return "JDK JFR API";
  }

  @Override
  public String getVersion() {
    return Runtime.version().toString();
  }

  @Override
  public int getPriority() {
    return 50; // Lower than Jafar
  }

  @Override
  public Set<BackendCapability> getCapabilities() {
    return CAPABILITIES;
  }

  @Override
  public BackendContext createContext() {
    return new NoOpBackendContext();
  }

  @Override
  public EventSource createEventSource(BackendContext context) {
    return new JdkEventSource();
  }

  @Override
  public MetadataSource createMetadataSource() {
    return new JdkMetadataSource();
  }

  @Override
  public ChunkSource createChunkSource() throws UnsupportedCapabilityException {
    throw new UnsupportedCapabilityException(BackendCapability.CHUNK_INFO, getId());
  }

  @Override
  public ConstantPoolSource createConstantPoolSource() throws UnsupportedCapabilityException {
    throw new UnsupportedCapabilityException(BackendCapability.CONSTANT_POOLS, getId());
  }

  // --- Inner Classes ---

  /** No-op context for JDK backend (no state to share). */
  private static final class NoOpBackendContext implements BackendContext {
    private final long startTime = System.nanoTime();

    @Override
    public long uptime() {
      return System.nanoTime() - startTime;
    }

    @Override
    public void close() {
      // Nothing to close
    }
  }

  /** JDK event source using RecordingFile. */
  private static final class JdkEventSource implements EventSource {
    @Override
    public void streamEvents(Path recording, Consumer<Event> consumer) throws Exception {
      try (RecordingFile rf = new RecordingFile(recording)) {
        while (rf.hasMoreEvents()) {
          RecordedEvent event = rf.readEvent();
          Map<String, Object> value = convertEvent(event);
          consumer.accept(new Event(event.getEventType().getName(), value));
        }
      }
    }

    private static Map<String, Object> convertEvent(RecordedEvent event) {
      Map<String, Object> map = new LinkedHashMap<>();
      for (ValueDescriptor field : event.getFields()) {
        String name = field.getName();
        Object value = event.getValue(name);
        map.put(name, convertValue(value));
      }
      return map;
    }

    private static Object convertValue(Object value) {
      if (value == null) {
        return null;
      }
      if (value instanceof RecordedObject ro) {
        Map<String, Object> map = new LinkedHashMap<>();
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

  /** JDK metadata source using RecordingFile.readEventTypes(). */
  private static final class JdkMetadataSource implements MetadataSource {
    @Override
    public Map<String, Object> loadClass(Path recording, String typeName) throws Exception {
      try (RecordingFile rf = new RecordingFile(recording)) {
        for (EventType eventType : rf.readEventTypes()) {
          if (eventType.getName().equals(typeName)) {
            return convertEventType(eventType);
          }
        }
      }
      return null;
    }

    @Override
    public Map<String, Object> loadField(Path recording, String typeName, String fieldName)
        throws Exception {
      Map<String, Object> meta = loadClass(recording, typeName);
      if (meta == null) {
        return null;
      }
      Object fieldsByName = meta.get("fieldsByName");
      if (fieldsByName instanceof Map<?, ?> map) {
        Object entry = map.get(fieldName);
        if (entry instanceof Map<?, ?>) {
          @SuppressWarnings("unchecked")
          Map<String, Object> ret = (Map<String, Object>) entry;
          return ret;
        }
      }
      return null;
    }

    @Override
    public List<Map<String, Object>> loadAllClasses(Path recording) throws Exception {
      List<Map<String, Object>> result = new ArrayList<>();
      try (RecordingFile rf = new RecordingFile(recording)) {
        for (EventType eventType : rf.readEventTypes()) {
          result.add(convertEventType(eventType));
        }
      }
      return result;
    }

    private static Map<String, Object> convertEventType(EventType eventType) {
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("id", eventType.getId());
      meta.put("name", eventType.getName());
      meta.put("superType", "jdk.jfr.Event"); // Simplified - all event types extend Event
      meta.put("label", eventType.getLabel());
      meta.put("description", eventType.getDescription());

      // Categories
      List<String> categories = eventType.getCategoryNames();
      meta.put("categories", categories);

      // Annotations
      List<String> classAnn = new ArrayList<>();
      List<String> classAnnFull = new ArrayList<>();
      for (var ann : eventType.getAnnotationElements()) {
        String typeName = ann.getTypeName();
        String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
        classAnn.add("@" + simple);
        classAnnFull.add(typeName);
      }
      meta.put("classAnnotations", classAnn);
      meta.put("classAnnotationsFull", classAnnFull);

      // Settings
      Map<String, Map<String, Object>> settingsByName = new HashMap<>();
      List<String> settingsDisplay = new ArrayList<>();
      for (var setting : eventType.getSettingDescriptors()) {
        Map<String, Object> sm = new HashMap<>();
        String sName = setting.getName();
        sm.put("name", sName);
        sm.put("type", setting.getTypeName());
        sm.put("label", setting.getLabel());
        sm.put("description", setting.getDescription());
        sm.put("defaultValue", setting.getDefaultValue());
        settingsByName.put(sName, sm);
        settingsDisplay.add(
            sName + ":" + setting.getTypeName() + " (" + setting.getDefaultValue() + ")");
      }
      meta.put("settingsByName", settingsByName);
      meta.put("settings", settingsDisplay);

      // Fields
      Map<String, Map<String, Object>> fieldsByName = new LinkedHashMap<>();
      List<String> fieldsDisplay = new ArrayList<>();
      for (ValueDescriptor field : eventType.getFields()) {
        Map<String, Object> fm = new HashMap<>();
        String fName = field.getName();
        String fType = field.getTypeName();
        fm.put("name", fName);
        fm.put("type", fType);
        fm.put("dimension", 0); // JDK API doesn't expose array dimension directly
        fm.put("hasConstantPool", false); // Not available via JDK API
        fm.put("label", field.getLabel());
        fm.put("description", field.getDescription());

        // Field annotations
        List<String> ann = new ArrayList<>();
        List<String> annFull = new ArrayList<>();
        for (var a : field.getAnnotationElements()) {
          String typeName = a.getTypeName();
          String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
          ann.add("@" + simple);
          annFull.add(typeName);
        }
        fm.put("annotations", ann);
        fm.put("annotationsFull", annFull);

        fieldsByName.put(fName, fm);
        String disp = fName + ":" + fType + (ann.isEmpty() ? "" : (" " + String.join(" ", ann)));
        fieldsDisplay.add(disp);
      }
      meta.put("fieldsByName", fieldsByName);
      meta.put("fields", fieldsDisplay);
      meta.put("fieldCount", fieldsDisplay.size());

      return meta;
    }
  }
}

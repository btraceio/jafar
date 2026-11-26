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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import jdk.jfr.AnnotationElement;
import jdk.jfr.EventType;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDK JFR API-based backend implementation. Uses the standard jdk.jfr.consumer API for parsing JFR
 * files.
 *
 * <p>Capabilities:
 *
 * <ul>
 *   <li>EVENT_STREAMING - Full support via RecordingFile
 *   <li>METADATA_CLASSES - Event types and recursively reachable field types
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
      // Shared with the live tail path (no decorations here). Array fields became the untyped
      // Object[]-of-maps model in #196; shell event queries do not use this source (see
      // doc/agents/Shells.md, "Event queries do not go through the backend plugin").
      return io.jafar.shell.live.LiveEventDecoder.decodeFields(event);
    }
  }

  /**
   * JDK metadata source.
   *
   * <p><b>Multi-chunk caveat:</b> {@link RecordingFile#readEventTypes()} collapses every chunk's
   * type table into a single id-to-name mapping. Recordings whose chunks redefine the same type id
   * (e.g. Datadog profiler output, where chunk A's {@code jdk.VirtualizationInformation} and chunk
   * B's {@code datadog.ExecutionSample} both carry id 101) lose the chunk-local types entirely.
   * Event payloads, by contrast, are resolved against their own chunk's metadata, so a recovery
   * scan of the event stream can rebuild the missing descriptors. Lookups that miss the merged view
   * fall back to that scan, and {@link #loadAllClasses(Path)} enriches its result with the
   * recovered types. Ids in the enriched view are chunk-local and not unique across chunks.
   *
   * <p>Detection and recovery happen only when metadata is actually requested through this class,
   * and only for types that have at least one event in the file — chunk-local types with zero
   * events and types unreachable from recovered event field descriptors are unrecoverable. {@link
   * #loadClass(Path, String)} prefers the merged descriptor, falling back to the first recovered
   * descriptor in file order. Listings contain one representative per name, including reachable
   * nested types. The recovery scan is capped at {@link #MAX_RECOVERY_SCAN_EVENTS} events.
   */
  static final class JdkMetadataSource implements MetadataSource {
    private static final Logger log = LoggerFactory.getLogger(JdkMetadataSource.class);

    /** Upper bound for the chunk-local recovery scan in this class. */
    private static final long MAX_RECOVERY_SCAN_EVENTS = 2_000_000;

    /** The recovery scan's event cap: {@link #MAX_RECOVERY_SCAN_EVENTS} outside tests. */
    private final long maxRecoveryScanEvents;

    /** Where the cap and chunk-local-type warnings go: the log outside tests. */
    private final Consumer<String> warnings;

    JdkMetadataSource() {
      this(MAX_RECOVERY_SCAN_EVENTS, log::warn);
    }

    /** For tests: a smaller scan cap, and the warnings captured instead of logged. */
    JdkMetadataSource(long maxRecoveryScanEvents, Consumer<String> warnings) {
      this.maxRecoveryScanEvents = maxRecoveryScanEvents;
      this.warnings = warnings;
    }

    /**
     * Normalized absolute path of the recording the chunk-local-types warning was last emitted for.
     */
    private static final AtomicReference<String> chunkLocalTypesWarnedFor = new AtomicReference<>();

    @Override
    public Map<String, Object> loadClass(Path recording, String typeName) throws Exception {
      Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
      Set<String> seen = new HashSet<>();
      try (RecordingFile rf = new RecordingFile(recording)) {
        for (EventType eventType : rf.readEventTypes()) {
          seen.add(typeKey(eventType));
          indexEventType(eventType, byName);
        }
      }
      if (byName.containsKey(typeName)) return byName.get(typeName);
      // The merged view drops chunk-local types on id collision; recover from events,
      // whose types resolve against their own chunk's metadata.
      try (RecordingFile rf = new RecordingFile(recording)) {
        long scanned = 0;
        while (rf.hasMoreEvents()) {
          RecordedEvent event = rf.readEvent();
          if (event == null) {
            break;
          }
          if (++scanned > maxRecoveryScanEvents) {
            warnings.accept(
                "Stopped the chunk-local type recovery scan of '"
                    + recording
                    + "' after "
                    + maxRecoveryScanEvents
                    + " events while looking up '"
                    + typeName
                    + "'; treating the type as not found");
            return null;
          }
          EventType eventType = event.getEventType();
          if (seen.add(typeKey(eventType))) indexEventType(eventType, byName);
          if (byName.containsKey(typeName)) {
            warnChunkLocalTypes(recording);
            return byName.get(typeName);
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
      Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
      Set<String> seen = new HashSet<>();
      Map<Long, String> mergedIdToName = new HashMap<>();
      try (RecordingFile rf = new RecordingFile(recording)) {
        for (EventType eventType : rf.readEventTypes()) {
          seen.add(typeKey(eventType));
          mergedIdToName.put(eventType.getId(), eventType.getName());
          indexEventType(eventType, byName);
        }
      }
      // Recover chunk-local types that the merged view dropped. Types with zero events in the
      // recording cannot be recovered this way and remain absent by design.
      boolean divergent = false;
      long scanned = 0;
      boolean capped = false;
      try (RecordingFile rf = new RecordingFile(recording)) {
        while (rf.hasMoreEvents()) {
          RecordedEvent event = rf.readEvent();
          if (event == null) {
            break;
          }
          EventType eventType = event.getEventType();
          String mergedName = mergedIdToName.get(eventType.getId());
          if (mergedName != null && !mergedName.equals(eventType.getName())) {
            divergent = true;
          }
          if (++scanned > maxRecoveryScanEvents) {
            capped = true;
            break;
          }
          if (seen.add(typeKey(eventType))) {
            indexEventType(eventType, byName);
          }
        }
      }
      if (divergent) {
        warnChunkLocalTypes(recording);
      }
      if (capped) {
        warnings.accept(
            "Stopped the chunk-local type recovery scan of '"
                + recording
                + "' after "
                + maxRecoveryScanEvents
                + " events; types that only occur later in the recording may be missing from the "
                + "metadata view");
      }
      return new ArrayList<>(byName.values());
    }

    private static void indexEventType(EventType type, Map<String, Map<String, Object>> byName) {
      byName.putIfAbsent(type.getName(), convertEventType(type));
      collectFieldTypes(type.getFields(), byName);
    }

    private static void collectFieldTypes(
        List<ValueDescriptor> fields, Map<String, Map<String, Object>> byName) {
      for (ValueDescriptor field : fields) {
        String name = field.getTypeName();
        if (byName.containsKey(name)) continue;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", field.getTypeId());
        meta.put("name", name);
        meta.put("superType", null);
        meta.put(
            "primitive",
            Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double")
                .contains(name));
        addFields(meta, field.getFields());
        byName.put(name, meta);
        collectFieldTypes(field.getFields(), byName);
      }
    }

    private static String typeKey(EventType type) {
      return type.getId() + ":" + type.getName();
    }

    /**
     * Warns that {@code recording} has chunk-local types, unless the previous warning was for the
     * same recording: the state is one process-wide slot, shared by every source whatever its sink,
     * so A, A warns once, and A, B, A warns three times.
     */
    private void warnChunkLocalTypes(Path recording) {
      String key = recording.toAbsolutePath().normalize().toString();
      String previous = chunkLocalTypesWarnedFor.get();
      if (key.equals(previous)) {
        return;
      }
      if (chunkLocalTypesWarnedFor.compareAndSet(previous, key)) {
        warnings.accept(
            "Recording '"
                + recording
                + "' redefines type ids across chunks (per-chunk metadata); the JDK "
                + "backend's merged metadata view misattributes or hides chunk-local types. "
                + "Chunk-local types were recovered from event payloads. Prefer the 'jafar' "
                + "backend for faithful per-chunk metadata.");
      }
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
        String value = annotationSuffix(ann);
        classAnn.add("@" + simple + value);
        classAnnFull.add(typeName + value);
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

      addFields(meta, eventType.getFields());
      return meta;
    }

    private static void addFields(Map<String, Object> meta, List<ValueDescriptor> fields) {
      Map<String, Map<String, Object>> fieldsByName = new LinkedHashMap<>();
      List<String> fieldsDisplay = new ArrayList<>();
      for (ValueDescriptor field : fields) {
        Map<String, Object> fm = new HashMap<>();
        String fName = field.getName();
        String fType = field.getTypeName();
        fm.put("name", fName);
        fm.put("type", fType);
        fm.put("dimension", field.isArray() ? 1 : 0);
        fm.put("hasConstantPool", false); // Not available via JDK API
        fm.put("label", field.getLabel());
        fm.put("description", field.getDescription());

        // Field annotations
        List<String> ann = new ArrayList<>();
        List<String> annFull = new ArrayList<>();
        for (var a : field.getAnnotationElements()) {
          String typeName = a.getTypeName();
          String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
          String value = annotationSuffix(a);
          ann.add("@" + simple + value);
          annFull.add(typeName + value);
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
    }

    private static String annotationSuffix(AnnotationElement annotation) {
      if (annotation.getValues().isEmpty()) return "";
      return "("
          + annotation.getValues().stream()
              .map(
                  value ->
                      value instanceof Object[] array
                          ? Arrays.deepToString(array)
                          : String.valueOf(value))
              .collect(Collectors.joining(", "))
          + ")";
    }
  }
}

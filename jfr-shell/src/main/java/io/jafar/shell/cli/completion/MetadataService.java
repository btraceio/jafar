package io.jafar.shell.cli.completion;

import io.jafar.shell.core.SessionManager;
import io.jafar.shell.providers.MetadataProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached metadata service for completion. Provides a single point of access for event types, field
 * names, and nested field information. Caches metadata to avoid repeated file parsing on every
 * keystroke.
 */
public class MetadataService {
  private final SessionManager sessions;

  /** Cache of type name -> metadata map */
  private final Map<String, Map<String, Object>> metadataCache = new ConcurrentHashMap<>();

  /** Cache key for recording path - invalidate cache when path changes */
  private Path cachedRecordingPath;

  public MetadataService(SessionManager sessions) {
    this.sessions = sessions;
  }

  /** Check if there is an active session. */
  public boolean hasActiveSession() {
    return sessions.getCurrent().isPresent();
  }

  /** Get the current recording path, or null if no session. */
  public Path getRecordingPath() {
    return sessions.getCurrent().map(entry -> entry.session.getRecordingPath()).orElse(null);
  }

  /** Get all available event type names from the current session. */
  public Set<String> getEventTypes() {
    return sessions
        .getCurrent()
        .map(entry -> entry.session.getAvailableEventTypes())
        .orElse(Collections.emptySet());
  }

  /** Get all metadata type names from the current session. */
  public Set<String> getAllMetadataTypes() {
    return sessions
        .getCurrent()
        .map(entry -> entry.session.getAllMetadataTypes())
        .orElse(Collections.emptySet());
  }

  /** Get constant pool type names from the current session. */
  public Set<String> getConstantPoolTypes() {
    return sessions
        .getCurrent()
        .map(entry -> entry.session.getAvailableConstantPoolTypes())
        .orElse(Collections.emptySet());
  }

  /** Get available chunk IDs from the current session. */
  public List<Integer> getChunkIds() {
    return sessions
        .getCurrent()
        .map(entry -> entry.session.getAvailableChunkIds())
        .orElse(Collections.emptyList());
  }

  /**
   * Get field names for a given event/metadata type. Results are cached.
   *
   * @param typeName the fully qualified type name
   * @return list of field names, or empty list if type not found
   */
  public List<String> getFieldNames(String typeName) {
    Map<String, Object> meta = getMetadata(typeName);
    if (meta == null) {
      return Collections.emptyList();
    }
    Object fieldsByName = meta.get("fieldsByName");
    if (fieldsByName instanceof Map<?, ?> map) {
      return new ArrayList<>(
          map.keySet().stream().filter(k -> k instanceof String).map(k -> (String) k).toList());
    }
    return Collections.emptyList();
  }

  /**
   * Get metadata for a specific field within a type.
   *
   * @param typeName the fully qualified type name
   * @param fieldName the field name
   * @return field metadata map or null if not found
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> getFieldMetadata(String typeName, String fieldName) {
    Map<String, Object> meta = getMetadata(typeName);
    if (meta == null) {
      return null;
    }
    Object fieldsByName = meta.get("fieldsByName");
    if (fieldsByName instanceof Map<?, ?> map) {
      Object field = map.get(fieldName);
      if (field instanceof Map<?, ?>) {
        return (Map<String, Object>) field;
      }
    }
    return null;
  }

  /**
   * Get the type name of a field.
   *
   * @param typeName the containing type name
   * @param fieldName the field name
   * @return the field's type name or null if not found
   */
  public String getFieldType(String typeName, String fieldName) {
    Map<String, Object> fieldMeta = getFieldMetadata(typeName, fieldName);
    if (fieldMeta != null) {
      Object type = fieldMeta.get("type");
      if (type instanceof String) {
        return (String) type;
      }
    }
    return null;
  }

  /**
   * Get field names for a nested path. For example, getNestedFieldNames("jdk.ExecutionSample",
   * "sampledThread") returns fields of the Thread type.
   *
   * @param typeName the root type name
   * @param path path segments leading to the nested type
   * @return list of field names at the nested level, or empty list
   */
  public List<String> getNestedFieldNames(String typeName, List<String> path) {
    if (path.isEmpty()) {
      return getFieldNames(typeName);
    }

    // Navigate through the path to find the nested type
    String currentType = typeName;
    for (String segment : path) {
      String fieldType = getFieldType(currentType, segment);
      if (fieldType == null) {
        return Collections.emptyList();
      }
      currentType = fieldType;
    }
    return getFieldNames(currentType);
  }

  /**
   * Get full metadata for a type (cached).
   *
   * @param typeName the type name
   * @return metadata map or null if not found
   */
  public Map<String, Object> getMetadata(String typeName) {
    Path currentPath = getRecordingPath();
    if (currentPath == null) {
      return null;
    }

    // Invalidate cache if recording path changed
    if (!currentPath.equals(cachedRecordingPath)) {
      invalidateCache();
      cachedRecordingPath = currentPath;
    }

    return metadataCache.computeIfAbsent(
        typeName,
        name -> {
          try {
            return MetadataProvider.loadClass(currentPath, name);
          } catch (Exception e) {
            // Return null for types that can't be loaded
            return null;
          }
        });
  }

  /** Invalidate all cached metadata. Call this when switching sessions or recording files. */
  public void invalidateCache() {
    metadataCache.clear();
    cachedRecordingPath = null;
  }
}

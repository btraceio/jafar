package io.jafar.hdump.shell.cli.completion;

import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.hdumppath.HdumpPath.ClassFields;
import io.jafar.hdump.shell.hdumppath.HdumpPath.GcRootFields;
import io.jafar.hdump.shell.hdumppath.HdumpPath.ObjectFields;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.completion.MetadataService;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Metadata service for heap dump completion. Provides class names and field information for
 * context-aware tab completion.
 */
public final class HdumpMetadataService implements MetadataService {

  private static final List<String> ROOT_TYPES = List.of("objects", "classes", "gcroots");

  private static final List<String> OPERATORS =
      List.of(
          "select",
          "top",
          "groupBy",
          "count",
          "sum",
          "stats",
          "sortBy",
          "head",
          "tail",
          "filter",
          "distinct",
          "pathToRoot",
          "checkLeaks",
          "dominators");

  /** Field names for objects root type */
  private static final List<String> OBJECT_FIELDS =
      List.of(
          ObjectFields.ID,
          ObjectFields.CLASS,
          ObjectFields.CLASS_NAME,
          ObjectFields.SHALLOW,
          ObjectFields.SHALLOW_SIZE,
          ObjectFields.RETAINED,
          ObjectFields.RETAINED_SIZE,
          ObjectFields.ARRAY_LENGTH,
          ObjectFields.STRING_VALUE);

  /** Field names for classes root type */
  private static final List<String> CLASS_FIELDS =
      List.of(
          ClassFields.ID,
          ClassFields.NAME,
          ClassFields.SIMPLE_NAME,
          ClassFields.INSTANCE_COUNT,
          ClassFields.INSTANCE_SIZE,
          ClassFields.SUPER_CLASS,
          ClassFields.IS_ARRAY);

  /** Field names for gcroots root type */
  private static final List<String> GC_ROOT_FIELDS =
      List.of(
          GcRootFields.TYPE,
          GcRootFields.OBJECT_ID,
          GcRootFields.OBJECT,
          GcRootFields.THREAD_SERIAL,
          GcRootFields.FRAME_NUMBER);

  private final SessionManager sessions;

  public HdumpMetadataService(SessionManager sessions) {
    this.sessions = sessions;
  }

  @Override
  public boolean hasActiveSession() {
    return getHeapSession() != null;
  }

  @Override
  public Path getFilePath() {
    HeapSession session = getHeapSession();
    return session != null ? session.getFilePath() : null;
  }

  @Override
  public Set<String> getAvailableTypes() {
    HeapSession session = getHeapSession();
    return session != null ? session.getAvailableTypes() : Collections.emptySet();
  }

  @Override
  public List<String> getFieldNames(String typeName) {
    // For hdump, typeName is the root type (objects, classes, gcroots)
    // not a class name like in JFR
    return getFieldsForRootType(typeName);
  }

  /**
   * Get fields for a specific root type.
   *
   * @param rootType the root type (objects, classes, gcroots)
   * @return list of field names
   */
  public List<String> getFieldsForRootType(String rootType) {
    if (rootType == null) {
      return Collections.emptyList();
    }
    return switch (rootType.toLowerCase()) {
      case "objects" -> OBJECT_FIELDS;
      case "classes" -> CLASS_FIELDS;
      case "gcroots" -> GC_ROOT_FIELDS;
      default -> Collections.emptyList();
    };
  }

  @Override
  public List<String> getRootTypes() {
    return ROOT_TYPES;
  }

  @Override
  public List<String> getOperators() {
    return OPERATORS;
  }

  @Override
  public Set<String> getVariableNames() {
    return sessions
        .getCurrent()
        .map(entry -> entry.variables.names())
        .orElse(Collections.emptySet());
  }

  @Override
  public void invalidateCache() {
    // No caching for hdump metadata - it's all static or from session
  }

  private HeapSession getHeapSession() {
    return sessions
        .getCurrent()
        .filter(entry -> entry.session instanceof HeapSession)
        .map(entry -> (HeapSession) entry.session)
        .orElse(null);
  }
}

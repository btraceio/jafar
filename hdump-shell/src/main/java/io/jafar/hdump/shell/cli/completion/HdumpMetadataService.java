package io.jafar.hdump.shell.cli.completion;

import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.hdumppath.HdumpPath.AgeFields;
import io.jafar.hdump.shell.hdumppath.HdumpPath.ClassFields;
import io.jafar.hdump.shell.hdumppath.HdumpPath.ClusterFields;
import io.jafar.hdump.shell.hdumppath.HdumpPath.DuplicateFields;
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

  private static final List<String> ROOT_TYPES =
      List.of("objects", "classes", "gcroots", "clusters", "duplicates", "ages");

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
          "retentionPaths",
          "retainedBreakdown",
          "checkLeaks",
          "dominators",
          "len",
          "uppercase",
          "lowercase",
          "trim",
          "replace",
          "abs",
          "round",
          "floor",
          "ceil",
          "waste",
          "join",
          "objects",
          "threadOwner",
          "dominatedSize",
          "whatif",
          "estimateAge");

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

  /** Field names for clusters root type */
  private static final List<String> CLUSTER_FIELDS =
      List.of(
          ClusterFields.ID,
          ClusterFields.OBJECT_COUNT,
          ClusterFields.RETAINED_SIZE,
          ClusterFields.ROOT_PATH_COUNT,
          ClusterFields.SCORE,
          ClusterFields.DOMINANT_CLASS,
          ClusterFields.ANCHOR_TYPE,
          ClusterFields.ANCHOR_OBJECT);

  /** Field names for duplicates root type */
  private static final List<String> DUPLICATE_FIELDS =
      List.of(
          DuplicateFields.ID,
          DuplicateFields.ROOT_CLASS,
          DuplicateFields.FINGERPRINT,
          DuplicateFields.COPIES,
          DuplicateFields.UNIQUE_SIZE,
          DuplicateFields.WASTED_BYTES,
          DuplicateFields.DEPTH,
          DuplicateFields.NODE_COUNT);

  /** Field names for ages root type */
  private static final List<String> AGE_FIELDS =
      List.of(
          ObjectFields.ID,
          ObjectFields.CLASS_NAME,
          ObjectFields.SHALLOW_SIZE,
          ObjectFields.RETAINED_SIZE,
          AgeFields.ESTIMATED_AGE,
          AgeFields.AGE_BUCKET,
          AgeFields.AGE_SIGNALS);

  private final SessionManager<HeapSession> sessions;

  public HdumpMetadataService(SessionManager<HeapSession> sessions) {
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
      case "clusters" -> CLUSTER_FIELDS;
      case "duplicates" -> DUPLICATE_FIELDS;
      case "ages" -> AGE_FIELDS;
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
    return sessions.getCurrent().map(entry -> entry.session).orElse(null);
  }
}

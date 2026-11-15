package io.jafar.shell.backend;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Abstraction for metadata queries against a JFR recording. */
public interface MetadataSource {

  /**
   * Load metadata for a specific class/type.
   *
   * <p>Implementations must return deterministic results: {@code loadClass} returns a stable result
   * for the same input across calls. The TCK enforces this.
   *
   * @param recording path to the JFR recording file
   * @param typeName fully qualified type name (e.g., "jdk.ExecutionSample")
   * @return metadata map with id, name, superType, fields, annotations, settings; or null if not
   *     found
   * @throws Exception if parsing fails
   */
  Map<String, Object> loadClass(Path recording, String typeName) throws Exception;

  /**
   * Load metadata for a single field under a class.
   *
   * @param recording path to the JFR recording file
   * @param typeName fully qualified type name
   * @param fieldName field name within the type
   * @return field metadata map with name, type, dimension, annotations; or null if not found
   * @throws Exception if parsing fails
   */
  Map<String, Object> loadField(Path recording, String typeName, String fieldName) throws Exception;

  /**
   * Load metadata for all classes in the recording.
   *
   * <p>Implementations must return deterministic results: {@code loadAllClasses} returns each class
   * (id, name pair) at most once, in a stable order across calls. The TCK enforces this.
   *
   * @param recording path to the JFR recording file
   * @return list of metadata maps for all classes
   * @throws Exception if parsing fails
   */
  List<Map<String, Object>> loadAllClasses(Path recording) throws Exception;
}

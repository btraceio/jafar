package io.jafar.shell.providers;

import io.jafar.shell.backend.BackendCapability;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.backend.JfrBackend;
import io.jafar.shell.backend.MetadataSource;
import io.jafar.shell.backend.UnsupportedCapabilityException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Provider for building summarized metadata views (classes, fields, annotations, settings).
 *
 * <p>Delegates to the current backend's {@link MetadataSource} implementation.
 */
public final class MetadataProvider {
  private MetadataProvider() {}

  private static MetadataSource getSource() throws UnsupportedCapabilityException {
    JfrBackend backend = BackendRegistry.getInstance().getCurrent();
    return backend.createMetadataSource();
  }

  /** Build a summarized metadata map for a given class name in the recording. */
  public static Map<String, Object> loadClass(Path recording, String typeName) throws Exception {
    if (System.getProperty("jfr.shell.completion.debug") != null) {
      System.err.println("[DEBUG] MetadataProvider.loadClass() called");
      System.err.println("[DEBUG]   recording: " + recording);
      System.err.println("[DEBUG]   typeName: " + typeName);
    }

    MetadataSource source = getSource();
    if (System.getProperty("jfr.shell.completion.debug") != null) {
      System.err.println("[DEBUG]   source: " + source.getClass().getName());
    }

    Map<String, Object> result = source.loadClass(recording, typeName);
    if (System.getProperty("jfr.shell.completion.debug") != null) {
      System.err.println(
          "[DEBUG]   result: " + (result != null ? "non-null (" + result.size() + " keys)" : "NULL"));
    }

    return result;
  }

  /** Load metadata for a single field under a class name. */
  public static Map<String, Object> loadField(Path recording, String typeName, String fieldName)
      throws Exception {
    return getSource().loadField(recording, typeName, fieldName);
  }

  /** Load metadata for all classes in the recording. */
  public static List<Map<String, Object>> loadAllClasses(Path recording) throws Exception {
    return getSource().loadAllClasses(recording);
  }

  /**
   * Check if metadata queries are supported by the current backend.
   *
   * @return true if supported
   */
  public static boolean isSupported() {
    return BackendRegistry.getInstance().getCurrent().supports(BackendCapability.METADATA_CLASSES);
  }
}

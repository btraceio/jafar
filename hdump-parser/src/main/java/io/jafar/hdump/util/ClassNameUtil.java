package io.jafar.hdump.util;

/**
 * Utility for converting between Java class name formats.
 *
 * <p>Heap dumps (HPROF format) store class names in <strong>internal format</strong> using slashes
 * as delimiters (e.g., {@code "java/lang/String"}), following the JVM specification. Java source
 * code uses <strong>qualified format</strong> with dots (e.g., {@code "java.lang.String"}).
 *
 * <p>This utility provides explicit conversion methods to avoid confusion and bugs from mixing
 * formats.
 *
 * <h2>Format Guidelines</h2>
 *
 * <ul>
 *   <li><strong>Internal APIs</strong> (HeapDump, HeapClass): Always use internal format
 *   <li><strong>User-facing code</strong> (display, completion): Convert to qualified format
 *   <li><strong>Storage</strong> (heap dump files): Native internal format
 * </ul>
 *
 * <h2>Examples</h2>
 *
 * <pre>{@code
 * // Finding classes in heap dump (internal format required)
 * dump.getClassByName(ClassNameUtil.toInternal("java.lang.String"));
 * dump.getObjectsOfClass("java/lang/String");
 *
 * // Displaying class names to user (convert to qualified format)
 * String displayName = ClassNameUtil.toQualified(heapClass.getName());
 * }</pre>
 */
public final class ClassNameUtil {

  /**
   * Converts internal format to qualified format.
   *
   * <p>Example: {@code "java/lang/String"} → {@code "java.lang.String"}
   *
   * @param internalName class name in internal format (slash-delimited)
   * @return qualified class name (dot-delimited), or null if input is null
   */
  public static String toQualified(String internalName) {
    if (internalName == null) {
      return null;
    }
    return internalName.replace('/', '.');
  }

  /**
   * Converts qualified format to internal format.
   *
   * <p>Example: {@code "java.lang.String"} → {@code "java/lang/String"}
   *
   * @param qualifiedName class name in qualified format (dot-delimited)
   * @return internal class name (slash-delimited), or null if input is null
   */
  public static String toInternal(String qualifiedName) {
    if (qualifiedName == null) {
      return null;
    }
    return qualifiedName.replace('.', '/');
  }

  /**
   * Checks if name is in internal format.
   *
   * <p>A class name is considered internal format if it contains slashes.
   *
   * @param name class name to check
   * @return true if the name appears to be in internal format
   */
  public static boolean isInternal(String name) {
    return name != null && name.contains("/");
  }

  /**
   * Checks if name is in qualified format.
   *
   * <p>A class name is considered qualified format if it contains dots.
   *
   * @param name class name to check
   * @return true if the name appears to be in qualified format
   */
  public static boolean isQualified(String name) {
    return name != null && name.contains(".");
  }

  private ClassNameUtil() {
    // Utility class
  }
}

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

  /**
   * Converts any JVM type name to human-readable Java form.
   *
   * <p>Handles primitive descriptors ({@code Z}→{@code boolean}, {@code B}→{@code byte}, etc.),
   * array descriptors ({@code [B}→{@code byte[]}, {@code [Ljava/lang/String;}→{@code
   * java.lang.String[]}), and internal class names ({@code java/lang/String}→{@code
   * java.lang.String}). Already-qualified or plain names pass through unchanged.
   *
   * @param name JVM type name in any format
   * @return human-readable Java type name, or null if input is null
   */
  public static String toHumanReadable(String name) {
    if (name == null || name.isEmpty()) {
      return name;
    }

    // Count leading '[' for array dimensions
    int dims = 0;
    while (dims < name.length() && name.charAt(dims) == '[') {
      dims++;
    }

    String base;
    if (dims > 0) {
      // Array type — resolve base component
      String descriptor = name.substring(dims);
      if (descriptor.length() == 1) {
        base = primitiveDescriptor(descriptor.charAt(0));
        if (base == null) {
          return name; // unknown single-char descriptor
        }
      } else if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
        base = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
      } else {
        return name; // unexpected format
      }
    } else if (name.length() == 1) {
      // Single-char primitive descriptor without array prefix
      String prim = primitiveDescriptor(name.charAt(0));
      return prim != null ? prim : name;
    } else {
      // Regular class name — just replace slashes with dots
      return name.replace('/', '.');
    }

    // Append [] suffixes for each array dimension
    StringBuilder sb = new StringBuilder(base.length() + dims * 2);
    sb.append(base);
    for (int i = 0; i < dims; i++) {
      sb.append("[]");
    }
    return sb.toString();
  }

  private static String primitiveDescriptor(char c) {
    switch (c) {
      case 'Z':
        return "boolean";
      case 'B':
        return "byte";
      case 'C':
        return "char";
      case 'S':
        return "short";
      case 'I':
        return "int";
      case 'J':
        return "long";
      case 'F':
        return "float";
      case 'D':
        return "double";
      default:
        return null;
    }
  }

  private ClassNameUtil() {
    // Utility class
  }
}

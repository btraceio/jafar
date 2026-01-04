package io.jafar.shell.cli;

import io.jafar.shell.providers.MetadataProvider;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tree renderer for metadata classes, with optional recursive expansion of field types. */
public final class TreeRenderer {
  private TreeRenderer() {}

  /** Non-recursive rendering of a single metadata class map. */
  public static void renderMetadata(Map<String, Object> meta, CommandDispatcher.IO io) {
    renderMetadata(meta, io, 0);
  }

  private static void renderMetadata(Map<String, Object> meta, CommandDispatcher.IO io, int depth) {
    PagedPrinter pager = PagedPrinter.forIO(io);
    String name = String.valueOf(meta.getOrDefault("name", "<unknown>"));
    String superType = String.valueOf(meta.getOrDefault("superType", "<none>"));
    Object idObj = meta.get("id");
    String id = idObj != null ? String.valueOf(idObj) : "<unknown>";
    pager.println(indent(depth) + name + " (id: " + id + ")");
    pager.println(indent(depth + 1) + "superType: " + superType);

    Object ann = meta.get("classAnnotations");
    if (ann instanceof List<?> a && !a.isEmpty()) {
      pager.println(indent(depth + 1) + "annotations:");
      for (Object v : a) pager.println(indent(depth + 2) + String.valueOf(v));
    }

    Object settings = meta.get("settings");
    if (settings instanceof List<?> s && !s.isEmpty()) {
      pager.println(indent(depth + 1) + "settings:");
      for (Object v : s) pager.println(indent(depth + 2) + String.valueOf(v));
    }

    Object fbn = meta.get("fieldsByName");
    if (fbn instanceof Map<?, ?> fields && !fields.isEmpty()) {
      pager.println(indent(depth + 1) + "fields:");
      // Stable iteration order by sorting names
      java.util.List<String> names = new java.util.ArrayList<>();
      for (Object k : fields.keySet()) names.add(String.valueOf(k));
      java.util.Collections.sort(names);
      for (String fname : names) {
        Object rec = fields.get(fname);
        if (rec instanceof Map<?, ?> fm) {
          Object typeObj = fm.get("type");
          String type = String.valueOf(typeObj == null ? "?" : typeObj);
          if ("null".equals(type)) {
            // skip fields with 'null' type
            continue;
          }
          Object dim = fm.get("dimension");
          int d = (dim instanceof Number) ? ((Number) dim).intValue() : -1;
          String dimSuffix = d > 0 ? "[]".repeat(d) : "";
          pager.println(indent(depth + 2) + fname + ": " + type + dimSuffix);
          Object a = fm.get("annotations");
          if (a instanceof List<?> fa && !fa.isEmpty()) {
            pager.println(indent(depth + 3) + "annotations:");
            for (Object v : fa) pager.println(indent(depth + 4) + String.valueOf(v));
          }
        }
      }
    }
  }

  /**
   * Recursive rendering: prints the class, and for each field whose type is a non-primitive
   * metadata class, loads and prints its subtree. Cycles are prevented via a visited set.
   */
  public static void renderMetadataRecursive(
      Path recording, String rootType, CommandDispatcher.IO io) {
    renderMetadataRecursive(recording, rootType, io, 10); // default depth 10
  }

  public static void renderMetadataRecursive(
      Path recording, String rootType, CommandDispatcher.IO io, int maxDepth) {
    Set<String> pathVisited = new HashSet<>();
    int limit = Math.max(0, maxDepth);
    PagedPrinter pager = PagedPrinter.forIO(io);
    renderMetadataRecursive(recording, rootType, io, pager, pathVisited, 0, 0, limit);
  }

  /**
   * Render a specific field of a metadata class as the root, including its annotations and
   * recursively expanding the field's type metadata inline according to the provided depth.
   */
  public static void renderFieldRecursive(
      Path recording, String ownerType, String fieldName, CommandDispatcher.IO io, int maxDepth) {
    Map<String, Object> field;
    Map<String, Object> owner;
    try {
      field = MetadataProvider.loadField(recording, ownerType, fieldName);
      owner = MetadataProvider.loadClass(recording, ownerType);
    } catch (Exception e) {
      return;
    }
    if (field == null) return;
    PagedPrinter pager = PagedPrinter.forIO(io);

    Object typeObj = field.get("type");
    String fieldType = String.valueOf(typeObj == null ? "?" : typeObj);
    if ("null".equals(fieldType)) return;
    Object dim = field.get("dimension");
    int dval = (dim instanceof Number) ? ((Number) dim).intValue() : -1;
    String dimSuffix = dval > 0 ? "[]".repeat(dval) : "";

    pager.println(fieldName + ": " + fieldType + dimSuffix);
    Object a = field.get("annotations");
    if (a instanceof java.util.List<?> fa && !fa.isEmpty()) {
      pager.println(indent(1) + "annotations:");
      for (Object v : fa) pager.println(indent(2) + String.valueOf(v));
    }

    // Try to recurse into the field type if depth allows; actual recursion will only
    // occur if the type exists in JFR metadata (checked inside renderMetadataRecursive)
    if (maxDepth > 0) {
      Set<String> pathVisited = new HashSet<>();
      pathVisited.add(
          String.valueOf(owner != null ? owner.getOrDefault("name", ownerType) : ownerType));
      renderMetadataRecursive(
          recording, fieldType, io, pager, pathVisited, 1, 1, Math.max(0, maxDepth));
    }
  }

  private static void renderMetadataRecursive(
      Path recording,
      String typeName,
      CommandDispatcher.IO io,
      PagedPrinter pager,
      Set<String> pathVisited,
      int level,
      int indent,
      int maxDepth) {
    if (level > maxDepth) {
      // Depth exceeded; do not render deeper
      return;
    }
    Map<String, Object> meta;
    try {
      meta = MetadataProvider.loadClass(recording, typeName);
    } catch (Exception e) {
      // Skip on error to avoid noisy output
      return;
    }
    if (meta == null) {
      // Unknown or non-metadata type; skip
      return;
    }
    // Render this class and inline recurse under each field
    String name = String.valueOf(meta.getOrDefault("name", typeName));
    String superType = String.valueOf(meta.getOrDefault("superType", "<none>"));
    pager.println(indent(indent) + name);
    pager.println(indent(indent + 1) + "superType: " + superType);

    Object ann = meta.get("classAnnotations");
    if (ann instanceof java.util.List<?> a && !a.isEmpty()) {
      pager.println(indent(indent + 1) + "annotations:");
      for (Object v : a) pager.println(indent(indent + 2) + String.valueOf(v));
    }

    Object settings = meta.get("settings");
    if (settings instanceof java.util.List<?> s && !s.isEmpty()) {
      pager.println(indent(indent + 1) + "settings:");
      for (Object v : s) pager.println(indent(indent + 2) + String.valueOf(v));
    }

    Object fbn = meta.get("fieldsByName");
    if (fbn instanceof java.util.Map<?, ?> fields && !fields.isEmpty()) {
      pager.println(indent(indent + 1) + "fields:");
      java.util.List<String> names = new java.util.ArrayList<>();
      for (Object k : fields.keySet()) names.add(String.valueOf(k));
      java.util.Collections.sort(names);
      for (String fname : names) {
        Object rec = fields.get(fname);
        if (rec instanceof java.util.Map<?, ?> fm) {
          Object typeObj = fm.get("type");
          String fieldType = String.valueOf(typeObj == null ? "?" : typeObj);
          if ("null".equals(fieldType)) {
            // skip fields reported as 'null'
            continue;
          }
          Object dim = fm.get("dimension");
          int dval = (dim instanceof Number) ? ((Number) dim).intValue() : -1;
          String dimSuffix = dval > 0 ? "[]".repeat(dval) : "";
          pager.println(indent(indent + 2) + fname + ": " + fieldType + dimSuffix);
          Object a = fm.get("annotations");
          if (a instanceof java.util.List<?> fa && !fa.isEmpty()) {
            pager.println(indent(indent + 3) + "annotations:");
            for (Object v : fa) pager.println(indent(indent + 4) + String.valueOf(v));
          }
          if (pathVisited.contains(fieldType)) {
            // avoid cycles in current path; show type only
          } else {
            pathVisited.add(fieldType);
            // Inline the subtree under this field if metadata is available
            renderMetadataRecursive(
                recording, fieldType, io, pager, pathVisited, level + 1, indent + 3, maxDepth);
            pathVisited.remove(fieldType);
          }
        }
      }
    }
  }

  // No package-based exclusions: recursion happens only if the target type exists in JFR metadata.

  private static String indent(int n) {
    return "  ".repeat(Math.max(0, n));
  }
}

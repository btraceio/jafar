package io.jafar.shell.cli;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.tree.GuideStyle;
import dev.tamboui.widgets.tree.TreeNode;
import dev.tamboui.widgets.tree.TreeState;
import dev.tamboui.widgets.tree.TreeWidget;
import io.jafar.shell.providers.MetadataProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tree renderer using TamboUI TreeWidget for styled inline output. Renders metadata class
 * hierarchies as interactive-looking tree structures with Unicode guide lines.
 */
public final class TuiTreeRenderer {
  private static final int DEFAULT_WIDTH = 120;

  private TuiTreeRenderer() {}

  /** Non-recursive rendering of a single metadata class map. */
  public static void renderMetadata(Map<String, Object> meta, CommandDispatcher.IO io) {
    PagedPrinter pager = PagedPrinter.forIO(io);
    TreeNode<Object> root = buildMetadataNode(meta);
    root.expanded();
    expandAll(root);
    renderTree(List.of(root), pager);
  }

  /** Recursive rendering of a type and its field types. */
  public static void renderMetadataRecursive(
      Path recording, String rootType, CommandDispatcher.IO io) {
    renderMetadataRecursive(recording, rootType, io, 10);
  }

  public static void renderMetadataRecursive(
      Path recording, String rootType, CommandDispatcher.IO io, int maxDepth) {
    Set<String> visited = new HashSet<>();
    TreeNode<Object> root = buildRecursiveNode(recording, rootType, visited, 0, maxDepth);
    if (root == null) return;
    root.expanded();
    expandAll(root);
    PagedPrinter pager = PagedPrinter.forIO(io);
    renderTree(List.of(root), pager);
  }

  /** Render a specific field as tree root, recursing into its type. */
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

    Object typeObj = field.get("type");
    String fieldType = String.valueOf(typeObj == null ? "?" : typeObj);
    if ("null".equals(fieldType)) return;
    Object dim = field.get("dimension");
    int dval = (dim instanceof Number) ? ((Number) dim).intValue() : -1;
    String dimSuffix = dval > 0 ? "[]".repeat(dval) : "";

    TreeNode<Object> root = TreeNode.of(fieldName + ": " + fieldType + dimSuffix);
    root.expanded();

    // Add annotations
    Object a = field.get("annotations");
    if (a instanceof List<?> fa && !fa.isEmpty()) {
      TreeNode<Object> annNode = TreeNode.of("annotations");
      annNode.expanded();
      for (Object v : fa) {
        annNode.add(TreeNode.of(String.valueOf(v)));
      }
      root.add(annNode);
    }

    // Recurse into field type if depth allows
    if (maxDepth > 0) {
      Set<String> visited = new HashSet<>();
      String ownerName =
          owner != null ? String.valueOf(owner.getOrDefault("name", ownerType)) : ownerType;
      visited.add(ownerName);
      TreeNode<Object> typeTree = buildRecursiveNode(recording, fieldType, visited, 1, maxDepth);
      if (typeTree != null) {
        typeTree.expanded();
        expandAll(typeTree);
        root.add(typeTree);
      }
    }

    expandAll(root);
    PagedPrinter pager = PagedPrinter.forIO(io);
    renderTree(List.of(root), pager);
  }

  private static TreeNode<Object> buildMetadataNode(Map<String, Object> meta) {
    String name = String.valueOf(meta.getOrDefault("name", "<unknown>"));
    String superType = String.valueOf(meta.getOrDefault("superType", "<none>"));
    Object idObj = meta.get("id");
    String id = idObj != null ? String.valueOf(idObj) : "<unknown>";

    TreeNode<Object> node = TreeNode.of(name + " (id: " + id + ")");

    node.add(TreeNode.of("superType: " + superType));

    Object ann = meta.get("classAnnotations");
    if (ann instanceof List<?> a && !a.isEmpty()) {
      TreeNode<Object> annNode = TreeNode.of("annotations");
      for (Object v : a) annNode.add(TreeNode.of(String.valueOf(v)));
      node.add(annNode);
    }

    Object settings = meta.get("settings");
    if (settings instanceof List<?> s && !s.isEmpty()) {
      TreeNode<Object> setNode = TreeNode.of("settings");
      for (Object v : s) setNode.add(TreeNode.of(String.valueOf(v)));
      node.add(setNode);
    }

    Object fbn = meta.get("fieldsByName");
    if (fbn instanceof Map<?, ?> fields && !fields.isEmpty()) {
      TreeNode<Object> fieldsNode = TreeNode.of("fields");
      List<String> names = new ArrayList<>();
      for (Object k : fields.keySet()) names.add(String.valueOf(k));
      Collections.sort(names);
      for (String fname : names) {
        Object rec = fields.get(fname);
        if (rec instanceof Map<?, ?> fm) {
          Object typeObj = fm.get("type");
          String type = String.valueOf(typeObj == null ? "?" : typeObj);
          if ("null".equals(type)) continue;
          Object dim = fm.get("dimension");
          int d = (dim instanceof Number) ? ((Number) dim).intValue() : -1;
          String dimSuffix = d > 0 ? "[]".repeat(d) : "";
          TreeNode<Object> fieldNode = TreeNode.of(fname + ": " + type + dimSuffix);
          Object fa = fm.get("annotations");
          if (fa instanceof List<?> faList && !faList.isEmpty()) {
            TreeNode<Object> faNode = TreeNode.of("annotations");
            for (Object v : faList) faNode.add(TreeNode.of(String.valueOf(v)));
            fieldNode.add(faNode);
          }
          fieldsNode.add(fieldNode);
        }
      }
      node.add(fieldsNode);
    }
    return node;
  }

  private static TreeNode<Object> buildRecursiveNode(
      Path recording, String typeName, Set<String> visited, int level, int maxDepth) {
    if (level > maxDepth) return null;
    Map<String, Object> meta;
    try {
      meta = MetadataProvider.loadClass(recording, typeName);
    } catch (Exception e) {
      return null;
    }
    if (meta == null) return null;

    String name = String.valueOf(meta.getOrDefault("name", typeName));
    String superType = String.valueOf(meta.getOrDefault("superType", "<none>"));

    TreeNode<Object> node = TreeNode.of(name);
    node.add(TreeNode.of("superType: " + superType));

    Object ann = meta.get("classAnnotations");
    if (ann instanceof List<?> a && !a.isEmpty()) {
      TreeNode<Object> annNode = TreeNode.of("annotations");
      for (Object v : a) annNode.add(TreeNode.of(String.valueOf(v)));
      node.add(annNode);
    }

    Object settings = meta.get("settings");
    if (settings instanceof List<?> s && !s.isEmpty()) {
      TreeNode<Object> setNode = TreeNode.of("settings");
      for (Object v : s) setNode.add(TreeNode.of(String.valueOf(v)));
      node.add(setNode);
    }

    Object fbn = meta.get("fieldsByName");
    if (fbn instanceof Map<?, ?> fields && !fields.isEmpty()) {
      TreeNode<Object> fieldsNode = TreeNode.of("fields");
      List<String> names = new ArrayList<>();
      for (Object k : fields.keySet()) names.add(String.valueOf(k));
      Collections.sort(names);
      for (String fname : names) {
        Object rec = fields.get(fname);
        if (rec instanceof Map<?, ?> fm) {
          Object typeObj = fm.get("type");
          String fieldType = String.valueOf(typeObj == null ? "?" : typeObj);
          if ("null".equals(fieldType)) continue;
          Object dim = fm.get("dimension");
          int dval = (dim instanceof Number) ? ((Number) dim).intValue() : -1;
          String dimSuffix = dval > 0 ? "[]".repeat(dval) : "";
          TreeNode<Object> fieldNode = TreeNode.of(fname + ": " + fieldType + dimSuffix);
          Object fa = fm.get("annotations");
          if (fa instanceof List<?> faList && !faList.isEmpty()) {
            TreeNode<Object> faNode = TreeNode.of("annotations");
            for (Object v : faList) faNode.add(TreeNode.of(String.valueOf(v)));
            fieldNode.add(faNode);
          }
          // Recurse into field type if not visited
          if (!visited.contains(fieldType)) {
            visited.add(fieldType);
            TreeNode<Object> subTree =
                buildRecursiveNode(recording, fieldType, visited, level + 1, maxDepth);
            if (subTree != null) {
              fieldNode.add(subTree);
            }
            visited.remove(fieldType);
          }
          fieldsNode.add(fieldNode);
        }
      }
      node.add(fieldsNode);
    }
    return node;
  }

  @SuppressWarnings("unchecked")
  private static void renderTree(List<TreeNode<Object>> roots, PagedPrinter pager) {
    // Count total visible nodes for height estimation
    int nodeCount = countNodes(roots);

    TreeWidget<TreeNode<Object>> tree =
        TreeWidget.<TreeNode<Object>>builder()
            .roots(roots)
            .children(n -> (List<TreeNode<Object>>) (List<?>) n.children())
            .isLeaf(n -> n.children().isEmpty())
            .expansionState(
                TreeNode::isExpanded,
                (n, expanded) -> {
                  if (expanded != n.isExpanded()) n.toggleExpanded();
                })
            .guideStyle(GuideStyle.UNICODE)
            .highlightSymbol("")
            .simpleNodeRenderer(n -> Paragraph.builder().text(Text.raw(n.label())).build())
            .build();

    int width = terminalWidth();
    int height = nodeCount + 1;
    Buffer buffer = Buffer.empty(Rect.of(width, height));
    Frame frame = Frame.forTesting(buffer);
    frame.renderStatefulWidget(tree, buffer.area(), new TreeState());

    String output = buffer.toAnsiStringTrimmed();
    for (String line : output.split("\r?\n", -1)) {
      if (!line.isBlank()) {
        pager.println(line);
      }
    }
  }

  private static void expandAll(TreeNode<?> node) {
    node.expanded();
    for (var child : node.children()) {
      expandAll(child);
    }
  }

  private static int countNodes(List<? extends TreeNode<?>> nodes) {
    int count = 0;
    for (TreeNode<?> n : nodes) {
      count++;
      if (n.isExpanded()) {
        count += countNodes(n.children());
      }
    }
    return count;
  }

  private static int terminalWidth() {
    String cols = System.getenv("COLUMNS");
    if (cols != null) {
      try {
        return Math.max(40, Integer.parseInt(cols.trim()));
      } catch (NumberFormatException ignore) {
      }
    }
    String prop = System.getProperty("jfr.shell.width");
    if (prop != null) {
      try {
        return Math.max(40, Integer.parseInt(prop.trim()));
      } catch (NumberFormatException ignore) {
      }
    }
    return DEFAULT_WIDTH;
  }
}

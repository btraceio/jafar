package io.jafar.shell.tui;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import io.jafar.shell.cli.TuiTableRenderer;
import io.jafar.shell.tui.TuiContext.ResultTab;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds detail-pane content for the TUI. Handles tree-structured key-value display, stack trace
 * rendering, and metadata detail construction.
 */
public final class TuiDetailBuilder {
  private final TuiContext ctx;

  TuiDetailBuilder(TuiContext ctx) {
    this.ctx = ctx;
  }

  void buildDetailTabs(ResultTab tab) {
    ctx.detailHScrollOffset = 0;
    if (tab.tableData == null || tab.selectedRow < 0 || tab.selectedRow >= tab.tableData.size()) {
      ctx.detailTabNames = List.of();
      ctx.detailTabValues = List.of();
      ctx.activeDetailTabIndex = 0;
      ctx.detailTabScrollOffsets.clear();
      ctx.detailCursorLine = -1;
      ctx.detailLineTypeRefs = null;
      return;
    }

    // Metadata browser mode: show fields/settings instead of complex-value tabs
    if (tab.metadataClassCache != null) {
      Map<String, Object> meta =
          tab.selectedRow < tab.metadataClassCache.size()
              ? tab.metadataClassCache.get(tab.selectedRow)
              : null;
      if (meta != null) {
        Object nameObj = meta.get("name");
        String typeName = nameObj != null ? nameObj.toString() : "type";
        ctx.detailTabNames = List.of(typeName);
        ctx.detailMarqueeTick0 = ctx.renderTick;
        ctx.detailTabValues = List.of(meta);
        ctx.activeDetailTabIndex = 0;
        ctx.detailTabScrollOffsets.clear();
        ctx.detailTabScrollOffsets.add(0);
        buildMetadataDetailRefs(meta, tab);
        return;
      }
      // No metadata for this row — fall through to empty detail
      ctx.detailTabNames = List.of();
      ctx.detailTabValues = List.of();
      ctx.activeDetailTabIndex = 0;
      ctx.detailTabScrollOffsets.clear();
      ctx.detailCursorLine = -1;
      ctx.detailLineTypeRefs = null;
      return;
    }

    ctx.detailCursorLine = -1;
    ctx.detailLineTypeRefs = null;

    Map<String, Object> row = tab.tableData.get(tab.selectedRow);
    List<String> names = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    // Iterate all row keys (not just tableHeaders) to find complex values for detail pane
    for (String key : row.keySet()) {
      Object val = row.get(key);
      boolean include = isComplexValue(val) || ("threads".equals(key) && val instanceof Map<?, ?>);
      // "fields" tab: show even for single-field objects (size == 1 is meaningful here)
      if (!include && "fields".equals(key) && val instanceof Map<?, ?> m && !m.isEmpty()) {
        include = true;
      }
      if (include) {
        names.add(key);
        values.add(val);
      }
    }
    // Fallback: if no complex-value tabs found, show a "details" tab with all scalar row fields
    if (names.isEmpty() && !row.isEmpty()) {
      names.add("details");
      values.add(row);
    }

    ctx.detailTabNames = names;
    ctx.detailMarqueeTick0 = ctx.renderTick;
    ctx.detailTabValues = values;
    ctx.activeDetailTabIndex = Math.min(ctx.activeDetailTabIndex, Math.max(0, names.size() - 1));
    ctx.detailTabScrollOffsets.clear();
    for (int i = 0; i < names.size(); i++) {
      ctx.detailTabScrollOffsets.add(0);
    }

    // Build thread refs for the threads detail tab
    if (!values.isEmpty()) {
      int threadsIdx = names.indexOf("threads");
      if (threadsIdx >= 0 && values.get(threadsIdx) instanceof Map<?, ?> tm && tm.size() > 1) {
        buildThreadDetailRefs(tm, threadsIdx);
      }
    }

    // Build object field refs for the "fields" detail tab
    if (ctx.detailLineTypeRefs == null) {
      int fieldsIdx = names.indexOf("fields");
      if (fieldsIdx >= 0 && values.get(fieldsIdx) instanceof Map<?, ?> fm) {
        List<String> refs = buildObjectFieldRefs(fm);
        boolean hasRef = false;
        for (String r : refs) {
          if (r != null) {
            hasRef = true;
            break;
          }
        }
        if (hasRef) {
          ctx.detailLineTypeRefs = refs;
          ctx.detailCursorLine = 0;
          ctx.activeDetailTabIndex = fieldsIdx;
        }
      }
    }
  }

  void buildThreadDetailRefs(Map<?, ?> threadsMap, int threadsTabIdx) {
    // The threads map is rendered by formatTree as a flat list of key: value entries.
    // Each entry is a thread — tag it with "thread:<name>" for the key handler.
    List<String> refs = new ArrayList<>();
    for (Map.Entry<?, ?> entry : threadsMap.entrySet()) {
      refs.add("thread:" + entry.getKey());
    }
    ctx.detailLineTypeRefs = refs;
    ctx.detailCursorLine = refs.isEmpty() ? -1 : 0;
    ctx.activeDetailTabIndex = threadsTabIdx;
  }

  @SuppressWarnings("unchecked")
  void buildMetadataDetailRefs(Map<String, Object> meta, ResultTab tab) {
    Set<String> navigableTypes = new HashSet<>();
    if (tab.tableData != null) {
      for (Map<String, Object> row : tab.tableData) {
        Object n = row.get("name");
        if (n != null) navigableTypes.add(n.toString());
      }
    }

    List<String> refs = new ArrayList<>();

    // Fields section
    Object fieldsByName = meta.get("fieldsByName");
    if (fieldsByName instanceof Map<?, ?> fbm && !fbm.isEmpty()) {
      refs.add(null); // header line
      for (Map.Entry<?, ?> entry : fbm.entrySet()) {
        String typeName = null;
        if (entry.getValue() instanceof Map<?, ?> fm) {
          Object typeObj = fm.get("type");
          if (typeObj != null) {
            typeName = typeObj.toString();
          }
        }
        refs.add(typeName != null && navigableTypes.contains(typeName) ? typeName : null);
      }
    }

    // Settings section
    Object settingsByName = meta.get("settingsByName");
    if (settingsByName instanceof Map<?, ?> sbm && !sbm.isEmpty()) {
      refs.add(null); // header line
      for (int i = 0; i < sbm.size(); i++) {
        refs.add(null);
      }
    }

    // Annotations section
    Object classAnnotations = meta.get("classAnnotations");
    if (classAnnotations instanceof List<?> ca && !ca.isEmpty()) {
      refs.add(null); // header line
      for (int i = 0; i < ca.size(); i++) {
        refs.add(null);
      }
    }

    ctx.detailLineTypeRefs = refs;
    ctx.detailCursorLine = refs.isEmpty() ? -1 : 0;
  }

  @SuppressWarnings("unchecked")
  String[] buildMetadataDetailLines(Map<String, Object> meta, ResultTab tab) {
    Set<String> navigableTypes = new HashSet<>();
    if (tab.tableData != null) {
      for (Map<String, Object> row : tab.tableData) {
        Object n = row.get("name");
        if (n != null) navigableTypes.add(n.toString());
      }
    }

    List<String> lines = new ArrayList<>();

    // Fields section
    Object fieldsByName = meta.get("fieldsByName");
    if (fieldsByName instanceof Map<?, ?> fbm && !fbm.isEmpty()) {
      lines.add("Fields (" + fbm.size() + "):");
      int idx = 0;
      int size = fbm.size();
      for (Map.Entry<?, ?> entry : fbm.entrySet()) {
        idx++;
        boolean last = (idx == size);
        String connector = last ? "\u2514\u2500 " : "\u251C\u2500 ";
        String fName = entry.getKey().toString();
        String fType = "";
        String annStr = "";
        if (entry.getValue() instanceof Map<?, ?> fm) {
          Object typeObj = fm.get("type");
          if (typeObj != null) fType = typeObj.toString();
          Object annObj = fm.get("annotations");
          if (annObj instanceof List<?> annList && !annList.isEmpty()) {
            annStr = " " + annList;
          }
        }
        String nav = navigableTypes.contains(fType) ? " \u2192" : "";
        lines.add(connector + fName + ": " + fType + annStr + nav);
      }
    }

    // Settings section
    Object settingsByName = meta.get("settingsByName");
    if (settingsByName instanceof Map<?, ?> sbm && !sbm.isEmpty()) {
      lines.add("Settings (" + sbm.size() + "):");
      int idx = 0;
      int size = sbm.size();
      for (Map.Entry<?, ?> entry : sbm.entrySet()) {
        idx++;
        boolean last = (idx == size);
        String connector = last ? "\u2514\u2500 " : "\u251C\u2500 ";
        String sName = entry.getKey().toString();
        String sType = "";
        String sDefault = "";
        if (entry.getValue() instanceof Map<?, ?> sm) {
          Object typeObj = sm.get("type");
          if (typeObj != null) sType = typeObj.toString();
          Object defObj = sm.get("defaultValue");
          if (defObj != null) sDefault = " = " + defObj;
        }
        lines.add(connector + sName + ": " + sType + sDefault);
      }
    }

    // Annotations section
    Object classAnnotations = meta.get("classAnnotations");
    if (classAnnotations instanceof List<?> ca && !ca.isEmpty()) {
      lines.add("Annotations (" + ca.size() + "):");
      int idx = 0;
      int size = ca.size();
      for (Object ann : ca) {
        idx++;
        boolean last = (idx == size);
        String connector = last ? "\u2514\u2500 " : "\u251C\u2500 ";
        lines.add(connector + ann);
      }
    }

    return lines.toArray(new String[0]);
  }

  String[] buildDetailLines(Object value, String tabName) {
    // Stack trace rendering
    if (value instanceof Map<?, ?> m && m.containsKey("frames")) {
      return buildStackTraceLines(m);
    }
    if ("frames".equals(tabName)) {
      return buildStackTraceLines(Map.of("frames", value));
    }

    // Default: tree-structured key-value dump
    List<String> lines = new ArrayList<>();
    Object resolved = resolveForDisplay(value);
    if (resolved instanceof Map<?, ?> m) {
      formatTree(lines, m, "");
    } else if (resolved != null && resolved.getClass().isArray()) {
      formatArrayTree(lines, resolved, "");
    } else if (resolved instanceof Collection<?> coll) {
      formatCollectionTree(lines, coll, "");
    } else {
      lines.add(String.valueOf(value));
    }
    return lines.toArray(new String[0]);
  }

  private String[] buildStackTraceLines(Map<?, ?> stackMap) {
    Object framesObj = stackMap.get("frames");
    if (framesObj instanceof ArrayType at) framesObj = at.getArray();
    int frameCount = TuiTableRenderer.arrayLength(framesObj);
    int count = Math.max(0, frameCount);
    List<String> lines = new ArrayList<>(count + 1);
    lines.add("(" + count + " frames)");
    for (int i = 0; i < count; i++) {
      Object frameObj;
      if (framesObj != null && framesObj.getClass().isArray()) {
        frameObj = Array.get(framesObj, i);
      } else if (framesObj instanceof List<?> list) {
        frameObj = list.get(i);
      } else {
        break;
      }
      frameObj = TuiTableRenderer.resolveComplex(frameObj);
      String sig = TuiTableRenderer.extractFrameString(frameObj);
      if (sig == null) sig = "<unknown>";
      String typeStr = "";
      if (frameObj instanceof Map<?, ?> fm) {
        Object ftype = TuiTableRenderer.unwrap(fm.get("type"));
        if (ftype != null) typeStr = " [" + ftype + "]";
      }
      String connector = (i == count - 1) ? "\u2514\u2500 " : "\u251C\u2500 ";
      lines.add(connector + sig + typeStr);
    }
    Object truncated = stackMap.get("truncated");
    if (truncated != null && Boolean.TRUE.equals(truncated)) {
      lines.add("   ... (truncated)");
    }
    return lines.toArray(new String[0]);
  }

  int getDetailScrollOffset() {
    if (ctx.activeDetailTabIndex < ctx.detailTabScrollOffsets.size()) {
      return ctx.detailTabScrollOffsets.get(ctx.activeDetailTabIndex);
    }
    return 0;
  }

  void setDetailScrollOffset(int offset) {
    while (ctx.detailTabScrollOffsets.size() <= ctx.activeDetailTabIndex) {
      ctx.detailTabScrollOffsets.add(0);
    }
    ctx.detailTabScrollOffsets.set(ctx.activeDetailTabIndex, offset);
  }

  void moveDetailCursor(int delta) {
    if (ctx.detailLineTypeRefs == null || ctx.detailLineTypeRefs.isEmpty()) return;
    int maxLine = ctx.detailLineTypeRefs.size() - 1;
    ctx.detailCursorLine = Math.max(0, Math.min(maxLine, ctx.detailCursorLine + delta));
    int scrollOffset = getDetailScrollOffset();
    if (ctx.detailCursorLine < scrollOffset) {
      setDetailScrollOffset(ctx.detailCursorLine);
    } else if (ctx.detailCursorLine >= scrollOffset + ctx.detailAreaHeight) {
      setDetailScrollOffset(ctx.detailCursorLine - ctx.detailAreaHeight + 1);
    }
  }

  void moveMetadataCursor(ResultTab tab, int delta) {
    if (ctx.metadataBrowserLineRefs == null || ctx.metadataBrowserLineRefs.isEmpty()) return;
    int maxRow = tab.lines.size() - 1;
    tab.selectedRow = Math.max(0, Math.min(maxRow, tab.selectedRow + delta));
    if (tab.selectedRow < tab.scrollOffset) {
      tab.scrollOffset = tab.selectedRow;
    } else if (tab.selectedRow >= tab.scrollOffset + ctx.resultsAreaHeight) {
      tab.scrollOffset = tab.selectedRow - ctx.resultsAreaHeight + 1;
    }
  }

  // ---- object field ref helpers ----

  private static List<String> buildObjectFieldRefs(Map<?, ?> fieldsMap) {
    List<String> refs = new ArrayList<>();
    buildObjectFieldRefsForMap(refs, fieldsMap);
    return refs;
  }

  private static void buildObjectFieldRefsForMap(List<String> refs, Map<?, ?> map) {
    var entries = new ArrayList<>(map.entrySet());
    for (Map.Entry<?, ?> entry : entries) {
      Object val = resolveForDisplay(entry.getValue());
      while (val instanceof Map<?, ?> inner && inner.size() == 1) {
        val = resolveForDisplay(inner.values().iterator().next());
      }
      if (val instanceof Map<?, ?> nested && nested.size() > 1) {
        refs.add(null);
        buildObjectFieldRefsForMap(refs, nested);
      } else if (val instanceof long[]) {
        refs.add(null);
      } else if (val != null && val.getClass().isArray()) {
        refs.add(null);
        int len = Array.getLength(val);
        for (int i = 0; i < len; i++) {
          Object item = resolveForDisplay(Array.get(val, i));
          while (item instanceof Map<?, ?> inner && inner.size() == 1) {
            item = resolveForDisplay(inner.values().iterator().next());
          }
          if (item instanceof Map<?, ?> nested && nested.size() > 1) {
            refs.add(null);
            buildObjectFieldRefsForMap(refs, nested);
          } else {
            refs.add(null);
          }
        }
      } else if (val instanceof Collection<?> coll) {
        refs.add(null);
        for (Object raw : coll) {
          Object item = resolveForDisplay(raw);
          while (item instanceof Map<?, ?> inner && inner.size() == 1) {
            item = resolveForDisplay(inner.values().iterator().next());
          }
          if (item instanceof Map<?, ?> nested && nested.size() > 1) {
            refs.add(null);
            buildObjectFieldRefsForMap(refs, nested);
          } else {
            refs.add(null);
          }
        }
      } else {
        refs.add(extractObjectRef(val));
      }
    }
  }

  private static String extractObjectRef(Object val) {
    if (val == null) return null;
    String s = val.toString();
    int atIdx = s.lastIndexOf('@');
    if (atIdx < 0 || atIdx == s.length() - 1) return null;
    for (int i = atIdx + 1; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
        return null;
      }
    }
    return "object:" + s.substring(atIdx + 1);
  }

  // ---- static tree formatters ----

  static Object resolveForDisplay(Object val) {
    if (val instanceof ComplexType ct) return ct.getValue();
    if (val instanceof ArrayType at) return at.getArray();
    return val;
  }

  static boolean isComplexValue(Object val) {
    if (val == null) return false;
    if (val instanceof ComplexType ct) return isComplexValue(ct.getValue());
    if (val instanceof Map<?, ?> m) {
      if (m.size() <= 1) return false;
      return true;
    }
    if (val instanceof ArrayType) return true;
    if (val instanceof Collection<?>) return true;
    if (val.getClass().isArray()) return true;
    return false;
  }

  static void formatTree(List<String> lines, Map<?, ?> map, String indent) {
    var entries = new ArrayList<>(map.entrySet());
    for (int i = 0; i < entries.size(); i++) {
      Map.Entry<?, ?> entry = entries.get(i);
      boolean last = (i == entries.size() - 1);
      String connector = last ? "\u2514\u2500 " : "\u251C\u2500 ";
      String childIndent = indent + (last ? "   " : "\u2502  ");
      String key = String.valueOf(entry.getKey());
      Object val = resolveForDisplay(entry.getValue());
      while (val instanceof Map<?, ?> inner && inner.size() == 1) {
        val = resolveForDisplay(inner.values().iterator().next());
      }
      if (val instanceof Map<?, ?> nested && nested.size() > 1) {
        lines.add(indent + connector + key);
        formatTree(lines, nested, childIndent);
      } else if (val instanceof long[] la) {
        lines.add(indent + connector + key + ": " + TuiTableRenderer.sparkline(la));
      } else if (val != null && val.getClass().isArray()) {
        int len = Array.getLength(val);
        lines.add(indent + connector + key + " (" + len + " items)");
        formatArrayTree(lines, val, childIndent);
      } else if (val instanceof Collection<?> coll) {
        lines.add(indent + connector + key + " (" + coll.size() + " items)");
        formatCollectionTree(lines, coll, childIndent);
      } else {
        lines.add(indent + connector + key + ": " + (val != null ? val : "(null)"));
      }
    }
  }

  static void formatArrayTree(List<String> lines, Object arr, String indent) {
    int len = Array.getLength(arr);
    for (int i = 0; i < len; i++) {
      boolean last = (i == len - 1);
      String connector = last ? "\u2514\u2500 " : "\u251C\u2500 ";
      String childIndent = indent + (last ? "   " : "\u2502  ");
      Object item = resolveForDisplay(Array.get(arr, i));
      while (item instanceof Map<?, ?> inner && inner.size() == 1) {
        item = resolveForDisplay(inner.values().iterator().next());
      }
      if (item instanceof Map<?, ?> nested && nested.size() > 1) {
        lines.add(indent + connector + "[" + i + "]");
        formatTree(lines, nested, childIndent);
      } else {
        lines.add(indent + connector + "[" + i + "]: " + item);
      }
    }
  }

  static void formatCollectionTree(List<String> lines, Collection<?> coll, String indent) {
    int idx = 0;
    int size = coll.size();
    for (Object raw : coll) {
      boolean last = (idx == size - 1);
      String connector = last ? "\u2514\u2500 " : "\u251C\u2500 ";
      String childIndent = indent + (last ? "   " : "\u2502  ");
      Object item = resolveForDisplay(raw);
      while (item instanceof Map<?, ?> inner && inner.size() == 1) {
        item = resolveForDisplay(inner.values().iterator().next());
      }
      if (item instanceof Map<?, ?> nested && nested.size() > 1) {
        lines.add(indent + connector + "[" + idx + "]");
        formatTree(lines, nested, childIndent);
      } else {
        lines.add(indent + connector + "[" + idx + "]: " + item);
      }
      idx++;
    }
  }
}

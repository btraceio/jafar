package io.jafar.shell.tui;

import io.jafar.shell.cli.TuiTableRenderer;
import io.jafar.shell.core.BrowseCategoryDescriptor;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.TuiAdapter;
import io.jafar.shell.tui.TuiContext.Focus;
import io.jafar.shell.tui.TuiContext.ResultTab;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages browser mode for constant pools, events, and metadata types. Handles entering/exiting
 * browser modes, loading entries, and sidebar navigation.
 */
public final class TuiBrowserController {

  private static final class NavigationEntry {
    final List<Map<String, Object>> tableData;
    final List<Map<String, Object>> cpAllEntries;
    final List<String> cpColumnHeaders;
    final int[] cpColumnWidths;
    final List<String> tableHeaders;
    final List<String> lines;
    final int maxLineWidth;
    final int selectedRow;
    final int scrollOffset;
    final int dataStartLine;
    final int cpRenderedCount;
    final int activeDetailTabIndex;
    final int detailCursorLine;

    NavigationEntry(ResultTab tab, TuiContext ctx) {
      this.tableData = tab.tableData != null ? new ArrayList<>(tab.tableData) : null;
      this.cpAllEntries = tab.cpAllEntries;
      this.cpColumnHeaders = tab.cpColumnHeaders;
      this.cpColumnWidths = tab.cpColumnWidths != null ? tab.cpColumnWidths.clone() : null;
      this.tableHeaders = tab.tableHeaders;
      this.lines = new ArrayList<>(tab.lines);
      this.maxLineWidth = tab.maxLineWidth;
      this.selectedRow = tab.selectedRow;
      this.scrollOffset = tab.scrollOffset;
      this.dataStartLine = tab.dataStartLine;
      this.cpRenderedCount = tab.cpRenderedCount;
      this.activeDetailTabIndex = ctx.activeDetailTabIndex;
      this.detailCursorLine = ctx.detailCursorLine;
    }
  }

  private final List<NavigationEntry> navBackStack = new ArrayList<>();
  private final List<NavigationEntry> navForwardStack = new ArrayList<>();

  private final TuiContext ctx;
  private final SessionManager<? extends Session> sessions;
  private final TuiDetailBuilder detailBuilder;
  private TuiAdapter tuiAdapter;

  TuiBrowserController(
      TuiContext ctx, SessionManager<? extends Session> sessions, TuiDetailBuilder detailBuilder) {
    this.ctx = ctx;
    this.sessions = sessions;
    this.detailBuilder = detailBuilder;
  }

  void setTuiAdapter(TuiAdapter adapter) {
    this.tuiAdapter = adapter;
  }

  // ---- command detection ----

  static boolean isCpSummaryCommand(String command) {
    String[] parts = command.trim().split("\\s+");
    if (parts.length == 0) return false;
    int cpIndex;
    if ("cp".equalsIgnoreCase(parts[0]) || "constants".equalsIgnoreCase(parts[0])) {
      cpIndex = 0;
    } else if ("show".equalsIgnoreCase(parts[0])
        && parts.length >= 2
        && ("cp".equalsIgnoreCase(parts[1]) || "constants".equalsIgnoreCase(parts[1]))) {
      cpIndex = 1;
    } else {
      return false;
    }
    for (int i = cpIndex + 1; i < parts.length; i++) {
      String p = parts[i];
      if ("--summary".equals(p) || "--format".equals(p) || "--range".equals(p)) {
        if (("--format".equals(p) || "--range".equals(p)) && i + 1 < parts.length) i++;
        continue;
      }
      return false;
    }
    return true;
  }

  static boolean isEventsSummaryCommand(String command) {
    String[] parts = command.trim().split("\\s+");
    if (parts.length == 0) return false;
    int evtIndex;
    if ("events".equalsIgnoreCase(parts[0])) {
      evtIndex = 0;
    } else if ("show".equalsIgnoreCase(parts[0])
        && parts.length >= 2
        && "events".equalsIgnoreCase(parts[1])) {
      evtIndex = 1;
    } else {
      return false;
    }
    for (int i = evtIndex + 1; i < parts.length; i++) {
      String p = parts[i];
      if ("--format".equals(p) || "--limit".equals(p) || "--range".equals(p)) {
        if (i + 1 < parts.length) i++;
        continue;
      }
      return false;
    }
    return true;
  }

  static boolean isMetadataSummaryCommand(String command) {
    String[] parts = command.trim().split("\\s+");
    if (parts.length == 0) return false;
    int metaIndex;
    if ("metadata".equalsIgnoreCase(parts[0]) || "types".equalsIgnoreCase(parts[0])) {
      metaIndex = 0;
    } else if ("show".equalsIgnoreCase(parts[0])
        && parts.length >= 2
        && ("metadata".equalsIgnoreCase(parts[1]) || "types".equalsIgnoreCase(parts[1]))) {
      metaIndex = 1;
    } else {
      return false;
    }
    for (int i = metaIndex + 1; i < parts.length; i++) {
      String p = parts[i];
      if (p.startsWith("--")) {
        if ("--search".equals(p) || "--format".equals(p)) {
          if (i + 1 < parts.length) i++;
        }
        continue;
      }
      return false;
    }
    return true;
  }

  // ---- enter/exit browser modes ----

  /**
   * Generic entry point for browser mode. Uses the descriptor to determine rendering and behavior.
   */
  void enterBrowserMode(ResultTab tab, String category) {
    BrowseCategoryDescriptor desc =
        tuiAdapter != null ? tuiAdapter.describeBrowseCategory(category) : null;
    if (desc != null && desc.hasMetadataClasses()) {
      enterMetadataBrowserMode(tab, desc);
      return;
    }

    tab.name = category;
    tab.marqueeTick0 = ctx.renderTick;
    ctx.browserMode = true;
    ctx.activeBrowserDescriptor = desc;
    ctx.browserCategory = category;
    ctx.sidebarTypes = tab.tableData;
    ctx.sidebarSelectedIndex = 0;
    ctx.sidebarScrollOffset = 0;
    ctx.sidebarFocused = true;
    tab.sidebarIndex = 0;
    tab.browserTypes = ctx.sidebarTypes;
    tab.browserDescriptor = desc;
    tab.tableData = null;
    tab.tableHeaders = null;
    tab.selectedRow = -1;
    tab.dataStartLine = -1;
    tab.lines.clear();
    tab.maxLineWidth = 0;
    clearDetailState();
    ctx.focus = Focus.RESULTS;

    String firstName = getSelectedSidebarName();
    if (!firstName.isEmpty()) loadBrowserEntriesViaAdapter(firstName, true);
  }

  void exitBrowserMode() {
    BrowseCategoryDescriptor desc = ctx.activeBrowserDescriptor;
    ctx.browserMode = false;
    ctx.activeBrowserDescriptor = null;
    ctx.browserCategory = null;
    ctx.sidebarSelectedIndex = 0;
    ctx.sidebarScrollOffset = 0;
    ctx.sidebarFocused = false;
    ctx.browserNavPending = null;
    ctx.sidebarSearchQuery = "";
    ctx.sidebarFilteredIndices = null;
    if (desc != null && desc.hasMetadataClasses()) {
      ctx.metadataBrowserLineRefs = null;
      ctx.metadataByName = null;
    }
  }

  void enterMetadataBrowserMode(ResultTab tab, BrowseCategoryDescriptor desc) {
    Session session = sessions.current().map(ref -> ref.session).orElse(null);
    if (session == null || tuiAdapter == null) return;

    Map<String, Map<String, Object>> allMeta;
    try {
      allMeta = tuiAdapter.loadMetadataClasses(session);
    } catch (Exception e) {
      allMeta = null;
    }

    List<Map<String, Object>> typeRows = tab.tableData;
    tab.name = ctx.browserCategory != null ? ctx.browserCategory : "metadata";
    tab.marqueeTick0 = ctx.renderTick;
    ctx.browserMode = true;
    ctx.activeBrowserDescriptor = desc;
    ctx.sidebarTypes = typeRows;
    ctx.sidebarSelectedIndex = 0;
    ctx.sidebarScrollOffset = 0;
    ctx.sidebarFocused = true;
    tab.sidebarIndex = 0;
    tab.browserTypes = typeRows;
    tab.browserDescriptor = desc;
    ctx.metadataByName = allMeta != null ? allMeta : new HashMap<>();
    ctx.metadataBrowserLineRefs = null;
    tab.tableData = null;
    tab.tableHeaders = null;
    tab.selectedRow = -1;
    tab.dataStartLine = -1;
    tab.lines.clear();
    tab.maxLineWidth = 0;
    clearDetailState();
    ctx.focus = Focus.RESULTS;

    String firstName = getSelectedSidebarName();
    if (!firstName.isEmpty()) loadMetadataDetail(firstName, true);
  }

  private void clearDetailState() {
    ctx.detailTabNames = List.of();
    ctx.detailTabValues = List.of();
    ctx.activeDetailTabIndex = 0;
    ctx.detailTabScrollOffsets.clear();
    ctx.detailCursorLine = -1;
    ctx.detailLineTypeRefs = null;
  }

  // ---- loaders ----

  void loadBrowserEntries(String typeName, boolean keepTypesFocused) {
    BrowseCategoryDescriptor desc = ctx.activeBrowserDescriptor;
    if (desc != null && desc.hasMetadataClasses()) {
      loadMetadataDetail(typeName, keepTypesFocused);
    } else {
      loadBrowserEntriesViaAdapter(typeName, keepTypesFocused);
    }
  }

  private void loadBrowserEntriesViaAdapter(String typeName, boolean keepTypesFocused) {
    Session session = sessions.current().map(ref -> ref.session).orElse(null);
    if (session == null || tuiAdapter == null) return;

    List<Map<String, Object>> entries;
    try {
      entries = tuiAdapter.loadBrowseEntries(session, ctx.browserCategory, typeName, -1);
    } catch (Exception e) {
      ResultTab tab = ctx.activeTab();
      tab.lines.clear();
      tab.lines.add("  Error: " + e.getMessage());
      tab.maxLineWidth = tab.lines.get(0).length();
      tab.tableData = null;
      tab.tableHeaders = null;
      tab.selectedRow = -1;
      tab.dataStartLine = -1;
      tab.cpAllEntries = null;
      tab.cpColumnHeaders = null;
      tab.cpColumnWidths = null;
      tab.cpRenderedCount = 0;
      if (!keepTypesFocused) ctx.sidebarFocused = false;
      return;
    }
    if (entries == null) entries = List.of();
    // Reuse CP-style paginated rendering for adapter entries
    loadEntriesIntoTab(entries, keepTypesFocused);
  }

  private void loadEntriesIntoTab(List<Map<String, Object>> entries, boolean keepTypesFocused) {
    ResultTab tab = ctx.activeTab();
    tab.sidebarIndex = ctx.sidebarSelectedIndex;
    tab.lines.clear();
    tab.scrollOffset = 0;
    tab.hScrollOffset = 0;
    tab.maxLineWidth = 0;
    tab.searchQuery = "";
    tab.detailSearchQuery = "";
    tab.filteredIndices = null;
    tab.filteredMaxLineWidth = 0;
    tab.sortColumn = -1;
    tab.sortAscending = true;
    tab.metadataClassCache = null;

    if (entries.isEmpty()) {
      tab.lines.add("  (no entries)");
      tab.maxLineWidth = tab.lines.get(0).length();
      tab.tableData = null;
      tab.tableHeaders = null;
      tab.selectedRow = -1;
      tab.dataStartLine = -1;
      tab.cpAllEntries = null;
      tab.cpColumnHeaders = null;
      tab.cpColumnWidths = null;
      tab.cpRenderedCount = 0;
      clearDetailState();
      if (!keepTypesFocused) ctx.sidebarFocused = false;
      return;
    }

    Set<String> cols = new LinkedHashSet<>();
    int sample = Math.min(entries.size(), 200);
    for (int i = 0; i < sample; i++) cols.addAll(entries.get(i).keySet());
    if (cols.size() <= 2 && entries.size() > sample) {
      int sample2 = Math.min(entries.size(), 1000);
      for (int i = 0; i < sample2; i++) cols.addAll(entries.get(i).keySet());
    }
    List<String> headers = new ArrayList<>(cols);

    int[] widths = TuiTableRenderer.computeMaxWidths(headers, entries);

    tab.cpAllEntries = entries;
    tab.cpColumnHeaders = headers;
    tab.cpColumnWidths = widths;

    StringBuilder sb = new StringBuilder("  ");
    for (int c = 0; c < headers.size(); c++) {
      if (c > 0) sb.append("  ");
      sb.append(String.format("%-" + widths[c] + "s", headers.get(c)));
    }
    String headerLine = sb.toString();
    tab.lines.add(headerLine);
    tab.maxLineWidth = Math.max(tab.maxLineWidth, headerLine.length());

    int pageSize = Math.min(entries.size(), Math.max(ctx.resultsAreaHeight + 5, 20));
    renderCpPage(tab, 0, pageSize);

    tab.tableData = new ArrayList<>(entries.subList(0, pageSize));
    tab.tableHeaders = headers;
    tab.cpRenderedCount = pageSize;
    tab.selectedRow = 0;
    tab.dataStartLine = 1;
    detailBuilder.buildDetailTabs(tab);

    tab.scrollOffset = 0;
    if (!keepTypesFocused) ctx.sidebarFocused = false;
  }

  void loadMetadataDetail(String typeName, boolean keepSidebarFocused) {
    if (ctx.metadataByName == null) return;
    Map<String, Object> meta = ctx.metadataByName.get(typeName);

    ResultTab tab = ctx.activeTab();
    tab.sidebarIndex = ctx.sidebarSelectedIndex;
    tab.lines.clear();
    tab.scrollOffset = 0;
    tab.hScrollOffset = 0;
    tab.maxLineWidth = 0;
    tab.searchQuery = "";
    tab.filteredIndices = null;
    tab.filteredMaxLineWidth = 0;
    tab.tableData = null;
    tab.tableHeaders = null;
    tab.selectedRow = 0;
    tab.dataStartLine = -1;
    clearDetailState();

    if (meta == null) {
      tab.lines.add("  (no metadata for " + typeName + ")");
      tab.maxLineWidth = tab.lines.get(0).length();
      ctx.metadataBrowserLineRefs = null;
      tab.selectedRow = -1;
      if (!keepSidebarFocused) ctx.sidebarFocused = false;
      return;
    }

    Set<String> navigableTypes = new HashSet<>();
    if (ctx.sidebarTypes != null) {
      for (Map<String, Object> row : ctx.sidebarTypes) {
        Object n = row.get("name");
        if (n != null) navigableTypes.add(n.toString());
      }
    }

    List<String> lines = new ArrayList<>();
    List<String> refs = new ArrayList<>();
    buildMetadataBrowserLines(meta, navigableTypes, lines, refs);

    tab.lines.addAll(lines);
    tab.maxLineWidth = 0;
    for (String l : lines) {
      if (l.length() > tab.maxLineWidth) tab.maxLineWidth = l.length();
    }
    ctx.metadataBrowserLineRefs = refs;
    tab.selectedRow = refs.isEmpty() ? -1 : 0;
    if (!keepSidebarFocused) ctx.sidebarFocused = false;
  }

  // ---- rendering helpers ----

  void renderCpPage(ResultTab tab, int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int r = from; r < to; r++) {
      Map<String, Object> row = tab.cpAllEntries.get(r);
      sb.setLength(0);
      sb.append("  ");
      for (int c = 0; c < tab.cpColumnHeaders.size(); c++) {
        if (c > 0) sb.append("  ");
        String cell = TuiTableRenderer.toCell(row.get(tab.cpColumnHeaders.get(c)));
        if (cell.length() > TuiTableRenderer.MAX_CELL_WIDTH) {
          cell = cell.substring(0, TuiTableRenderer.MAX_CELL_WIDTH - 3) + "...";
        }
        sb.append(String.format("%-" + tab.cpColumnWidths[c] + "s", cell));
      }
      String line = sb.toString();
      tab.lines.add(line);
      tab.maxLineWidth = Math.max(tab.maxLineWidth, line.length());
    }
  }

  void ensureCpEntriesLoaded(ResultTab tab, int upToRow) {
    if (tab.cpAllEntries == null || tab.cpRenderedCount >= tab.cpAllEntries.size()) return;
    if (upToRow < tab.cpRenderedCount) return;
    int pageSize = Math.max(ctx.resultsAreaHeight + 5, 20);
    int nextEnd = Math.min(tab.cpAllEntries.size(), tab.cpRenderedCount + pageSize);
    renderCpPage(tab, tab.cpRenderedCount, nextEnd);
    for (int i = tab.cpRenderedCount; i < nextEnd; i++) {
      tab.tableData.add(tab.cpAllEntries.get(i));
    }
    tab.cpRenderedCount = nextEnd;
  }

  // ---- sidebar navigation ----

  String getSelectedSidebarName() {
    return ctx.getSelectedSidebarName();
  }

  int findPrevSidebarItem(int currentIndex) {
    if (ctx.sidebarFilteredIndices == null) {
      return currentIndex > 0 ? currentIndex - 1 : -1;
    }
    int prev = -1;
    for (int idx : ctx.sidebarFilteredIndices) {
      if (idx >= currentIndex) break;
      prev = idx;
    }
    return prev;
  }

  int findNextSidebarItem(int currentIndex) {
    if (ctx.sidebarFilteredIndices == null) {
      return (ctx.sidebarTypes != null && currentIndex < ctx.sidebarTypes.size() - 1)
          ? currentIndex + 1
          : -1;
    }
    for (int idx : ctx.sidebarFilteredIndices) {
      if (idx > currentIndex) return idx;
    }
    return -1;
  }

  int sidebarVisibleIndex(int rawIndex) {
    if (ctx.sidebarFilteredIndices == null) return rawIndex;
    return ctx.sidebarFilteredIndices.indexOf(rawIndex);
  }

  void dispatchSidebarArrow(int direction) {
    switch (direction) {
      case 'A': // Up
        {
          int prev = findPrevSidebarItem(ctx.sidebarSelectedIndex);
          if (prev >= 0) {
            ctx.sidebarSelectedIndex = prev;
            int visIdx = sidebarVisibleIndex(prev);
            if (visIdx >= 0 && visIdx < ctx.sidebarScrollOffset) {
              ctx.sidebarScrollOffset = visIdx;
            }
            String upName = getSelectedSidebarName();
            if (!upName.isEmpty()) {
              ctx.browserNavPending = upName;
              ctx.browserNavKeepFocus = true;
              ctx.browserNavTime = System.nanoTime();
            }
          }
        }
        break;
      case 'B': // Down
        {
          int next = findNextSidebarItem(ctx.sidebarSelectedIndex);
          if (next >= 0) {
            ctx.sidebarSelectedIndex = next;
            int visIdx = sidebarVisibleIndex(next);
            if (visIdx >= 0 && visIdx >= ctx.sidebarScrollOffset + ctx.sidebarAreaHeight) {
              ctx.sidebarScrollOffset = visIdx - ctx.sidebarAreaHeight + 1;
            }
            String downName = getSelectedSidebarName();
            if (!downName.isEmpty()) {
              ctx.browserNavPending = downName;
              ctx.browserNavKeepFocus = true;
              ctx.browserNavTime = System.nanoTime();
            }
          }
        }
        break;
      case 'C': // Right — load entries and move focus
        {
          ctx.browserNavPending = null;
          String name = getSelectedSidebarName();
          if (!name.isEmpty()) loadBrowserEntries(name, false);
        }
        break;
      default:
        break;
    }
  }

  void navigateToType(String typeName) {
    if (typeName.startsWith("object:")) {
      navigateToObjectId(typeName.substring("object:".length()));
      return;
    }
    ResultTab tab = ctx.activeTab();
    if (tab.tableData == null) return;
    if (tab.filteredIndices != null) {
      tab.searchQuery = "";
      tab.filteredIndices = null;
      tab.filteredMaxLineWidth = 0;
    }
    tab.detailSearchQuery = "";
    for (int i = 0; i < tab.tableData.size(); i++) {
      Object name = tab.tableData.get(i).get("name");
      if (name != null && typeName.equals(name.toString())) {
        tab.selectedRow = i;
        if (i < tab.scrollOffset) {
          tab.scrollOffset = i;
        } else if (i >= tab.scrollOffset + ctx.resultsAreaHeight) {
          tab.scrollOffset = i - ctx.resultsAreaHeight + 1;
        }
        detailBuilder.buildDetailTabs(tab);
        return;
      }
    }
  }

  private void navigateToObjectId(String hexId) {
    ResultTab tab = ctx.activeTab();
    long targetId;
    try {
      targetId = Long.parseUnsignedLong(hexId, 16);
    } catch (NumberFormatException e) {
      ctx.hintMessage = "Invalid object id: " + hexId;
      ctx.hintMessageTick = ctx.renderTick;
      return;
    }

    // Scan current tableData first — compare as long, not hex string
    if (tab.tableData != null) {
      for (int i = 0; i < tab.tableData.size(); i++) {
        Object idObj = tab.tableData.get(i).get("id");
        if (idObj instanceof Number n && n.longValue() == targetId) {
          tab.selectedRow = i;
          if (i < tab.scrollOffset) tab.scrollOffset = i;
          else if (i >= tab.scrollOffset + ctx.resultsAreaHeight)
            tab.scrollOffset = i - ctx.resultsAreaHeight + 1;
          detailBuilder.buildDetailTabs(tab);
          return;
        }
      }
    }

    // Not found in current data: load via adapter
    Session session = sessions.current().map(ref -> ref.session).orElse(null);
    if (session == null || tuiAdapter == null) {
      ctx.hintMessage = "No active session";
      ctx.hintMessageTick = ctx.renderTick;
      return;
    }
    Map<String, Object> row;
    try {
      row = tuiAdapter.loadObjectById(session, hexId);
    } catch (Exception e) {
      ctx.hintMessage = "Error: " + e.getMessage();
      ctx.hintMessageTick = ctx.renderTick;
      return;
    }
    if (row == null) {
      ctx.hintMessage = "Object not found: @" + hexId;
      ctx.hintMessageTick = ctx.renderTick;
      return;
    }
    if (tab.pinned) {
      // Navigate from a pinned tab: open in the intermediary tab without adding to history
      int unpinnedIdx = findOrCreateUnpinnedTabIndex();
      ctx.activeTabIndex = unpinnedIdx;
      clearNavHistory();
    } else {
      pushNavEntry(tab);
    }
    loadEntriesIntoTab(List.of(row), false);
  }

  boolean canNavigateBack() {
    return !navBackStack.isEmpty();
  }

  boolean canNavigateForward() {
    return !navForwardStack.isEmpty();
  }

  void navigateBack() {
    if (!canNavigateBack()) return;
    NavigationEntry prev = navBackStack.remove(navBackStack.size() - 1);
    navForwardStack.add(new NavigationEntry(ctx.activeTab(), ctx));
    restoreNavEntry(prev);
  }

  void navigateForward() {
    if (!canNavigateForward()) return;
    NavigationEntry next = navForwardStack.remove(navForwardStack.size() - 1);
    navBackStack.add(new NavigationEntry(ctx.activeTab(), ctx));
    restoreNavEntry(next);
  }

  void clearNavHistory() {
    navBackStack.clear();
    navForwardStack.clear();
  }

  private void pushNavEntry(ResultTab tab) {
    navForwardStack.clear();
    navBackStack.add(new NavigationEntry(tab, ctx));
    if (navBackStack.size() > 20) {
      navBackStack.remove(0);
    }
  }

  private int findOrCreateUnpinnedTabIndex() {
    for (int i = ctx.tabs.size() - 1; i >= 0; i--) {
      if (!ctx.tabs.get(i).pinned) return i;
    }
    ctx.tabs.add(new TuiContext.ResultTab(""));
    return ctx.tabs.size() - 1;
  }

  private void restoreNavEntry(NavigationEntry e) {
    ResultTab tab = ctx.activeTab();
    tab.tableData = e.tableData;
    tab.cpAllEntries = e.cpAllEntries;
    tab.cpColumnHeaders = e.cpColumnHeaders;
    tab.cpColumnWidths = e.cpColumnWidths != null ? e.cpColumnWidths.clone() : null;
    tab.tableHeaders = e.tableHeaders;
    tab.lines.clear();
    tab.lines.addAll(e.lines);
    tab.maxLineWidth = e.maxLineWidth;
    tab.selectedRow = e.selectedRow;
    tab.scrollOffset = e.scrollOffset;
    tab.dataStartLine = e.dataStartLine;
    tab.cpRenderedCount = e.cpRenderedCount;
    ctx.activeDetailTabIndex = e.activeDetailTabIndex;
    ctx.detailCursorLine = e.detailCursorLine;
    detailBuilder.buildDetailTabs(tab);
  }

  void navigateToSidebarType(String typeName) {
    if (ctx.sidebarTypes == null) return;
    if (ctx.sidebarFilteredIndices != null) {
      ctx.sidebarSearchQuery = "";
      ctx.sidebarFilteredIndices = null;
    }
    for (int i = 0; i < ctx.sidebarTypes.size(); i++) {
      Object name = ctx.sidebarTypes.get(i).get("name");
      if (name != null && typeName.equals(name.toString())) {
        ctx.sidebarSelectedIndex = i;
        int visIdx = sidebarVisibleIndex(i);
        if (visIdx >= 0 && visIdx < ctx.sidebarScrollOffset) {
          ctx.sidebarScrollOffset = visIdx;
        } else if (visIdx >= 0 && visIdx >= ctx.sidebarScrollOffset + ctx.sidebarAreaHeight) {
          ctx.sidebarScrollOffset = visIdx - ctx.sidebarAreaHeight + 1;
        }
        loadBrowserEntries(typeName, true);
        return;
      }
    }
  }

  // ---- metadata browser lines ----

  @SuppressWarnings("unchecked")
  private void buildMetadataBrowserLines(
      Map<String, Object> meta, Set<String> navigableTypes, List<String> lines, List<String> refs) {
    Object fieldsByName = meta.get("fieldsByName");
    if (fieldsByName instanceof Map<?, ?> fbm && !fbm.isEmpty()) {
      lines.add("Fields (" + fbm.size() + "):");
      refs.add(null);
      int idx = 0;
      int size = fbm.size();
      for (Map.Entry<?, ?> entry : fbm.entrySet()) {
        idx++;
        boolean last = (idx == size);
        String connector = last ? "\u2514\u2500 " : "\u251C\u2500 ";
        String fName = entry.getKey().toString();
        String fType = "";
        if (entry.getValue() instanceof Map<?, ?> fm) {
          Object typeObj = fm.get("type");
          if (typeObj != null) fType = typeObj.toString();
          Object annObj = fm.get("annotations");
          String annStr = "";
          if (annObj instanceof List<?> annList && !annList.isEmpty()) {
            annStr = " " + annList;
          }
          String nav = navigableTypes.contains(fType) ? " \u2192" : "";
          lines.add(connector + fName + ": " + fType + annStr + nav);
          refs.add(navigableTypes.contains(fType) ? fType : null);
        } else {
          lines.add(connector + fName);
          refs.add(null);
        }
      }
    }

    Object settingsByName = meta.get("settingsByName");
    if (settingsByName instanceof Map<?, ?> sbm && !sbm.isEmpty()) {
      lines.add("Settings (" + sbm.size() + "):");
      refs.add(null);
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
        refs.add(null);
      }
    }

    Object classAnnotations = meta.get("classAnnotations");
    if (classAnnotations instanceof List<?> ca && !ca.isEmpty()) {
      lines.add("Annotations (" + ca.size() + "):");
      refs.add(null);
      for (Object ann : ca) {
        lines.add("  " + ann);
        refs.add(null);
      }
    }
  }
}

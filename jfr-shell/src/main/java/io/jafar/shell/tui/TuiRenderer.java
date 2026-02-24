package io.jafar.shell.tui;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.scrollbar.Scrollbar;
import dev.tamboui.widgets.scrollbar.ScrollbarState;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.cli.TuiTableRenderer;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.tui.TuiContext.Focus;
import io.jafar.shell.tui.TuiContext.ResultTab;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jline.reader.Candidate;

/**
 * Handles all rendering for the TUI shell. Reads shared state from {@link TuiContext} and writes
 * back viewport dimensions (resultsAreaHeight, detailAreaHeight, sidebarAreaHeight, inputAreaRect).
 */
public final class TuiRenderer {
  private static final Style HIGHLIGHT_STYLE = Style.create().bg(Color.YELLOW).fg(Color.BLACK);

  private final TuiContext ctx;
  private final SessionManager sessions;
  private final TuiDetailBuilder detailBuilder;

  TuiRenderer(TuiContext ctx, SessionManager sessions, TuiDetailBuilder detailBuilder) {
    this.ctx = ctx;
    this.sessions = sessions;
    this.detailBuilder = detailBuilder;
  }

  void render(Frame frame) {
    ctx.renderTick++;
    List<Constraint> constraints = new ArrayList<>();
    constraints.add(Constraint.length(1)); // status bar
    constraints.add(Constraint.fill()); // results
    if (ctx.focus == Focus.SEARCH) constraints.add(Constraint.length(1));
    if (ctx.focus == Focus.HISTORY_SEARCH) constraints.add(Constraint.length(1));
    constraints.add(Constraint.length(3)); // input
    constraints.add(Constraint.length(1)); // tips
    constraints.add(Constraint.length(1)); // hints

    List<Rect> areas =
        Layout.vertical().constraints(constraints.toArray(new Constraint[0])).split(frame.area());

    int idx = 0;
    renderStatusBar(frame, areas.get(idx++));

    Rect resultsRect = areas.get(idx++);
    ResultTab activeTab = ctx.activeTab();
    if (!ctx.detailTabNames.isEmpty()
        && activeTab.tableData != null
        && !activeTab.tableData.isEmpty()) {
      List<Rect> splitAreas =
          Layout.vertical()
              .constraints(Constraint.percentage(60), Constraint.percentage(40))
              .split(resultsRect);
      renderResults(frame, splitAreas.get(0));
      renderDetailSection(frame, splitAreas.get(1));
    } else {
      renderResults(frame, resultsRect);
    }

    if (ctx.focus == Focus.SEARCH) renderSearchBar(frame, areas.get(idx++));
    if (ctx.focus == Focus.HISTORY_SEARCH) renderHistorySearchBar(frame, areas.get(idx++));
    Rect inputRect = areas.get(idx++);
    ctx.inputAreaRect = inputRect;
    renderInput(frame, inputRect);
    renderTipLine(frame, areas.get(idx++));
    renderHints(frame, areas.get(idx));

    // Overlays
    if (ctx.completionPopupVisible
        && ctx.completionFiltered != null
        && !ctx.completionFiltered.isEmpty()) {
      renderCompletionPopup(frame);
    }
    if (ctx.cellPickerVisible
        && ctx.cellPickerEntries != null
        && !ctx.cellPickerEntries.isEmpty()) {
      renderCellPicker(frame);
    }
    if (ctx.sessionPickerVisible
        && ctx.sessionPickerEntries != null
        && !ctx.sessionPickerEntries.isEmpty()) {
      renderSessionPicker(frame);
    }
    if (ctx.exportPopupVisible) {
      renderExportPopup(frame);
    }
  }

  // ---- status bar ----

  private void renderStatusBar(Frame frame, Rect area) {
    String sessionInfo =
        sessions
            .current()
            .map(
                ref -> {
                  String name =
                      ref.alias != null
                          ? ref.alias
                          : ref.session.getRecordingPath().getFileName().toString();
                  return " | session: " + name;
                })
            .orElse(" | no session");

    String backendName = "";
    try {
      backendName = " | [" + BackendRegistry.getInstance().getCurrent().getId() + "]";
    } catch (Exception ignore) {
    }

    String altMod = (TuiContext.PLATFORM == TuiContext.Platform.MACOS) ? "Opt" : "Alt";
    String sessionHint = "";
    if (sessions.list().size() > 1) {
      sessionHint = " | " + altMod + "+s:switch";
    }

    String status = " JFR Shell TUI" + sessionInfo + backendName + sessionHint;
    if (status.length() < area.width()) {
      status = status + " ".repeat(area.width() - status.length());
    }

    Paragraph statusBar =
        Paragraph.builder()
            .text(Text.raw(status))
            .style(Style.create().bg(Color.BLUE).fg(Color.WHITE).bold())
            .build();
    frame.renderWidget(statusBar, area);
  }

  // ---- results ----

  private void renderResults(Frame frame, Rect area) {
    ResultTab activeTab = ctx.activeTab();

    String title = "Results";
    if (activeTab.tableData != null) {
      if (activeTab.filteredIndices != null) {
        int filteredPos = 0;
        if (activeTab.dataStartLine >= 0) {
          int selLineIdx = activeTab.dataStartLine + activeTab.selectedRow;
          int fidx = activeTab.filteredIndices.indexOf(selLineIdx);
          filteredPos = fidx >= 0 ? fidx + 1 : 0;
        }
        title +=
            " ("
                + filteredPos
                + "/"
                + activeTab.filteredIndices.size()
                + " of "
                + activeTab.tableData.size()
                + " match \""
                + activeTab.searchQuery
                + "\")";
      } else {
        title += " (" + (activeTab.selectedRow + 1) + "/" + activeTab.tableData.size() + ")";
      }
    } else if (!activeTab.lines.isEmpty()) {
      int lineCount0 = TuiContext.effectiveLineCount(activeTab);
      if (lineCount0 > 0) {
        int scrollOff = activeTab.scrollOffset;
        int endLine = Math.min(scrollOff + ctx.resultsAreaHeight, lineCount0);
        if (activeTab.filteredIndices != null) {
          title +=
              " ("
                  + activeTab.filteredIndices.size()
                  + "/"
                  + activeTab.lines.size()
                  + " match \""
                  + activeTab.searchQuery
                  + "\")";
        } else {
          title += " (line " + (scrollOff + 1) + "-" + endLine + "/" + lineCount0 + ")";
        }
      }
    }
    if (!ctx.browserMode
        && ctx.focus == Focus.SEARCH
        && ctx.searchOriginFocus == Focus.RESULTS
        && !ctx.searchOriginSidebar) {
      String q = ctx.searchInputState.text();
      if (!q.isEmpty()) {
        title += " /" + q;
      }
    } else if (!ctx.browserMode && !activeTab.searchQuery.isEmpty() && ctx.focus != Focus.SEARCH) {
      title += " /" + activeTab.searchQuery;
    }
    Title blockTitle = mnemonicTitle(title, 0);

    Block.Builder blockBuilder =
        Block.builder().title(blockTitle).borders(Borders.ALL).borderType(BorderType.ROUNDED);
    if (ctx.focus == Focus.RESULTS && !ctx.browserMode) {
      blockBuilder.borderColor(Color.CYAN);
    }
    if (!ctx.browserMode
        && ctx.focus == Focus.SEARCH
        && ctx.searchOriginFocus == Focus.RESULTS
        && !ctx.searchOriginSidebar) {
      blockBuilder.borderColor(Color.YELLOW);
    }
    if (!ctx.browserMode && activeTab.filteredIndices != null && !activeTab.searchQuery.isEmpty()) {
      blockBuilder.titleBottom(
          Title.from(
              Span.styled(
                  " filter: " + activeTab.searchQuery + " ",
                  Style.create().fg(Color.YELLOW).italic())));
    }
    Block block = blockBuilder.build();

    Rect inner = block.inner(area);
    frame.renderWidget(block, area);

    if (showTabBar()) {
      List<Rect> tabSplit =
          Layout.vertical().constraints(Constraint.length(1), Constraint.fill()).split(inner);
      renderResultTabBar(frame, tabSplit.get(0));
      inner = tabSplit.get(1);
    }

    // Browser mode: split for sidebar
    if (ctx.browserMode) {
      List<Rect> hSplit =
          Layout.horizontal()
              .constraints(Constraint.percentage(30), Constraint.percentage(70))
              .split(inner);
      renderSidebar(frame, hSplit.get(0));

      String entriesTitle = ctx.getSelectedSidebarName();
      boolean entriesSearchActive =
          ctx.focus == Focus.SEARCH
              && ctx.searchOriginFocus == Focus.RESULTS
              && !ctx.searchOriginSidebar;
      if (entriesSearchActive) {
        String q = ctx.searchInputState.text();
        if (!q.isEmpty()) entriesTitle += " /" + q;
      } else if (!activeTab.searchQuery.isEmpty()) {
        entriesTitle += " /" + activeTab.searchQuery;
      }
      Block.Builder cpEntriesBuilder =
          Block.builder()
              .title(Title.from(entriesTitle))
              .borders(Borders.ALL)
              .borderType(BorderType.ROUNDED);
      if (ctx.focus == Focus.RESULTS && !ctx.sidebarFocused) {
        cpEntriesBuilder.borderColor(Color.CYAN);
      }
      if (entriesSearchActive) {
        cpEntriesBuilder.borderColor(Color.YELLOW);
      }
      Block cpEntriesBlock = cpEntriesBuilder.build();
      inner = cpEntriesBlock.inner(hSplit.get(1));
      frame.renderWidget(cpEntriesBlock, hSplit.get(1));
    }

    // Spinner while command running
    if (ctx.commandRunning && ctx.renderTick - ctx.commandStartTick > 1) {
      int spinIdx = (int) (ctx.renderTick % TuiContext.SPINNER.length);
      Paragraph spinner = Paragraph.from("  " + TuiContext.SPINNER[spinIdx] + " Running...");
      frame.renderWidget(spinner, inner);
      ctx.resultsAreaHeight = inner.height();
      return;
    }

    int lineCount = TuiContext.effectiveLineCount(activeTab);
    int maxWidth = TuiContext.effectiveMaxLineWidth(activeTab);

    if (lineCount == 0) {
      ctx.resultsAreaHeight = inner.height();
      if (activeTab.lines.isEmpty()) {
        Paragraph empty =
            Paragraph.from("Type a command and press Enter. Use 'help' for available commands.");
        frame.renderWidget(empty, inner);
      } else {
        Paragraph noMatch = Paragraph.from("No matching lines.");
        frame.renderWidget(noMatch, inner);
      }
      return;
    }

    // Scrollbars
    boolean needsVScroll = lineCount > inner.height();
    boolean needsHScroll = maxWidth > inner.width();

    Rect contentArea = inner;
    Rect vScrollbarArea = null;
    Rect hScrollbarArea = null;

    if (needsVScroll && needsHScroll) {
      List<Rect> hSplit =
          Layout.horizontal().constraints(Constraint.fill(), Constraint.length(1)).split(inner);
      List<Rect> vSplit =
          Layout.vertical()
              .constraints(Constraint.fill(), Constraint.length(1))
              .split(hSplit.get(0));
      contentArea = vSplit.get(0);
      hScrollbarArea = vSplit.get(1);
      vScrollbarArea = hSplit.get(1);
    } else if (needsVScroll) {
      List<Rect> hSplit =
          Layout.horizontal().constraints(Constraint.fill(), Constraint.length(1)).split(inner);
      contentArea = hSplit.get(0);
      vScrollbarArea = hSplit.get(1);
    } else if (needsHScroll) {
      List<Rect> vSplit =
          Layout.vertical().constraints(Constraint.fill(), Constraint.length(1)).split(inner);
      contentArea = vSplit.get(0);
      hScrollbarArea = vSplit.get(1);
    }

    int visibleWidth = contentArea.width();
    int maxHScroll = Math.max(0, maxWidth - visibleWidth);
    activeTab.hScrollOffset = Math.min(activeTab.hScrollOffset, maxHScroll);

    // Sticky header
    boolean hasTable =
        activeTab.tableData != null
            && activeTab.dataStartLine >= 1
            && activeTab.filteredIndices == null;
    int headerLine = hasTable ? activeTab.dataStartLine - 1 : -1;
    Rect headerArea = null;
    if (hasTable && headerLine >= 0 && headerLine < lineCount) {
      List<Rect> headerSplit =
          Layout.vertical().constraints(Constraint.length(1), Constraint.fill()).split(contentArea);
      headerArea = headerSplit.get(0);
      contentArea = headerSplit.get(1);
    }

    int visibleHeight = contentArea.height();
    if (visibleHeight <= 0) {
      ctx.resultsAreaHeight = 1;
      return;
    }
    ctx.resultsAreaHeight = visibleHeight;

    // Render sticky header
    if (headerArea != null) {
      Style headerStyle = Style.create().bold().fg(Color.CYAN);
      String headerText = TuiContext.effectiveLine(activeTab, headerLine);
      if (activeTab.sortColumn >= 0 && activeTab.tableHeaders != null) {
        headerText = injectSortIndicator(headerText, activeTab);
      }
      String visibleHeader = applyHScroll(headerText, activeTab.hScrollOffset, visibleWidth);
      Paragraph headerPara =
          Paragraph.builder()
              .text(new Text(List.of(Line.from(Span.styled(visibleHeader, headerStyle))), null))
              .build();
      frame.renderWidget(headerPara, headerArea);
    }

    // Scrollable area
    int scrollLineCount;
    int scrollBase;
    int highlightLine;
    int selectedLineIdx =
        activeTab.tableData != null && activeTab.selectedRow >= 0 && activeTab.dataStartLine >= 0
            ? activeTab.dataStartLine + activeTab.selectedRow
            : -1;
    if (hasTable) {
      scrollBase = activeTab.dataStartLine;
      scrollLineCount = Math.max(0, lineCount - scrollBase);
      highlightLine = activeTab.selectedRow >= 0 ? activeTab.selectedRow : -1;
    } else if (ctx.metadataBrowserMode && !ctx.sidebarFocused) {
      scrollBase = 0;
      scrollLineCount = lineCount;
      highlightLine = activeTab.selectedRow >= 0 ? activeTab.selectedRow : -1;
    } else {
      scrollBase = 0;
      scrollLineCount = lineCount;
      highlightLine = -1;
    }

    int maxVScroll = Math.max(0, scrollLineCount - visibleHeight);
    activeTab.scrollOffset = Math.min(activeTab.scrollOffset, maxVScroll);

    int start = activeTab.scrollOffset;
    int end = Math.min(start + visibleHeight, scrollLineCount);

    Style highlightStyle = Style.create().reversed();
    Style navigableStyle = Style.create().fg(Color.CYAN).bold();
    List<Line> styledLines = new ArrayList<>(end - start);
    for (int i = start; i < end; i++) {
      String line = TuiContext.effectiveLine(activeTab, scrollBase + i);
      String visible = applyHScroll(line, activeTab.hScrollOffset, visibleWidth);
      boolean isHighlighted = (i == highlightLine);
      if (!isHighlighted && activeTab.filteredIndices != null && selectedLineIdx >= 0) {
        int actualLineIdx = activeTab.filteredIndices.get(i);
        isHighlighted = (actualLineIdx == selectedLineIdx);
      }
      if (isHighlighted) {
        if (visible.length() < visibleWidth) {
          visible = visible + " ".repeat(visibleWidth - visible.length());
        }
        styledLines.add(Line.from(Span.styled(visible, highlightStyle)));
      } else if (ctx.metadataBrowserMode
          && ctx.metadataBrowserLineRefs != null
          && i < ctx.metadataBrowserLineRefs.size()
          && ctx.metadataBrowserLineRefs.get(i) != null) {
        styledLines.add(Line.from(Span.styled(visible, navigableStyle)));
      } else if (!activeTab.searchQuery.isEmpty()) {
        styledLines.add(buildHighlightedLine(visible, activeTab.searchQuery, null));
      } else {
        styledLines.add(TuiTableRenderer.colorizeLine(visible, line));
      }
    }
    Paragraph results = Paragraph.builder().text(new Text(styledLines, null)).build();
    frame.renderWidget(results, contentArea);

    if (vScrollbarArea != null) {
      ScrollbarState vState =
          new ScrollbarState(scrollLineCount)
              .viewportContentLength(visibleHeight)
              .position(activeTab.scrollOffset);
      frame.renderStatefulWidget(Scrollbar.vertical(), vScrollbarArea, vState);
    }
    if (hScrollbarArea != null) {
      ScrollbarState hState =
          new ScrollbarState(maxWidth)
              .viewportContentLength(visibleWidth)
              .position(activeTab.hScrollOffset);
      frame.renderStatefulWidget(Scrollbar.horizontal(), hScrollbarArea, hState);
    }
  }

  // ---- sidebar ----

  private void renderSidebar(Frame frame, Rect area) {
    String sidebarTitle =
        ctx.metadataBrowserMode
            ? "Metadata Types"
            : ctx.eventBrowserMode ? "Event Types" : "Constant Types";
    if (ctx.focus == Focus.SEARCH && ctx.searchOriginSidebar) {
      String q = ctx.searchInputState.text();
      if (!q.isEmpty()) sidebarTitle += " /" + q;
    } else if (!ctx.sidebarSearchQuery.isEmpty()) {
      sidebarTitle += " /" + ctx.sidebarSearchQuery;
    }
    Block.Builder blockBuilder =
        Block.builder()
            .title(Title.from(sidebarTitle))
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED);
    if (ctx.focus == Focus.RESULTS && ctx.sidebarFocused) {
      blockBuilder.borderColor(Color.CYAN);
    }
    if (ctx.focus == Focus.SEARCH && ctx.searchOriginSidebar) {
      blockBuilder.borderColor(Color.YELLOW);
    }
    Block block = blockBuilder.build();
    Rect inner = block.inner(area);
    frame.renderWidget(block, area);

    if (ctx.sidebarTypes == null || ctx.sidebarTypes.isEmpty()) return;

    int totalVisible =
        ctx.sidebarFilteredIndices != null
            ? ctx.sidebarFilteredIndices.size()
            : ctx.sidebarTypes.size();

    boolean needsVScroll = totalVisible > inner.height();
    Rect contentArea = inner;
    Rect vScrollbarArea = null;
    if (needsVScroll) {
      List<Rect> scrollSplit =
          Layout.horizontal().constraints(Constraint.fill(), Constraint.length(1)).split(inner);
      contentArea = scrollSplit.get(0);
      vScrollbarArea = scrollSplit.get(1);
    }

    int visibleHeight = contentArea.height();
    ctx.sidebarAreaHeight = Math.max(1, visibleHeight);

    int maxScroll = Math.max(0, totalVisible - visibleHeight);
    ctx.sidebarScrollOffset = Math.min(ctx.sidebarScrollOffset, maxScroll);

    int start = ctx.sidebarScrollOffset;
    int end = Math.min(start + visibleHeight, totalVisible);

    Style highlightStyle = Style.create().reversed();
    List<Line> lines = new ArrayList<>(end - start);
    for (int vi = start; vi < end; vi++) {
      int i = ctx.sidebarFilteredIndices != null ? ctx.sidebarFilteredIndices.get(vi) : vi;
      Map<String, Object> typeRow = ctx.sidebarTypes.get(i);
      String name = String.valueOf(typeRow.getOrDefault("name", ""));
      String display;
      if (ctx.metadataBrowserMode) {
        String event = String.valueOf(typeRow.getOrDefault("event", ""));
        display = " " + name + (event.isEmpty() ? "" : " *");
      } else {
        String countKey = ctx.eventBrowserMode ? "count" : "totalSize";
        String count = String.valueOf(typeRow.getOrDefault(countKey, ""));
        display = " " + name + " (" + count + ")";
      }
      if (display.length() > contentArea.width()) {
        display = display.substring(0, contentArea.width());
      }
      if (i == ctx.sidebarSelectedIndex) {
        if (display.length() < contentArea.width()) {
          display = display + " ".repeat(contentArea.width() - display.length());
        }
        lines.add(Line.from(Span.styled(display, highlightStyle)));
      } else if (!ctx.sidebarSearchQuery.isEmpty()) {
        lines.add(buildHighlightedLine(display, ctx.sidebarSearchQuery, null));
      } else {
        lines.add(Line.from(display));
      }
    }
    Paragraph para = Paragraph.builder().text(new Text(lines, null)).build();
    frame.renderWidget(para, contentArea);

    if (vScrollbarArea != null) {
      ScrollbarState vState =
          new ScrollbarState(totalVisible)
              .viewportContentLength(visibleHeight)
              .position(ctx.sidebarScrollOffset);
      frame.renderStatefulWidget(Scrollbar.vertical(), vScrollbarArea, vState);
    }
  }

  // ---- detail section ----

  private void renderDetailSection(Frame frame, Rect area) {
    if (ctx.detailTabNames.isEmpty()) return;
    renderDetailContent(frame, area);
  }

  private void renderDetailTabBar(Frame frame, Rect area) {
    String[] titles = new String[ctx.detailTabNames.size()];
    for (int i = 0; i < ctx.detailTabNames.size(); i++) {
      titles[i] = marqueeTitle(ctx.detailTabNames.get(i), ctx.renderTick - ctx.detailMarqueeTick0);
    }
    Tabs detailTabs =
        Tabs.builder()
            .titles(titles)
            .highlightStyle(Style.create().bold().fg(Color.YELLOW))
            .divider(" | ")
            .style(
                Style.create()
                    .bg(ctx.focus == Focus.DETAIL ? Color.DARK_GRAY : Color.BLACK)
                    .fg(Color.WHITE))
            .build();
    TabsState detailTabsState = new TabsState(ctx.activeDetailTabIndex);
    frame.renderStatefulWidget(detailTabs, area, detailTabsState);
  }

  private void renderDetailContent(Frame frame, Rect area) {
    if (ctx.activeDetailTabIndex >= ctx.detailTabValues.size()) return;

    ResultTab activeTab = ctx.activeTab();
    String detailTitle = "Details";
    if (ctx.focus == Focus.SEARCH && ctx.searchOriginFocus == Focus.DETAIL) {
      String q = ctx.searchInputState.text();
      if (!q.isEmpty()) {
        detailTitle += " /" + q;
      }
    } else if (activeTab.detailSearchQuery != null
        && !activeTab.detailSearchQuery.isEmpty()
        && ctx.focus != Focus.SEARCH) {
      detailTitle += " /" + activeTab.detailSearchQuery;
    }
    Block.Builder blockBuilder =
        Block.builder()
            .title(mnemonicTitle(detailTitle, 0))
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED);
    if (ctx.focus == Focus.DETAIL) {
      blockBuilder.borderColor(Color.CYAN);
    }
    if (ctx.focus == Focus.SEARCH && ctx.searchOriginFocus == Focus.DETAIL) {
      blockBuilder.borderColor(Color.YELLOW);
    }
    if (activeTab.detailSearchQuery != null && !activeTab.detailSearchQuery.isEmpty()) {
      blockBuilder.titleBottom(
          Title.from(
              Span.styled(
                  " filter: " + activeTab.detailSearchQuery + " ",
                  Style.create().fg(Color.YELLOW).italic())));
    }
    Block block = blockBuilder.build();
    Rect inner = block.inner(area);
    frame.renderWidget(block, area);

    if (!ctx.detailTabNames.isEmpty()) {
      List<Rect> tabSplit =
          Layout.vertical().constraints(Constraint.length(1), Constraint.fill()).split(inner);
      renderDetailTabBar(frame, tabSplit.get(0));
      inner = tabSplit.get(1);
    }

    Object tabValue = ctx.detailTabValues.get(ctx.activeDetailTabIndex);
    boolean metadataMode = activeTab.metadataClassCache != null && tabValue instanceof Map<?, ?>;

    String[] allLines;
    if (metadataMode) {
      @SuppressWarnings("unchecked")
      Map<String, Object> meta = (Map<String, Object>) tabValue;
      allLines = detailBuilder.buildMetadataDetailLines(meta, activeTab);
    } else {
      allLines =
          detailBuilder.buildDetailLines(
              tabValue, ctx.detailTabNames.get(ctx.activeDetailTabIndex));
    }

    // Apply detail search filter
    String[] detailLines;
    if (activeTab.detailSearchQuery != null && !activeTab.detailSearchQuery.isEmpty()) {
      String lower = activeTab.detailSearchQuery.toLowerCase();
      List<String> filtered = new ArrayList<>();
      for (String line : allLines) {
        if (line.toLowerCase().contains(lower)) {
          filtered.add(line);
        }
      }
      detailLines = filtered.toArray(new String[0]);
    } else {
      detailLines = allLines;
    }
    int totalLines = detailLines.length;

    boolean needsVScroll = totalLines > inner.height();
    Rect contentArea = inner;
    Rect vScrollbarArea = null;
    if (needsVScroll) {
      List<Rect> hSplit =
          Layout.horizontal().constraints(Constraint.fill(), Constraint.length(1)).split(inner);
      contentArea = hSplit.get(0);
      vScrollbarArea = hSplit.get(1);
    }

    int visibleHeight = contentArea.height();
    ctx.detailAreaHeight = Math.max(1, visibleHeight);
    int scrollOffset = detailBuilder.getDetailScrollOffset();
    int maxScroll = Math.max(0, totalLines - visibleHeight);
    scrollOffset = Math.min(scrollOffset, maxScroll);
    detailBuilder.setDetailScrollOffset(scrollOffset);

    int start = scrollOffset;
    int end = Math.min(start + visibleHeight, totalLines);

    boolean showCursor = metadataMode && ctx.focus == Focus.DETAIL && ctx.detailCursorLine >= 0;
    Style cursorStyle = Style.create().reversed();
    Style navigableStyle = Style.create().fg(Color.CYAN).bold();

    List<Line> styledLines = new ArrayList<>(end - start);
    int detailWidth = contentArea.width();
    for (int i = start; i < end; i++) {
      String line = applyHScroll(detailLines[i], ctx.detailHScrollOffset, detailWidth);
      if (showCursor && i == ctx.detailCursorLine) {
        if (line.length() < detailWidth) {
          line = line + " ".repeat(detailWidth - line.length());
        }
        styledLines.add(Line.from(Span.styled(line, cursorStyle)));
      } else if (metadataMode
          && ctx.detailLineTypeRefs != null
          && i < ctx.detailLineTypeRefs.size()
          && ctx.detailLineTypeRefs.get(i) != null) {
        styledLines.add(Line.from(Span.styled(line, navigableStyle)));
      } else if (activeTab.detailSearchQuery != null && !activeTab.detailSearchQuery.isEmpty()) {
        styledLines.add(buildHighlightedLine(line, activeTab.detailSearchQuery, null));
      } else {
        styledLines.add(Line.from(line));
      }
    }

    Paragraph detail = Paragraph.builder().text(new Text(styledLines, null)).build();
    frame.renderWidget(detail, contentArea);

    if (vScrollbarArea != null) {
      ScrollbarState vState =
          new ScrollbarState(totalLines)
              .viewportContentLength(visibleHeight)
              .position(scrollOffset);
      frame.renderStatefulWidget(Scrollbar.vertical(), vScrollbarArea, vState);
    }
  }

  // ---- input / search / history / tips / hints ----

  private void renderInput(Frame frame, Rect area) {
    Block.Builder inputBlockBuilder =
        Block.builder()
            .title(mnemonicTitle("Command >", 0))
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED);
    if (ctx.focus == Focus.INPUT) {
      inputBlockBuilder.borderColor(Color.CYAN);
    }
    TextInput textInput = TextInput.builder().block(inputBlockBuilder.build()).build();
    textInput.renderWithCursor(area, frame.buffer(), ctx.inputState, frame);
  }

  private void renderSearchBar(Frame frame, Rect area) {
    String prefix = ctx.searchOriginFocus == Focus.DETAIL ? "/detail " : "/ ";
    String text = prefix + ctx.searchInputState.text();
    if (text.length() < area.width()) {
      text = text + " ".repeat(area.width() - text.length());
    }
    Paragraph bar =
        Paragraph.builder()
            .text(Text.raw(text))
            .style(Style.create().bg(Color.DARK_GRAY).fg(Color.YELLOW))
            .build();
    frame.renderWidget(bar, area);
  }

  private void renderHistorySearchBar(Frame frame, Rect area) {
    String matched = ctx.inputState.text();
    String prefix =
        ctx.historySearchFailing ? "(failing reverse-i-search) " : "(reverse-i-search) ";
    String text = prefix + ctx.historySearchQuery + ": " + matched;
    if (text.length() < area.width()) {
      text = text + " ".repeat(area.width() - text.length());
    }
    Style style =
        ctx.historySearchFailing
            ? Style.create().bg(Color.DARK_GRAY).fg(Color.RED)
            : Style.create().bg(Color.DARK_GRAY).fg(Color.YELLOW);
    Paragraph bar = Paragraph.builder().text(Text.raw(text)).style(style).build();
    frame.renderWidget(bar, area);
  }

  private void renderTipLine(Frame frame, Rect area) {
    int tipIndex = (int) ((ctx.renderTick / TuiContext.TIP_ROTATE_TICKS) % TuiContext.TIPS.length);
    String tip = TuiContext.TIPS[tipIndex];
    Paragraph bar =
        Paragraph.builder()
            .text(Text.from(Line.from(Span.styled(tip, Style.create().fg(Color.DARK_GRAY)))))
            .build();
    frame.renderWidget(bar, area);
  }

  private void renderHints(Frame frame, Rect area) {
    // Temporary hint message
    if (ctx.hintMessage != null) {
      if (ctx.renderTick - ctx.hintMessageTick > TuiContext.HINT_MESSAGE_TICKS) {
        ctx.hintMessage = null;
      } else {
        String msg = " " + ctx.hintMessage;
        if (msg.length() < area.width()) {
          msg = msg + " ".repeat(area.width() - msg.length());
        }
        Paragraph bar =
            Paragraph.builder()
                .text(Text.raw(msg))
                .style(Style.create().bg(Color.DARK_GRAY).fg(Color.GREEN))
                .build();
        frame.renderWidget(bar, area);
        return;
      }
    }

    String altMod = (TuiContext.PLATFORM == TuiContext.Platform.MACOS) ? "Opt" : "Alt";
    String hints;
    if (ctx.focus == Focus.HISTORY_SEARCH) {
      hints = " Type to search  Ctrl+R:older  Enter:accept  Esc:cancel";
    } else if (ctx.focus == Focus.SEARCH) {
      String target = ctx.searchOriginFocus == Focus.DETAIL ? "detail" : "results";
      hints = " Type to filter (" + target + ")  Ctrl+L:both  Enter:confirm  Esc:cancel";
    } else if (ctx.focus == Focus.DETAIL) {
      boolean hasCursor = ctx.detailLineTypeRefs != null && ctx.detailCursorLine >= 0;
      String drillHint = "";
      if (hasCursor
          && ctx.detailCursorLine < ctx.detailLineTypeRefs.size()
          && ctx.detailLineTypeRefs.get(ctx.detailCursorLine) != null) {
        drillHint = "Enter:drill-down  ";
      }
      String cursorHint = hasCursor ? "\u2191\u2193:select  " : "\u2191\u2193:scroll  ";
      String resultTabHint = ctx.tabs.size() > 1 ? "{}:pins  " : "";
      hints =
          " "
              + cursorHint
              + "\u2190\u2192:scroll  "
              + "[]:tabs  "
              + resultTabHint
              + drillHint
              + "/:search  "
              + "S-\u2191\u2193:history  "
              + "S-Tab:focus  "
              + altMod
              + "+r:results  "
              + altMod
              + "+c:cmd  Esc:back";
    } else if (ctx.focus == Focus.RESULTS && ctx.browserMode) {
      if (ctx.sidebarFocused) {
        String pinsHint = ctx.tabs.size() > 1 ? "  {}:pins" : "";
        String viewLabel = ctx.metadataBrowserMode ? "view detail" : "view entries";
        hints = " \u2191\u2193:select  Enter/\u2192:" + viewLabel + "  Esc:close" + pinsHint;
      } else if (ctx.metadataBrowserMode) {
        ResultTab at = ctx.activeTab();
        String navHint = "";
        if (ctx.metadataBrowserLineRefs != null
            && at.selectedRow >= 0
            && at.selectedRow < ctx.metadataBrowserLineRefs.size()
            && ctx.metadataBrowserLineRefs.get(at.selectedRow) != null) {
          navHint = "Enter:drill-down  ";
        }
        hints = " \u2191\u2193:cursor  " + navHint + "\u2190:sidebar  /:search  Esc:back";
      } else {
        ResultTab at = ctx.activeTab();
        String rowHint =
            (at.tableData != null && at.selectedRow >= 0)
                ? "\u2191\u2193:row  "
                : "\u2191\u2193:scroll  ";
        String sortHint = "";
        if (at.tableData != null && at.tableHeaders != null) {
          sortHint =
              at.sortColumn >= 0 ? "<>:sort col  " + altMod + "+r:reverse  " : "<>:sort col  ";
        }
        String filterHint = at.filteredIndices != null ? "/:search  Esc:clear  " : "/:search  ";
        String detailJump = ctx.detailTabNames.isEmpty() ? "" : altMod + "+d:detail  ";
        String tabSwitchHint = ctx.detailTabNames.isEmpty() ? "" : "[]:subtabs  ";
        String resultTabHint = ctx.tabs.size() > 1 ? "{}:pins  " : "";
        String pinHint = at.pinned ? "Ctrl+P:unpin  " : "Ctrl+P:pin  ";
        String exportHint = at.tableData != null ? "Ctrl+E:export  " : "";
        hints =
            " "
                + rowHint
                + "\u2190:types  "
                + sortHint
                + filterHint
                + tabSwitchHint
                + resultTabHint
                + pinHint
                + exportHint
                + detailJump
                + "Esc:types";
      }
    } else if (ctx.focus == Focus.RESULTS) {
      ResultTab at = ctx.activeTab();
      String rowHint =
          (at.tableData != null && at.selectedRow >= 0)
              ? "\u2191\u2193:row  "
              : "\u2191\u2193\u2190\u2192:scroll  ";
      String sortHint = "";
      if (at.tableData != null && at.tableHeaders != null) {
        sortHint = at.sortColumn >= 0 ? "<>:sort col  " + altMod + "+r:reverse  " : "<>:sort col  ";
      }
      String filterHint = at.filteredIndices != null ? "/:search  Esc:clear  " : "/:search  ";
      String detailJump = ctx.detailTabNames.isEmpty() ? "" : altMod + "+d:detail  ";
      String tabSwitchHint = ctx.detailTabNames.isEmpty() ? "" : "[]:subtabs  ";
      String resultTabHint = ctx.tabs.size() > 1 ? "{}:pins  " : "";
      String pinHint = at.pinned ? "Ctrl+P:unpin  " : "Ctrl+P:pin  ";
      String exportHint = at.tableData != null ? "Ctrl+E:export  " : "";
      hints =
          " "
              + rowHint
              + sortHint
              + filterHint
              + tabSwitchHint
              + resultTabHint
              + pinHint
              + exportHint
              + detailJump
              + "S-\u2191\u2193:history  "
              + "S-Tab:focus  "
              + altMod
              + "+c:cmd  Esc:input";
    } else {
      if (ctx.completionPopupVisible) {
        hints = " \u2191\u2193:select  Enter/Tab:accept  Esc:cancel  Type to filter";
      } else if (ctx.cellPickerVisible) {
        hints = " \u2191\u2193:select  Enter:insert  Esc:cancel";
      } else {
        hints =
            " Enter:run  "
                + "S-\u2191\u2193:history  "
                + "Ctrl+R:search history  "
                + "Tab:complete  "
                + "@:pick cell  "
                + "S-Tab:focus  "
                + altMod
                + "+r:results  "
                + "Ctrl+C:exit";
      }
    }
    if (hints.length() < area.width()) {
      hints = hints + " ".repeat(area.width() - hints.length());
    }
    Paragraph bar =
        Paragraph.builder()
            .text(Text.raw(hints))
            .style(Style.create().bg(Color.DARK_GRAY).fg(Color.WHITE))
            .build();
    frame.renderWidget(bar, area);
  }

  // ---- tab bar ----

  private void renderResultTabBar(Frame frame, Rect area) {
    String[] titles = new String[ctx.tabs.size()];
    for (int i = 0; i < ctx.tabs.size(); i++) {
      ResultTab tab = ctx.tabs.get(i);
      String prefix = tab.pinned ? "\uD83D\uDCCC " : "\u25B7 ";
      titles[i] = prefix + marqueeTitle(tab.name, ctx.renderTick - tab.marqueeTick0);
    }
    Tabs tabsWidget =
        Tabs.builder()
            .titles(titles)
            .highlightStyle(Style.create().bold().fg(Color.CYAN))
            .divider(" | ")
            .style(Style.create().bg(Color.DARK_GRAY).fg(Color.WHITE))
            .build();
    TabsState tabsState = new TabsState(ctx.activeTabIndex);
    frame.renderStatefulWidget(tabsWidget, area, tabsState);
  }

  // ---- popup overlays ----

  void renderCompletionPopup(Frame frame) {
    if (ctx.inputAreaRect == null) return;

    int maxCandidateWidth = 0;
    for (Candidate c : ctx.completionFiltered) {
      maxCandidateWidth = Math.max(maxCandidateWidth, c.value().length());
    }

    int popupHeight = Math.min(ctx.completionFiltered.size(), TuiContext.COMPLETION_MAX_HEIGHT);
    int popupWidth = Math.min(maxCandidateWidth + 2, TuiContext.COMPLETION_MAX_WIDTH);
    popupWidth = Math.min(popupWidth, frame.area().width());

    int x = ctx.inputAreaRect.x() + ctx.completionWordStart + 2;
    int y = ctx.inputAreaRect.y() - popupHeight;
    if (x + popupWidth > frame.area().width()) {
      x = Math.max(0, frame.area().width() - popupWidth);
    }
    if (y < 0) y = 0;

    Rect popupRect = new Rect(x, y, popupWidth, popupHeight);

    Style normalStyle = Style.create().bg(Color.DARK_GRAY).fg(Color.WHITE);
    Style highlightStyle = Style.create().bg(Color.CYAN).fg(Color.BLACK).bold();

    int visibleCount = popupRect.height();
    int start = ctx.completionScrollOffset;
    int end = Math.min(start + visibleCount, ctx.completionFiltered.size());

    List<Line> lines = new ArrayList<>(end - start);
    for (int i = start; i < end; i++) {
      String value = " " + ctx.completionFiltered.get(i).value();
      if (value.length() > popupRect.width()) {
        value = value.substring(0, popupRect.width());
      }
      if (value.length() < popupRect.width()) {
        value = value + " ".repeat(popupRect.width() - value.length());
      }
      Style style = (i == ctx.completionSelectedIndex) ? highlightStyle : normalStyle;
      lines.add(Line.from(Span.styled(value, style)));
    }

    Paragraph popup = Paragraph.builder().text(new Text(lines, null)).build();
    frame.renderWidget(popup, popupRect);
  }

  void renderCellPicker(Frame frame) {
    if (ctx.inputAreaRect == null || ctx.cellPickerEntries == null) return;

    int nameWidth = 0;
    int valueWidth = 0;
    for (String[] entry : ctx.cellPickerEntries) {
      nameWidth = Math.max(nameWidth, entry[0].length());
      valueWidth = Math.max(valueWidth, entry[1].length());
    }
    nameWidth = Math.min(nameWidth + 1, 25);
    valueWidth = Math.min(valueWidth + 1, 25);
    int popupWidth = Math.min(nameWidth + valueWidth + 3, TuiContext.COMPLETION_MAX_WIDTH);
    popupWidth = Math.min(popupWidth, frame.area().width());
    int popupHeight = Math.min(ctx.cellPickerEntries.size(), TuiContext.COMPLETION_MAX_HEIGHT);

    int x = ctx.inputAreaRect.x() + ctx.inputState.cursorPosition() + 2;
    int y = ctx.inputAreaRect.y() - popupHeight;
    if (x + popupWidth > frame.area().width()) {
      x = Math.max(0, frame.area().width() - popupWidth);
    }
    if (y < 0) y = 0;

    Rect popupRect = new Rect(x, y, popupWidth, popupHeight);
    Style nameNormal = Style.create().bg(Color.DARK_GRAY).fg(Color.WHITE);
    Style valueNormal = Style.create().bg(Color.DARK_GRAY).fg(Color.GRAY);
    Style nameHighlight = Style.create().bg(Color.CYAN).fg(Color.BLACK).bold();
    Style valueHighlight = Style.create().bg(Color.CYAN).fg(Color.DARK_GRAY);
    Style separatorStyle = Style.create().bg(Color.DARK_GRAY).fg(Color.YELLOW).dim();

    int start = ctx.cellPickerScrollOffset;
    int end = Math.min(start + popupHeight, ctx.cellPickerEntries.size());
    List<Line> lines = new ArrayList<>(end - start);
    for (int i = start; i < end; i++) {
      if (ctx.isCellPickerSeparator(i)) {
        String label = " " + ctx.cellPickerEntries.get(i)[0];
        if (label.length() < popupRect.width()) {
          label = label + " ".repeat(popupRect.width() - label.length());
        } else if (label.length() > popupRect.width()) {
          label = label.substring(0, popupRect.width());
        }
        lines.add(Line.from(Span.styled(label, separatorStyle)));
        continue;
      }
      String name = " " + ctx.cellPickerEntries.get(i)[0];
      String value = ctx.cellPickerEntries.get(i)[1] + " ";
      int avail = popupRect.width();
      int nameLen = Math.min(name.length(), nameWidth + 1);
      int valueLen = avail - nameLen - 1;
      if (valueLen < 0) valueLen = 0;
      String nameStr =
          name.length() > nameLen
              ? name.substring(0, nameLen)
              : name + " ".repeat(nameLen - name.length());
      String sep = " ";
      String valueStr =
          value.length() > valueLen
              ? value.substring(0, valueLen)
              : value + " ".repeat(Math.max(0, valueLen - value.length()));
      boolean highlight = (i == ctx.cellPickerSelectedIndex);
      lines.add(
          Line.from(
              Span.styled(nameStr, highlight ? nameHighlight : nameNormal),
              Span.styled(sep, highlight ? nameHighlight : nameNormal),
              Span.styled(valueStr, highlight ? valueHighlight : valueNormal)));
    }

    Paragraph popup = Paragraph.builder().text(new Text(lines, null)).build();
    frame.renderWidget(popup, popupRect);
  }

  void renderSessionPicker(Frame frame) {
    if (ctx.sessionPickerEntries == null || ctx.sessionPickerEntries.isEmpty()) return;

    int currentId = sessions.current().map(r -> r.id).orElse(-1);
    int maxWidth = 0;
    List<String> labels = new ArrayList<>(ctx.sessionPickerEntries.size());
    for (SessionManager.SessionRef ref : ctx.sessionPickerEntries) {
      String marker = ref.id == currentId ? "* " : "  ";
      String name =
          ref.alias != null ? ref.alias : ref.session.getRecordingPath().getFileName().toString();
      String label = marker + "#" + ref.id + " " + name;
      labels.add(label);
      maxWidth = Math.max(maxWidth, label.length());
    }
    int popupWidth = Math.min(maxWidth + 2, TuiContext.COMPLETION_MAX_WIDTH);
    popupWidth = Math.min(popupWidth, frame.area().width());
    int popupHeight = Math.min(ctx.sessionPickerEntries.size(), TuiContext.COMPLETION_MAX_HEIGHT);

    int x = Math.max(0, (frame.area().width() - popupWidth) / 2);
    int y = Math.max(1, (frame.area().height() - popupHeight) / 2);

    Rect popupRect = new Rect(x, y, popupWidth, popupHeight);
    Style normal = Style.create().bg(Color.DARK_GRAY).fg(Color.WHITE);
    Style highlight = Style.create().bg(Color.CYAN).fg(Color.BLACK).bold();

    List<Line> lines = new ArrayList<>(popupHeight);
    for (int i = 0; i < popupHeight && i < labels.size(); i++) {
      String label = " " + labels.get(i);
      if (label.length() < popupWidth) {
        label = label + " ".repeat(popupWidth - label.length());
      } else if (label.length() > popupWidth) {
        label = label.substring(0, popupWidth);
      }
      lines.add(
          Line.from(Span.styled(label, i == ctx.sessionPickerSelectedIndex ? highlight : normal)));
    }

    Paragraph popup = Paragraph.builder().text(new Text(lines, null)).build();
    frame.renderWidget(popup, popupRect);
  }

  void renderExportPopup(Frame frame) {
    int popupWidth = Math.min(frame.area().width() - 4, 70);
    int popupHeight = 3;
    int x = Math.max(0, (frame.area().width() - popupWidth) / 2);
    int y = Math.max(1, (frame.area().height() - popupHeight) / 2);

    Rect popupRect = new Rect(x, y, popupWidth, popupHeight);

    Block popupBlock =
        Block.builder()
            .title(Title.from("Export CSV"))
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(Color.YELLOW)
            .build();
    TextInput exportInput = TextInput.builder().block(popupBlock).build();
    exportInput.renderWithCursor(popupRect, frame.buffer(), ctx.exportPathState, frame);
  }

  // ---- static helpers ----

  boolean showTabBar() {
    if (ctx.tabs.size() >= 2) return true;
    for (int i = 0; i < ctx.tabs.size(); i++) {
      if (ctx.tabs.get(i).pinned) return true;
    }
    return false;
  }

  static String marqueeTitle(String fullTitle, long tick) {
    int len = fullTitle.length();
    if (len <= TuiContext.MAX_TAB_TITLE_WIDTH) return fullTitle;
    int scrollDistance = len - TuiContext.MAX_TAB_TITLE_WIDTH;
    int scrollTicks = scrollDistance * TuiContext.SCROLL_SPEED;
    int cycleLength = TuiContext.PAUSE_START_TICKS + scrollTicks + TuiContext.PAUSE_END_TICKS;
    int tickInCycle = (int) (tick % cycleLength);

    int offset;
    if (tickInCycle < TuiContext.PAUSE_START_TICKS) {
      offset = 0;
    } else if (tickInCycle < TuiContext.PAUSE_START_TICKS + scrollTicks) {
      offset = (tickInCycle - TuiContext.PAUSE_START_TICKS) / TuiContext.SCROLL_SPEED;
    } else {
      offset = scrollDistance;
    }

    boolean showLeadingEllipsis = offset > 0;
    boolean showTrailingEllipsis = offset < scrollDistance;

    if (showLeadingEllipsis && showTrailingEllipsis) {
      return "\u2026"
          + fullTitle.substring(offset + 1, offset + TuiContext.MAX_TAB_TITLE_WIDTH - 1)
          + "\u2026";
    } else if (showLeadingEllipsis) {
      return "\u2026" + fullTitle.substring(offset + 1, offset + TuiContext.MAX_TAB_TITLE_WIDTH);
    } else if (showTrailingEllipsis) {
      return fullTitle.substring(offset, offset + TuiContext.MAX_TAB_TITLE_WIDTH - 1) + "\u2026";
    } else {
      return fullTitle.substring(offset, offset + TuiContext.MAX_TAB_TITLE_WIDTH);
    }
  }

  static Title mnemonicTitle(String text, int mnemonicIndex) {
    Style underlineStyle = Style.create().underlined().bold();
    if (mnemonicIndex <= 0) {
      return Title.from(
          Line.from(
              Span.styled(text.substring(0, 1), underlineStyle), Span.raw(text.substring(1))));
    } else if (mnemonicIndex >= text.length() - 1) {
      return Title.from(
          Line.from(
              Span.raw(text.substring(0, mnemonicIndex)),
              Span.styled(text.substring(mnemonicIndex), underlineStyle)));
    } else {
      return Title.from(
          Line.from(
              Span.raw(text.substring(0, mnemonicIndex)),
              Span.styled(text.substring(mnemonicIndex, mnemonicIndex + 1), underlineStyle),
              Span.raw(text.substring(mnemonicIndex + 1))));
    }
  }

  static String applyHScroll(String line, int hOffset, int visibleWidth) {
    if (hOffset < line.length()) {
      int end = Math.min(hOffset + visibleWidth, line.length());
      return line.substring(hOffset, end);
    }
    return "";
  }

  static Line buildHighlightedLine(String text, String query, Style baseStyle) {
    if (query == null || query.isEmpty()) {
      return baseStyle != null ? Line.from(Span.styled(text, baseStyle)) : Line.from(text);
    }
    String lowerText = text.toLowerCase();
    String lowerQuery = query.toLowerCase();
    int qLen = lowerQuery.length();
    List<Span> spans = new ArrayList<>();
    int pos = 0;
    while (pos < text.length()) {
      int match = lowerText.indexOf(lowerQuery, pos);
      if (match < 0) {
        String tail = text.substring(pos);
        spans.add(baseStyle != null ? Span.styled(tail, baseStyle) : Span.raw(tail));
        break;
      }
      if (match > pos) {
        String before = text.substring(pos, match);
        spans.add(baseStyle != null ? Span.styled(before, baseStyle) : Span.raw(before));
      }
      spans.add(Span.styled(text.substring(match, match + qLen), HIGHLIGHT_STYLE));
      pos = match + qLen;
    }
    if (spans.isEmpty()) {
      return baseStyle != null ? Line.from(Span.styled(text, baseStyle)) : Line.from(text);
    }
    return Line.from(spans.toArray(new Span[0]));
  }

  static String injectSortIndicator(String headerText, ResultTab tab) {
    String colName = tab.tableHeaders.get(tab.sortColumn);
    String indicator = tab.sortAscending ? "\u25B2" : "\u25BC";
    int pos = headerText.indexOf(colName);
    if (pos >= 0) {
      return headerText.substring(0, pos) + indicator + headerText.substring(pos);
    }
    return headerText;
  }
}

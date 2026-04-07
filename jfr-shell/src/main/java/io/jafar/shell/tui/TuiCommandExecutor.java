package io.jafar.shell.tui;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.cli.TuiTableRenderer;
import io.jafar.shell.core.BrowseCategoryDescriptor;
import io.jafar.shell.core.CommandDescriptor;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.TuiAdapter;
import io.jafar.shell.tui.TuiContext.Focus;
import io.jafar.shell.tui.TuiContext.ResultTab;
import io.jafar.shell.tui.TuiContext.TuiParsedLine;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jline.reader.Candidate;

/**
 * Handles command dispatch, async execution, sorting, tab management, history, scrolling,
 * filtering, completion, cell picker, session picker, and CSV export.
 */
public final class TuiCommandExecutor {
  private static final DateTimeFormatter EXPORT_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private static final Set<String> SCRIPTING_KEYWORDS = Set.of("if", "elif", "else", "endif");

  private final TuiContext ctx;
  private final ExecutorService commandExecutor;

  // Set after construction to break circular dependency (dispatcher → IO → executor)
  private CommandDispatcher dispatcher;
  private SessionManager<? extends Session> sessions;
  private org.jline.reader.Completer completer;
  private TuiBrowserController browser;
  private TuiDetailBuilder detailBuilder;
  private TuiAdapter tuiAdapter;

  TuiCommandExecutor(TuiContext ctx, ExecutorService commandExecutor) {
    this.ctx = ctx;
    this.commandExecutor = commandExecutor;
  }

  void setDispatcher(CommandDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  void setSessions(SessionManager<? extends Session> sessions) {
    this.sessions = sessions;
  }

  public void setCompleter(org.jline.reader.Completer completer) {
    this.completer = completer;
  }

  void setBrowser(TuiBrowserController browser) {
    this.browser = browser;
  }

  void setDetailBuilder(TuiDetailBuilder detailBuilder) {
    this.detailBuilder = detailBuilder;
  }

  public void setTuiAdapter(TuiAdapter adapter) {
    this.tuiAdapter = adapter;
  }

  TuiAdapter getTuiAdapter() {
    return tuiAdapter;
  }

  // ---- output capture ----

  void addOutputLine(String s) {
    String stripped = TuiContext.stripAnsi(s);
    for (String line : stripped.split("\n", -1)) {
      String indented = "  " + line;
      if (ctx.asyncOutputBuffer != null) {
        ctx.asyncOutputBuffer.add(indented);
        ctx.asyncMaxLineWidth = Math.max(ctx.asyncMaxLineWidth, indented.length());
      } else {
        ResultTab tab = ctx.activeTab();
        tab.lines.add(indented);
        tab.maxLineWidth = Math.max(tab.maxLineWidth, indented.length());
      }
    }
  }

  // ---- command execution ----

  void submitCommand() {
    String command = ctx.inputState.text().trim();
    ctx.inputState.clear();
    ctx.historyIndex = -1;

    if (command.isEmpty()) return;

    if (ctx.browserMode) {
      browser.exitBrowserMode();
    }

    if ("exit".equalsIgnoreCase(command) || "quit".equalsIgnoreCase(command)) {
      ctx.running = false;
      return;
    }

    if ("clear".equalsIgnoreCase(command)) {
      ResultTab tab = ctx.activeTab();
      tab.lines.clear();
      tab.scrollOffset = 0;
      tab.hScrollOffset = 0;
      tab.maxLineWidth = 0;
      tab.searchQuery = "";
      tab.detailSearchQuery = "";
      tab.filteredIndices = null;
      tab.filteredMaxLineWidth = 0;
      tab.tableData = null;
      tab.tableHeaders = null;
      tab.selectedRow = -1;
      tab.metadataClassCache = null;
      tab.sortColumn = -1;
      tab.sortAscending = true;
      tab.cpAllEntries = null;
      tab.cpColumnHeaders = null;
      tab.cpColumnWidths = null;
      tab.cpRenderedCount = 0;
      ctx.detailCursorLine = -1;
      ctx.detailLineTypeRefs = null;
      detailBuilder.buildDetailTabs(tab);
      return;
    }

    // pin [name]
    if (command.equalsIgnoreCase("pin") || command.toLowerCase().startsWith("pin ")) {
      ResultTab tab = ctx.activeTab();
      if (!tab.pinned) {
        String name = command.length() > 4 ? command.substring(4).trim() : "";
        if (!name.isEmpty()) {
          tab.name = name;
          tab.marqueeTick0 = ctx.renderTick;
        }
        tab.pinned = true;
        tab.pinnedSessionId = currentSessionId();
      }
      return;
    }

    // closetab [name]
    if (command.equalsIgnoreCase("closetab") || command.toLowerCase().startsWith("closetab ")) {
      String name = command.length() > 9 ? command.substring(9).trim() : "";
      int targetIndex = -1;
      if (name.isEmpty()) {
        if (ctx.activeTab().pinned) {
          targetIndex = ctx.activeTabIndex;
        }
      } else {
        for (int i = 0; i < ctx.tabs.size(); i++) {
          if (name.equalsIgnoreCase(ctx.tabs.get(i).name)) {
            targetIndex = i;
            break;
          }
        }
      }
      if (targetIndex >= 0 && ctx.tabs.size() > 1) {
        ctx.tabs.remove(targetIndex);
        if (ctx.activeTabIndex >= ctx.tabs.size()) {
          ctx.activeTabIndex = ctx.tabs.size() - 1;
        } else if (ctx.activeTabIndex > targetIndex) {
          ctx.activeTabIndex--;
        }
      }
      return;
    }

    // tabs
    if ("tabs".equalsIgnoreCase(command)) {
      ResultTab tab = ctx.activeTab();
      for (int i = 0; i < ctx.tabs.size(); i++) {
        ResultTab t = ctx.tabs.get(i);
        String marker = (i == ctx.activeTabIndex) ? " * " : "   ";
        String pinLabel = t.pinned ? " [pinned]" : "";
        tab.lines.add(marker + t.name + pinLabel + " (" + t.lines.size() + " lines)");
      }
      return;
    }

    ctx.commandHistory.add(command);
    ctx.lastCommand = command;

    // If executing from a pinned tab, activate that tab's session so the command
    // runs in the right context. Output is routed to the unpinned tab (created if none exists).
    ResultTab sourceTab = ctx.activeTab();
    if (sourceTab.pinned && sourceTab.pinnedSessionId != null && sessions != null) {
      sessions.use(String.valueOf(sourceTab.pinnedSessionId));
    }

    int currentIdx = findUnpinnedTab();
    if (currentIdx < 0) {
      ctx.tabs.add(new ResultTab(command));
      currentIdx = ctx.tabs.size() - 1;
    }
    ctx.activeTabIndex = currentIdx;
    ResultTab activeTab = ctx.activeTab();
    // Record the current session on the unpinned tab so switching back to it restores it.
    activeTab.pinnedSessionId = currentSessionId();
    activeTab.name = command;
    activeTab.marqueeTick0 = ctx.renderTick;
    activeTab.lines.clear();
    activeTab.scrollOffset = 0;
    activeTab.hScrollOffset = 0;
    activeTab.maxLineWidth = 0;
    activeTab.searchQuery = "";
    activeTab.detailSearchQuery = "";
    activeTab.filteredIndices = null;
    activeTab.filteredMaxLineWidth = 0;
    activeTab.tableData = null;
    activeTab.tableHeaders = null;
    activeTab.dataStartLine = -1;
    activeTab.selectedRow = -1;
    activeTab.metadataClassCache = null;
    activeTab.sortColumn = -1;
    activeTab.sortAscending = true;
    activeTab.cpAllEntries = null;
    activeTab.cpColumnHeaders = null;
    activeTab.cpColumnWidths = null;
    activeTab.cpRenderedCount = 0;
    browser.clearNavHistory();

    // Pre-execution check for expensive operations
    if (tuiAdapter != null) {
      Session preCheckSession = sessions.current().map(ref -> ref.session).orElse(null);
      String warning = tuiAdapter.getExpensiveOperationWarning(command, preCheckSession);
      if (warning != null) {
        ctx.awaitingConfirmation = true;
        ctx.pendingConfirmCommand = command;
        ctx.confirmationMessage = warning;
        return;
      }
    }

    dispatchCommand(command);
  }

  /**
   * Executes a command that has already passed the expensive-operation confirmation check. Called
   * from the key handler when the user confirms a previously intercepted expensive command.
   */
  void submitConfirmedCommand(String command) {
    dispatchCommand(command);
  }

  private void dispatchCommand(String command) {
    ResultTab activeTab = ctx.activeTab();

    // Browser mode detection via TuiAdapter (must run before ownsCommand so bare "classes"
    // / "objects" enter browser mode rather than being routed to the adapter as queries)
    if (tuiAdapter != null) {
      String category = tuiAdapter.detectBrowserCommand(command);
      if (category != null) {
        Session session = sessions.current().map(ref -> ref.session).orElse(null);
        if (session != null) {
          BrowseCategoryDescriptor desc = tuiAdapter.describeBrowseCategory(category);

          // Async loading for expensive categories (e.g. JFR events scan)
          if (desc != null && desc.asyncLoading()) {
            ctx.asyncBrowserPending = true;
            ctx.browserCategory = category;
            ctx.asyncLinesBeforeDispatch = 0;
            ctx.asyncOutputBuffer = new ArrayList<>();
            ctx.asyncMaxLineWidth = 0;
            ctx.commandRunning = true;
            ctx.commandStartTick = ctx.renderTick;
            ctx.commandStartTimeMs = System.currentTimeMillis();
            ctx.focus = Focus.RESULTS;

            ctx.commandFuture =
                commandExecutor.submit(
                    () -> {
                      PrintStream origErr = System.err;
                      ProgressCapturingStream progressStream = new ProgressCapturingStream(ctx);
                      System.setErr(progressStream);
                      try {
                        List<Map<String, Object>> summary =
                            tuiAdapter.loadBrowseSummary(session, category);
                        ctx.asyncTableData = summary;
                      } catch (Exception e) {
                        ctx.asyncOutputBuffer.add("  Error: " + e.getMessage());
                        ctx.asyncBrowserPending = false;
                      } finally {
                        System.setErr(origErr);
                        ctx.asyncProgressMessage = null;
                        ctx.asyncProgressLines.clear();
                        for (String errLine : progressStream.getOutputLines()) {
                          addOutputLine("  " + errLine);
                        }
                        ctx.commandRunning = false;
                      }
                    });
            return;
          }

          // Synchronous browser mode
          try {
            List<Map<String, Object>> summary = tuiAdapter.loadBrowseSummary(session, category);
            if (summary != null && !summary.isEmpty()) {
              activeTab.tableData = summary;
              ctx.browserCategory = category;
              browser.enterBrowserMode(activeTab, category);
              return;
            }
          } catch (Exception ignore) {
            // Fall through to normal dispatch
          }
        }
      }
    }

    // If the adapter exclusively owns this command, route directly to it
    String cmdWord = command.trim().split("\\s+")[0].toLowerCase();
    CommandDescriptor desc = tuiAdapter != null ? tuiAdapter.describeCommand(cmdWord) : null;
    if (desc != null) {
      submitAdapterCommand(command, desc);
      return;
    }

    // Echo command
    activeTab.lines.add("> " + command);

    ctx.asyncLinesBeforeDispatch = activeTab.lines.size();
    ctx.asyncOutputBuffer = new ArrayList<>();
    ctx.asyncMaxLineWidth = 0;
    ctx.asyncProgressLines.clear();
    ctx.commandRunning = true;
    ctx.commandStartTick = ctx.renderTick;
    ctx.commandStartTimeMs = System.currentTimeMillis();
    ctx.focus = Focus.RESULTS;

    ctx.commandFuture =
        commandExecutor.submit(
            () -> {
              TuiTableRenderer.clearLastData();
              PrintStream origErr = System.err;
              ProgressCapturingStream progressStream = new ProgressCapturingStream(ctx);
              System.setErr(progressStream);
              try {
                boolean handled = dispatcher.dispatch(command);
                if (!handled && tuiAdapter != null) {
                  try {
                    tuiAdapter.dispatch(
                        command,
                        new TuiAdapter.CommandIO() {
                          @Override
                          public void println(String s) {
                            addOutputLine(s);
                          }

                          @Override
                          public void printf(String fmt, Object... args) {
                            addOutputLine(String.format(fmt, args));
                          }

                          @Override
                          public void error(String s) {
                            addOutputLine("ERROR: " + s);
                          }
                        });
                    handled = true;
                  } catch (Exception ignore) {
                    // Adapter dispatch failed — fall through to unknown command
                  }
                }
                if (!handled) {
                  ctx.asyncOutputBuffer.add(
                      "  Unknown command. Type 'help' for available commands.");
                }
              } catch (Exception e) {
                ctx.asyncOutputBuffer.add("  Error: " + e.getMessage());
              } finally {
                System.setErr(origErr);
                ctx.asyncProgressMessage = null;
                ctx.asyncProgressLines.clear();
                for (String errLine : progressStream.getOutputLines()) {
                  addOutputLine("  " + errLine);
                }
                ctx.asyncTableData = TuiTableRenderer.getLastTableData();
                ctx.asyncTableHeaders = TuiTableRenderer.getLastTableHeaders();
                ctx.asyncMetadataClasses = TuiTableRenderer.getLastMetadataClasses();
                ctx.asyncPreambleLines = TuiTableRenderer.getLastPreambleLines();
                TuiTableRenderer.clearLastData();
                ctx.commandRunning = false;
              }
            });
  }

  /** Submits a command directly to the TUI adapter, bypassing CommandDispatcher. */
  private void submitAdapterCommand(String command, CommandDescriptor desc) {
    ResultTab activeTab = ctx.activeTab();
    activeTab.lines.add("> " + command);
    ctx.asyncLinesBeforeDispatch = activeTab.lines.size();
    ctx.asyncOutputBuffer = new ArrayList<>();
    ctx.asyncMaxLineWidth = 0;
    ctx.asyncProgressLines.clear();
    ctx.commandRunning = true;
    ctx.commandStartTick = ctx.renderTick;
    ctx.commandStartTimeMs = System.currentTimeMillis();
    ctx.focus = Focus.RESULTS;

    ctx.commandFuture =
        commandExecutor.submit(
            () -> {
              TuiTableRenderer.clearLastData();
              PrintStream origErr = System.err;
              ProgressCapturingStream progressStream = new ProgressCapturingStream(ctx);
              System.setErr(progressStream);
              try {
                tuiAdapter.dispatch(
                    command,
                    new TuiAdapter.CommandIO() {
                      @Override
                      public void println(String s) {
                        addOutputLine(s);
                      }

                      @Override
                      public void printf(String fmt, Object... args) {
                        addOutputLine(String.format(fmt, args));
                      }

                      @Override
                      public void error(String s) {
                        addOutputLine("ERROR: " + s);
                      }

                      @Override
                      public void renderTable(List<Map<String, Object>> rows) {
                        switch (desc.outputMode()) {
                          case TABULAR, MASTER_DETAIL ->
                              TuiTableRenderer.render(
                                  rows,
                                  new CommandDispatcher.IO() {
                                    @Override
                                    public void println(String s) {
                                      addOutputLine(s);
                                    }

                                    @Override
                                    public void printf(String fmt, Object... args) {
                                      addOutputLine(String.format(fmt, args));
                                    }

                                    @Override
                                    public void error(String s) {
                                      addOutputLine("ERROR: " + s);
                                    }
                                  });
                          // TODO: MASTER_DETAIL — auto-open detail pane after completion
                          case TEXT -> {
                            if (rows == null || rows.isEmpty()) {
                              addOutputLine("(no rows)");
                            } else {
                              rows.forEach(row -> addOutputLine(row.toString()));
                            }
                          }
                        }
                      }
                    });
              } catch (Exception e) {
                ctx.asyncOutputBuffer.add("  Error: " + e.getMessage());
              } finally {
                System.setErr(origErr);
                ctx.asyncProgressMessage = null;
                ctx.asyncProgressLines.clear();
                for (String errLine : progressStream.getOutputLines()) {
                  addOutputLine("  " + errLine);
                }
                ctx.asyncTableData = TuiTableRenderer.getLastTableData();
                ctx.asyncTableHeaders = TuiTableRenderer.getLastTableHeaders();
                ctx.asyncMetadataClasses = TuiTableRenderer.getLastMetadataClasses();
                ctx.asyncPreambleLines = TuiTableRenderer.getLastPreambleLines();
                TuiTableRenderer.clearLastData();
                ctx.commandRunning = false;
              }
            });
  }

  /** Cancels the currently running command, interrupting its thread, and resets TUI state. */
  void cancelRunningCommand() {
    Future<?> future = ctx.commandFuture;
    if (future != null) {
      future.cancel(true);
      ctx.commandFuture = null;
    }
    ctx.commandRunning = false;
    ctx.asyncProgressMessage = null;
    ctx.asyncOutputBuffer = null;
    ctx.asyncProgressLines.clear();
    ctx.focus = Focus.INPUT;
    ctx.showHintMessage("Cancelled");
  }

  void finishAsyncCommand() {
    if (ctx.asyncBrowserPending) {
      ctx.asyncBrowserPending = false;
      ResultTab activeTab = ctx.activeTab();
      List<Map<String, Object>> summary = ctx.asyncTableData;
      ctx.asyncTableData = null;
      ctx.asyncTableHeaders = null;
      ctx.asyncMetadataClasses = null;
      ctx.asyncOutputBuffer = null;
      ctx.asyncMaxLineWidth = 0;
      ctx.asyncProgressLines.clear();
      ctx.commandFuture = null;

      if (summary != null && !summary.isEmpty()) {
        activeTab.tableData = summary;
        String category = ctx.browserCategory != null ? ctx.browserCategory : "events";
        ctx.browserCategory = null;
        browser.enterBrowserMode(activeTab, category);
      } else {
        activeTab.lines.clear();
        activeTab.lines.add("  No entries found.");
        activeTab.maxLineWidth = activeTab.lines.get(0).length();
      }
      return;
    }

    ResultTab activeTab = ctx.activeTab();
    for (String line : ctx.asyncOutputBuffer) {
      activeTab.lines.add(line);
    }
    activeTab.maxLineWidth = Math.max(activeTab.maxLineWidth, ctx.asyncMaxLineWidth);
    ctx.asyncOutputBuffer = null;
    ctx.asyncMaxLineWidth = 0;
    ctx.asyncProgressLines.clear();
    ctx.commandFuture = null;

    activeTab.tableData = ctx.asyncTableData;
    activeTab.tableHeaders = ctx.asyncTableHeaders;
    activeTab.metadataClassCache = ctx.asyncMetadataClasses;
    ctx.asyncTableData = null;
    ctx.asyncTableHeaders = null;
    ctx.asyncMetadataClasses = null;

    activeTab.sortColumn = -1;
    activeTab.sortAscending = true;
    if (activeTab.tableData != null && !activeTab.tableData.isEmpty()) {
      activeTab.dataStartLine = ctx.asyncLinesBeforeDispatch + ctx.asyncPreambleLines;
      // Jump to first hotspot/N+1 candidate if present
      int hotspotRow = -1;
      for (int i = 0; i < activeTab.tableData.size(); i++) {
        Object marker = activeTab.tableData.get(i).get(" ");
        if (marker instanceof String s && !s.isEmpty()) {
          hotspotRow = i;
          break;
        }
      }
      activeTab.selectedRow = hotspotRow >= 0 ? hotspotRow : 0;
      detailBuilder.buildDetailTabs(activeTab);
    } else {
      activeTab.selectedRow = -1;
      activeTab.dataStartLine = -1;
      detailBuilder.buildDetailTabs(activeTab);
    }

    if (activeTab.dataStartLine >= 0) {
      activeTab.pendingCenterScroll = true;
    } else {
      activeTab.scrollOffset = Math.max(0, activeTab.lines.size() - ctx.resultsAreaHeight);
    }
  }

  // ---- sorting ----

  void reverseSortIfActive() {
    ResultTab rt = ctx.activeTab();
    if (rt.sortColumn >= 0 && rt.tableData != null) {
      rt.sortAscending = !rt.sortAscending;
      applySortAndRerender(rt);
    }
  }

  void applySortAndRerender(ResultTab tab) {
    if (tab.tableData == null || tab.tableHeaders == null || tab.sortColumn < 0) return;

    if (tab.cpAllEntries != null) {
      String sortKey = tab.tableHeaders.get(tab.sortColumn);
      boolean asc = tab.sortAscending;
      tab.cpAllEntries.sort(
          (a, b) -> {
            int result =
                compareNatural(
                    TuiTableRenderer.toCell(a.get(sortKey)),
                    TuiTableRenderer.toCell(b.get(sortKey)));
            return asc ? result : -result;
          });
      tab.lines.clear();
      tab.maxLineWidth = 0;
      StringBuilder sb = new StringBuilder("  ");
      for (int c = 0; c < tab.cpColumnHeaders.size(); c++) {
        if (c > 0) sb.append("  ");
        sb.append(String.format("%-" + tab.cpColumnWidths[c] + "s", tab.cpColumnHeaders.get(c)));
      }
      String headerLine = sb.toString();
      tab.lines.add(headerLine);
      tab.maxLineWidth = Math.max(tab.maxLineWidth, headerLine.length());
      int pageSize = Math.min(tab.cpAllEntries.size(), Math.max(ctx.resultsAreaHeight + 5, 20));
      browser.renderCpPage(tab, 0, pageSize);
      tab.tableData = new ArrayList<>(tab.cpAllEntries.subList(0, pageSize));
      tab.cpRenderedCount = pageSize;
      tab.dataStartLine = 1;
      tab.selectedRow = Math.min(tab.selectedRow, Math.max(0, pageSize - 1));
      tab.scrollOffset = 0;
      tab.filteredIndices = null;
      tab.searchQuery = "";
      tab.detailSearchQuery = "";
      detailBuilder.buildDetailTabs(tab);
      return;
    }

    String sortKey = tab.tableHeaders.get(tab.sortColumn);
    boolean asc = tab.sortAscending;

    int size = tab.tableData.size();
    Integer[] indices = new Integer[size];
    for (int i = 0; i < size; i++) indices[i] = i;

    Comparator<Integer> cmp =
        (a, b) -> {
          String va = TuiTableRenderer.toCell(tab.tableData.get(a).get(sortKey));
          String vb = TuiTableRenderer.toCell(tab.tableData.get(b).get(sortKey));
          int result = compareNatural(va, vb);
          return asc ? result : -result;
        };
    Arrays.sort(indices, cmp);

    List<Map<String, Object>> sortedData = new ArrayList<>(size);
    List<Map<String, Object>> sortedMeta =
        tab.metadataClassCache != null ? new ArrayList<>(size) : null;
    for (int idx : indices) {
      sortedData.add(tab.tableData.get(idx));
      if (sortedMeta != null && idx < tab.metadataClassCache.size()) {
        sortedMeta.add(tab.metadataClassCache.get(idx));
      }
    }
    tab.tableData = sortedData;
    if (sortedMeta != null) tab.metadataClassCache = sortedMeta;

    tab.lines.clear();
    tab.maxLineWidth = 0;
    tab.lines.add("> " + ctx.lastCommand);
    TuiTableRenderer.clearLastData();
    TuiTableRenderer.render(
        sortedData,
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {
            String stripped = TuiContext.stripAnsi(s);
            for (String line : stripped.split("\n", -1)) {
              String indented = "  " + line;
              tab.lines.add(indented);
              tab.maxLineWidth = Math.max(tab.maxLineWidth, indented.length());
            }
          }

          @Override
          public void printf(String fmt, Object... args) {
            println(String.format(fmt, args));
          }

          @Override
          public void error(String s) {
            println("ERROR: " + s);
          }
        });
    int preamble = TuiTableRenderer.getLastPreambleLines();
    TuiTableRenderer.clearLastData();

    tab.dataStartLine = 1 + preamble;
    tab.selectedRow = Math.min(tab.selectedRow, Math.max(0, tab.tableData.size() - 1));
    tab.scrollOffset = 0;
    tab.filteredIndices = null;
    tab.searchQuery = "";
    tab.detailSearchQuery = "";
    detailBuilder.buildDetailTabs(tab);
  }

  static int compareNatural(String a, String b) {
    try {
      double da = Double.parseDouble(a);
      double db = Double.parseDouble(b);
      return Double.compare(da, db);
    } catch (NumberFormatException ignore) {
    }
    return a.compareToIgnoreCase(b);
  }

  // ---- tab management ----

  void switchTab(int newIndex) {
    if (newIndex == ctx.activeTabIndex) return;
    int oldIndex = ctx.activeTabIndex;
    if (ctx.browserMode) {
      ctx.tabs.get(oldIndex).sidebarIndex = ctx.sidebarSelectedIndex;
    }
    ctx.activeTabIndex = newIndex;
    ResultTab newTab = ctx.tabs.get(newIndex);
    if (newTab.pinnedSessionId != null && sessions != null) {
      sessions.use(String.valueOf(newTab.pinnedSessionId));
    }
    if (newTab.sidebarIndex >= 0 && newTab.browserTypes != null) {
      ctx.browserMode = true;
      ctx.activeBrowserDescriptor = newTab.browserDescriptor;
      ctx.sidebarTypes = newTab.browserTypes;
      ctx.sidebarSelectedIndex = newTab.sidebarIndex;
      ctx.sidebarFocused = false;
    } else {
      if (newTab.sidebarIndex >= 0) newTab.sidebarIndex = -1;
      ctx.browserMode = false;
      ctx.activeBrowserDescriptor = null;
      ctx.sidebarFocused = false;
    }
    detailBuilder.buildDetailTabs(newTab);
  }

  void togglePin() {
    ResultTab tab = ctx.activeTab();
    if (tab.pinned) {
      int existingUnpinned = findUnpinnedTab();
      if (existingUnpinned >= 0) {
        ctx.tabs.remove(existingUnpinned);
        if (ctx.activeTabIndex > existingUnpinned) {
          ctx.activeTabIndex--;
        } else if (ctx.activeTabIndex >= ctx.tabs.size()) {
          ctx.activeTabIndex = ctx.tabs.size() - 1;
        }
      }
      tab.pinned = false;
      tab.name = "jfr>";
      tab.marqueeTick0 = ctx.renderTick;
    } else {
      tab.pinned = true;
      tab.pinnedSessionId = currentSessionId();
    }
  }

  private Integer currentSessionId() {
    return sessions != null ? sessions.current().map(r -> r.id).orElse(null) : null;
  }

  int findUnpinnedTab() {
    for (int i = ctx.tabs.size() - 1; i >= 0; i--) {
      if (!ctx.tabs.get(i).pinned) return i;
    }
    return -1;
  }

  // ---- history ----

  void loadHistory() {
    if (!Files.exists(TuiContext.HISTORY_PATH)) return;
    try {
      List<String> lines = Files.readAllLines(TuiContext.HISTORY_PATH);
      int start = Math.max(0, lines.size() - TuiContext.MAX_HISTORY);
      for (int i = start; i < lines.size(); i++) {
        String line = lines.get(i).trim();
        if (line.isEmpty()) continue;
        int colon = line.indexOf(':');
        if (colon > 0 && line.substring(0, colon).chars().allMatch(Character::isDigit)) {
          line = line.substring(colon + 1);
        }
        if (!line.isEmpty()) {
          ctx.commandHistory.add(line);
        }
      }
    } catch (IOException ignore) {
    }
  }

  public void saveHistory() {
    try {
      Files.createDirectories(TuiContext.HISTORY_PATH.getParent());
      List<String> toSave = ctx.commandHistory;
      if (toSave.size() > TuiContext.MAX_HISTORY) {
        toSave = toSave.subList(toSave.size() - TuiContext.MAX_HISTORY, toSave.size());
      }
      long ts = System.currentTimeMillis();
      List<String> formatted = new ArrayList<>(toSave.size());
      for (String cmd : toSave) {
        formatted.add(ts++ + ":" + cmd);
      }
      Files.write(TuiContext.HISTORY_PATH, formatted);
    } catch (IOException ignore) {
    }
  }

  void navigateHistory(int direction) {
    if (ctx.commandHistory.isEmpty()) return;

    if (direction < 0) {
      if (ctx.historyIndex < 0) {
        ctx.historyIndex = ctx.commandHistory.size() - 1;
      } else if (ctx.historyIndex > 0) {
        ctx.historyIndex--;
      }
    } else {
      if (ctx.historyIndex >= 0) {
        ctx.historyIndex++;
        if (ctx.historyIndex >= ctx.commandHistory.size()) {
          ctx.historyIndex = -1;
          ctx.inputState.clear();
          return;
        }
      }
    }

    if (ctx.historyIndex >= 0 && ctx.historyIndex < ctx.commandHistory.size()) {
      ctx.inputState.setText(ctx.commandHistory.get(ctx.historyIndex));
    }
  }

  int findHistoryMatch(String query, int fromIndex) {
    if (query.isEmpty() || ctx.commandHistory.isEmpty()) return -1;
    String lower = query.toLowerCase();
    for (int i = Math.min(fromIndex, ctx.commandHistory.size() - 1); i >= 0; i--) {
      if (ctx.commandHistory.get(i).toLowerCase().contains(lower)) {
        return i;
      }
    }
    return -1;
  }

  int findDistinctHistoryMatch(String query, int fromIndex, String skipValue) {
    if (query.isEmpty() || ctx.commandHistory.isEmpty()) return -1;
    String lower = query.toLowerCase();
    for (int i = Math.min(fromIndex, ctx.commandHistory.size() - 1); i >= 0; i--) {
      String entry = ctx.commandHistory.get(i);
      if (entry.toLowerCase().contains(lower) && !entry.equals(skipValue)) {
        return i;
      }
    }
    return -1;
  }

  // ---- scrolling ----

  void scrollResults(int delta) {
    ResultTab tab = ctx.activeTab();
    tab.scrollOffset = Math.max(0, tab.scrollOffset + delta);
    browser.ensureCpEntriesLoaded(tab, tab.scrollOffset + ctx.resultsAreaHeight);
  }

  void scrollHorizontal(int delta) {
    ResultTab tab = ctx.activeTab();
    tab.hScrollOffset = Math.max(0, tab.hScrollOffset + delta);
  }

  void moveSelectedRow(ResultTab tab, int delta) {
    int newRow;
    if (tab.filteredIndices != null && tab.dataStartLine >= 0) {
      newRow = findFilteredRow(tab, tab.selectedRow, delta);
    } else {
      browser.ensureCpEntriesLoaded(tab, tab.selectedRow + delta);
      newRow = Math.max(0, Math.min(tab.tableData.size() - 1, tab.selectedRow + delta));
    }
    if (newRow == tab.selectedRow) return;
    tab.selectedRow = newRow;
    detailBuilder.buildDetailTabs(tab);

    if (tab.filteredIndices != null) {
      int lineIdx = tab.dataStartLine + newRow;
      int filteredPos = tab.filteredIndices.indexOf(lineIdx);
      if (filteredPos >= 0) {
        if (filteredPos < tab.scrollOffset) {
          tab.scrollOffset = filteredPos;
        } else if (filteredPos >= tab.scrollOffset + ctx.resultsAreaHeight) {
          tab.scrollOffset = filteredPos - ctx.resultsAreaHeight + 1;
        }
      }
    } else {
      if (newRow < tab.scrollOffset) {
        tab.scrollOffset = newRow;
      } else if (newRow >= tab.scrollOffset + ctx.resultsAreaHeight) {
        tab.scrollOffset = newRow - ctx.resultsAreaHeight + 1;
      }
    }
  }

  static int findFilteredRow(ResultTab tab, int currentRow, int delta) {
    int direction = delta > 0 ? 1 : -1;
    int candidate = currentRow + direction;
    int maxRow = tab.tableData.size() - 1;
    while (candidate >= 0 && candidate <= maxRow) {
      int lineIdx = tab.dataStartLine + candidate;
      if (tab.filteredIndices.contains(lineIdx)) {
        return candidate;
      }
      candidate += direction;
    }
    return currentRow;
  }

  // ---- search / filter ----

  void applySearchFilter(ResultTab tab, String query) {
    tab.searchQuery = query;
    if (query.isEmpty()) {
      tab.filteredIndices = null;
      tab.filteredMaxLineWidth = 0;
      tab.scrollOffset = 0;
      return;
    }
    String lower = query.toLowerCase();
    List<Integer> indices = new ArrayList<>();
    int maxWidth = 0;
    for (int i = 0; i < tab.lines.size(); i++) {
      String line = tab.lines.get(i);
      if (line.toLowerCase().contains(lower)) {
        indices.add(i);
        maxWidth = Math.max(maxWidth, line.length());
      }
    }
    tab.filteredIndices = indices;
    tab.filteredMaxLineWidth = maxWidth;
    tab.scrollOffset = 0;
  }

  @SuppressWarnings("unchecked")
  void filterByThread(String threadName) {
    ResultTab tab = ctx.activeTab();
    if (tab.tableData == null || tab.dataStartLine < 0) return;

    // Toggle off if already filtering by the same thread
    String marker = "\u2261" + threadName;
    if (tab.filteredIndices != null && marker.equals(tab.searchQuery)) {
      tab.filteredIndices = null;
      tab.filteredMaxLineWidth = 0;
      tab.searchQuery = "";
      scrollToSelectedRow(tab);
      return;
    }

    List<Integer> indices = new ArrayList<>();
    int maxWidth = 0;
    for (int i = 0; i < tab.tableData.size(); i++) {
      Map<String, Object> row = tab.tableData.get(i);
      Object threads = row.get("threads");
      if (threads instanceof Map<?, ?> tm && tm.containsKey(threadName)) {
        int lineIdx = tab.dataStartLine + i;
        if (lineIdx < tab.lines.size()) {
          indices.add(lineIdx);
          maxWidth = Math.max(maxWidth, tab.lines.get(lineIdx).length());
        }
      }
    }
    // Use a marker prefix so toggle detection works
    tab.searchQuery = "\u2261" + threadName;
    tab.filteredIndices = indices;
    tab.filteredMaxLineWidth = maxWidth;
    int selectedLineIdx = tab.dataStartLine + tab.selectedRow;
    int filteredPos = indices.indexOf(selectedLineIdx);
    if (filteredPos >= 0) {
      tab.scrollOffset = Math.max(0, filteredPos - ctx.resultsAreaHeight / 2);
    } else {
      tab.scrollOffset = 0;
    }
  }

  void clearThreadFilter() {
    ResultTab tab = ctx.activeTab();
    if (tab.filteredIndices != null && tab.searchQuery.startsWith("\u2261")) {
      tab.filteredIndices = null;
      tab.filteredMaxLineWidth = 0;
      tab.searchQuery = "";
      scrollToSelectedRow(tab);
    }
  }

  private void scrollToSelectedRow(ResultTab tab) {
    if (tab.selectedRow >= 0) {
      tab.scrollOffset = Math.max(0, tab.selectedRow - ctx.resultsAreaHeight / 2);
    } else {
      tab.scrollOffset = 0;
    }
  }

  void applyLiveSearchFilter(ResultTab tab, String query) {
    if (ctx.searchOriginFocus == Focus.DETAIL) {
      tab.detailSearchQuery = query;
    } else if (ctx.searchOriginSidebar) {
      applySidebarFilter(query);
    } else {
      applySearchFilter(tab, query);
    }
  }

  void applySidebarFilter(String query) {
    ctx.sidebarSearchQuery = query;
    if (query.isEmpty() || ctx.sidebarTypes == null) {
      ctx.sidebarFilteredIndices = null;
      return;
    }
    String lower = query.toLowerCase();
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < ctx.sidebarTypes.size(); i++) {
      String name = String.valueOf(ctx.sidebarTypes.get(i).getOrDefault("name", ""));
      if (name.toLowerCase().contains(lower)) {
        indices.add(i);
      }
    }
    ctx.sidebarFilteredIndices = indices;
    ctx.sidebarScrollOffset = 0;
  }

  void enterSearchMode() {
    ctx.searchOriginFocus = (ctx.focus == Focus.DETAIL) ? Focus.DETAIL : Focus.RESULTS;
    ctx.searchOriginSidebar = (ctx.browserMode && ctx.sidebarFocused && ctx.focus == Focus.RESULTS);
    ctx.focus = Focus.SEARCH;
    ResultTab tab = ctx.activeTab();
    if (ctx.searchOriginFocus == Focus.DETAIL) {
      ctx.searchInputState.setText(tab.detailSearchQuery);
    } else if (ctx.searchOriginSidebar) {
      ctx.searchInputState.setText(ctx.sidebarSearchQuery);
    } else {
      ctx.searchInputState.setText(tab.searchQuery);
    }
  }

  void cancelSearch() {
    ResultTab tab = ctx.activeTab();
    if (ctx.searchOriginFocus == Focus.DETAIL) {
      tab.detailSearchQuery = "";
    } else if (ctx.searchOriginSidebar) {
      ctx.sidebarSearchQuery = "";
      ctx.sidebarFilteredIndices = null;
    } else {
      tab.searchQuery = "";
      tab.filteredIndices = null;
      tab.filteredMaxLineWidth = 0;
      tab.scrollOffset = 0;
    }
    ctx.focus = ctx.searchOriginFocus;
  }

  // ---- completion popup ----

  void openCompletionPopup() {
    if (ctx.completionPopupVisible) {
      acceptCompletion();
      return;
    }

    String text = ctx.inputState.text();
    int cursor = ctx.inputState.cursorPosition();

    TuiParsedLine parsed = new TuiParsedLine(text, cursor);
    List<Candidate> candidates = new ArrayList<>();
    try {
      completer.complete(null, parsed, candidates);
    } catch (Exception e) {
      return;
    }

    if (candidates.isEmpty()) return;

    if (text.isBlank()) {
      candidates.removeIf(
          c -> c.value().contains("(") || c.value().contains("/") || isScriptingKeyword(c.value()));
      if (candidates.isEmpty()) return;
    }

    candidates.sort(Comparator.comparing(Candidate::value, String.CASE_INSENSITIVE_ORDER));

    ctx.completionWordStart = cursor;
    while (ctx.completionWordStart > 0
        && !Character.isWhitespace(text.charAt(ctx.completionWordStart - 1))) {
      ctx.completionWordStart--;
    }
    ctx.completionOriginalWord = text.substring(ctx.completionWordStart, cursor);

    if (candidates.size() == 1) {
      applyCompletion(candidates.get(0).value());
      return;
    }

    String prefix = commonPrefix(candidates);
    if (prefix.length() > ctx.completionOriginalWord.length()) {
      applyCompletion(prefix);
      return;
    }

    ctx.completionAllCandidates = candidates;
    ctx.completionFiltered = new ArrayList<>(candidates);
    ctx.completionSelectedIndex = 0;
    ctx.completionScrollOffset = 0;
    ctx.completionPopupVisible = true;
    ctx.completionOriginalInput = text;
  }

  void closeCompletionPopup(boolean restore) {
    ctx.completionPopupVisible = false;
    if (restore && ctx.completionOriginalInput != null) {
      ctx.inputState.setText(ctx.completionOriginalInput);
    }
    ctx.completionAllCandidates = null;
    ctx.completionFiltered = null;
    ctx.completionOriginalInput = null;
    ctx.completionOriginalWord = null;
  }

  void acceptCompletion() {
    if (ctx.completionFiltered != null
        && ctx.completionSelectedIndex >= 0
        && ctx.completionSelectedIndex < ctx.completionFiltered.size()) {
      applyCompletion(ctx.completionFiltered.get(ctx.completionSelectedIndex).value());
    }
    closeCompletionPopup(false);
  }

  void refilterCompletions() {
    String text = ctx.inputState.text();
    int cursor = ctx.inputState.cursorPosition();
    int wordEnd = Math.min(cursor, text.length());
    if (ctx.completionWordStart > wordEnd) {
      closeCompletionPopup(false);
      return;
    }
    String currentWord = text.substring(ctx.completionWordStart, wordEnd);
    if (currentWord.isEmpty()) {
      closeCompletionPopup(false);
      return;
    }
    String lower = currentWord.toLowerCase();
    List<Candidate> filtered = new ArrayList<>();
    for (Candidate c : ctx.completionAllCandidates) {
      if (c.value().toLowerCase().startsWith(lower)) {
        filtered.add(c);
      }
    }
    if (filtered.isEmpty()) {
      closeCompletionPopup(false);
      return;
    }
    ctx.completionFiltered = filtered;
    ctx.completionSelectedIndex = Math.min(ctx.completionSelectedIndex, filtered.size() - 1);
    int maxVisible = TuiContext.COMPLETION_MAX_HEIGHT;
    ctx.completionScrollOffset =
        Math.min(ctx.completionScrollOffset, Math.max(0, filtered.size() - maxVisible));
  }

  void applyCompletion(String value) {
    String text = ctx.inputState.text();
    int cursor = ctx.inputState.cursorPosition();
    String before = text.substring(0, ctx.completionWordStart);
    String after = cursor <= text.length() ? text.substring(cursor) : "";
    String newText = before + value + after;
    ctx.inputState.clear();
    ctx.inputState.insert(newText);
    int target = ctx.completionWordStart + value.length();
    ctx.inputState.moveCursorToEnd();
    int overshoot = ctx.inputState.cursorPosition() - target;
    for (int i = 0; i < overshoot; i++) {
      ctx.inputState.moveCursorLeft();
    }
  }

  static String commonPrefix(List<Candidate> candidates) {
    String first = candidates.get(0).value();
    int len = first.length();
    for (int i = 1; i < candidates.size(); i++) {
      String other = candidates.get(i).value();
      len = Math.min(len, other.length());
      for (int j = 0; j < len; j++) {
        if (first.charAt(j) != other.charAt(j)) {
          len = j;
          break;
        }
      }
    }
    return first.substring(0, len);
  }

  static boolean isScriptingKeyword(String value) {
    return SCRIPTING_KEYWORDS.contains(value);
  }

  // ---- cell picker ----

  boolean openCellPicker() {
    if (ctx.completionPopupVisible || ctx.cellPickerVisible) return false;
    ResultTab tab = ctx.activeTab();
    ctx.cellPickerEntries = new ArrayList<>();

    if (ctx.browserMode) {
      boolean hasTypes =
          ctx.sidebarTypes != null
              && ctx.sidebarSelectedIndex >= 0
              && ctx.sidebarSelectedIndex < ctx.sidebarTypes.size();
      boolean hasEntries =
          tab.tableData != null && tab.selectedRow >= 0 && tab.selectedRow < tab.tableData.size();
      if (!hasTypes && !hasEntries) return false;

      if (hasTypes) {
        Map<String, Object> typeRow = ctx.sidebarTypes.get(ctx.sidebarSelectedIndex);
        ctx.cellPickerEntries.add(new String[] {"\u2500 type \u2500", ""});
        for (String key : typeRow.keySet()) {
          if (TuiDetailBuilder.isComplexValue(typeRow.get(key))) continue;
          ctx.cellPickerEntries.add(new String[] {key, TuiTableRenderer.toCell(typeRow.get(key))});
        }
      }
      if (hasEntries) {
        Map<String, Object> entryRow = tab.tableData.get(tab.selectedRow);
        List<String> headers =
            tab.tableHeaders != null ? tab.tableHeaders : new ArrayList<>(entryRow.keySet());
        ctx.cellPickerEntries.add(new String[] {"\u2500 entry \u2500", ""});
        for (String header : headers) {
          if (TuiDetailBuilder.isComplexValue(entryRow.get(header))) continue;
          ctx.cellPickerEntries.add(
              new String[] {header, TuiTableRenderer.toCell(entryRow.get(header))});
        }
      }
    } else if (tab.tableData != null
        && tab.selectedRow >= 0
        && tab.selectedRow < tab.tableData.size()) {
      Map<String, Object> sourceRow = tab.tableData.get(tab.selectedRow);
      List<String> headers =
          tab.tableHeaders != null ? tab.tableHeaders : new ArrayList<>(sourceRow.keySet());
      if (headers.isEmpty()) return false;
      for (String header : headers) {
        if (TuiDetailBuilder.isComplexValue(sourceRow.get(header))) continue;
        ctx.cellPickerEntries.add(
            new String[] {header, TuiTableRenderer.toCell(sourceRow.get(header))});
      }
    }

    // Remove trailing separators
    while (!ctx.cellPickerEntries.isEmpty()) {
      String[] last = ctx.cellPickerEntries.get(ctx.cellPickerEntries.size() - 1);
      if (last[1].isEmpty() && last[0].startsWith("\u2500")) {
        ctx.cellPickerEntries.remove(ctx.cellPickerEntries.size() - 1);
      } else {
        break;
      }
    }
    if (ctx.cellPickerEntries.isEmpty()) return false;
    ctx.cellPickerSelectedIndex = 0;
    if (ctx.cellPickerEntries.get(0)[1].isEmpty()
        && ctx.cellPickerEntries.get(0)[0].startsWith("\u2500")) {
      ctx.cellPickerSelectedIndex = Math.min(1, ctx.cellPickerEntries.size() - 1);
    }
    ctx.cellPickerScrollOffset = 0;
    ctx.cellPickerVisible = true;
    return true;
  }

  boolean isCellPickerSeparator(int index) {
    return ctx.isCellPickerSeparator(index);
  }

  void cellPickerMoveTo(int target, int direction) {
    int size = ctx.cellPickerEntries.size();
    while (target >= 0 && target < size && isCellPickerSeparator(target)) {
      target += direction;
    }
    if (target < 0 || target >= size) return;
    ctx.cellPickerSelectedIndex = target;
    if (ctx.cellPickerSelectedIndex < ctx.cellPickerScrollOffset) {
      ctx.cellPickerScrollOffset = ctx.cellPickerSelectedIndex;
    } else if (ctx.cellPickerSelectedIndex
        >= ctx.cellPickerScrollOffset + TuiContext.COMPLETION_MAX_HEIGHT) {
      ctx.cellPickerScrollOffset =
          ctx.cellPickerSelectedIndex - TuiContext.COMPLETION_MAX_HEIGHT + 1;
    }
  }

  void closeCellPicker() {
    ctx.cellPickerVisible = false;
    ctx.cellPickerEntries = null;
  }

  static void copyToClipboard(String text) {
    try {
      String[] cmd =
          switch (TuiContext.PLATFORM) {
            case MACOS -> new String[] {"pbcopy"};
            case WINDOWS -> new String[] {"clip"};
            case LINUX -> new String[] {"xclip", "-selection", "clipboard"};
          };
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      p.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
      p.getOutputStream().close();
      p.waitFor(2, TimeUnit.SECONDS);
    } catch (Exception ignore) {
    }
  }

  // ---- session picker ----

  void openSessionPicker() {
    @SuppressWarnings("unchecked")
    List<SessionManager.SessionRef<? extends Session>> all =
        (List<SessionManager.SessionRef<? extends Session>>) (List<?>) sessions.list();
    if (all.size() < 2) return;
    ctx.sessionPickerEntries = all;
    int currentId = sessions.current().map(r -> r.id).orElse(-1);
    ctx.sessionPickerSelectedIndex = 0;
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).id == currentId) {
        ctx.sessionPickerSelectedIndex = i;
        break;
      }
    }
    ctx.sessionPickerVisible = true;
  }

  void closeSessionPicker() {
    ctx.sessionPickerVisible = false;
    ctx.sessionPickerEntries = null;
  }

  // ---- export ----

  void exportActiveTab() {
    ResultTab tab = ctx.activeTab();
    if (tab.tableData == null || tab.tableData.isEmpty()) {
      ctx.showHintMessage("Nothing to export");
      return;
    }
    Path exportDir = Path.of(System.getProperty("user.home"), ".jfr-shell", "exports");
    String fileName = "export-" + LocalDateTime.now().format(EXPORT_TS) + ".csv";
    Path defaultPath = exportDir.resolve(fileName);
    ctx.exportPathState.clear();
    ctx.exportPathState.insert(defaultPath.toString());
    ctx.exportPathPristine = true;
    ctx.exportPopupVisible = true;
  }

  void performExport(String pathStr) {
    ResultTab tab = ctx.activeTab();
    if (tab.tableData == null || tab.tableData.isEmpty()) {
      ctx.showHintMessage("Nothing to export");
      return;
    }

    List<Map<String, Object>> exportData =
        (tab.cpAllEntries != null && !tab.cpAllEntries.isEmpty())
            ? tab.cpAllEntries
            : tab.tableData;
    List<String> headers =
        tab.tableHeaders != null ? tab.tableHeaders : new ArrayList<>(exportData.get(0).keySet());
    if (pathStr.startsWith("~/") || pathStr.equals("~")) {
      pathStr = System.getProperty("user.home") + pathStr.substring(1);
    }
    Path exportPath = Path.of(pathStr);

    try {
      Path parent = exportPath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException e) {
      ctx.showHintMessage("Export failed: " + e.getMessage());
      return;
    }

    try (BufferedWriter w = Files.newBufferedWriter(exportPath, StandardCharsets.UTF_8)) {
      w.write(csvLine(headers));
      w.newLine();
      for (Map<String, Object> row : exportData) {
        List<String> cells = new ArrayList<>(headers.size());
        for (String h : headers) {
          cells.add(csvCell(row.get(h)));
        }
        w.write(csvLine(cells));
        w.newLine();
      }
    } catch (IOException e) {
      ctx.showHintMessage("Export failed: " + e.getMessage());
      return;
    }

    ctx.showHintMessage("Exported " + exportData.size() + " rows to " + exportPath);
  }

  static String csvLine(List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(escapeCsv(values.get(i)));
    }
    return sb.toString();
  }

  static String csvCell(Object value) {
    if (value == null) return "";
    if (value instanceof Collection<?> coll) {
      StringBuilder sb = new StringBuilder();
      int idx = 0;
      for (Object item : coll) {
        if (idx++ > 0) sb.append(" | ");
        sb.append(item);
      }
      return sb.toString();
    }
    if (value instanceof ArrayType at) {
      Object arr = at.getArray();
      if (arr != null && arr.getClass().isArray()) return Arrays.deepToString(new Object[] {arr});
      return String.valueOf(arr);
    }
    if (value instanceof ComplexType ct) return String.valueOf(ct.getValue());
    if (value.getClass().isArray()) return Arrays.deepToString(new Object[] {value});
    return String.valueOf(value);
  }

  static String escapeCsv(String s) {
    if (s == null) return "";
    if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }

  /**
   * A PrintStream that routes stderr output to the TUI spinner and the ephemeral live-progress area
   * ({@code ctx.asyncProgressLines}). Progress lines are separate from {@code asyncOutputBuffer} so
   * they are shown live during execution but discarded after the command finishes, keeping the
   * final results area clean.
   *
   * <ul>
   *   <li>{@code \r}-terminated lines (in-place progress bar) replace the last live-progress line.
   *   <li>{@code \n}-terminated milestone lines are appended as permanent live-progress lines.
   * </ul>
   */
  private static final class ProgressCapturingStream extends PrintStream {
    // Matches logback pattern: "HH:mm:ss.SSS [thread] LEVEL ..."
    private static final java.util.regex.Pattern LOGBACK_LINE =
        java.util.regex.Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\[.+]\\s+\\w+\\s+.*");

    private final TuiContext ctx;
    private final StringBuilder currentLine = new StringBuilder();
    private boolean lastWasInPlace = false;

    ProgressCapturingStream(TuiContext ctx) {
      super(new ByteArrayOutputStream(0)); // dummy backing stream
      this.ctx = ctx;
    }

    @Override
    public void write(int b) {
      handleChar((char) b);
    }

    @Override
    public void write(byte[] buf, int off, int len) {
      String decoded = new String(buf, off, len, StandardCharsets.UTF_8);
      for (int i = 0; i < decoded.length(); i++) {
        handleChar(decoded.charAt(i));
      }
      String partial = currentLine.toString().trim();
      if (!partial.isEmpty()) {
        ctx.asyncProgressMessage = partial;
      }
    }

    // Override print/println to bypass PrintStream's internal textOut/charOut pipeline,
    // which may not route through write(byte[]) on all JDK versions.
    @Override
    public void print(String s) {
      handleString(s != null ? s : "null");
      // After each print(), flush whatever is still in currentLine as the live progress
      // message. This handles the \r-at-start protocol where the new content is written
      // AFTER \r, so it only becomes visible on the next \r otherwise.
      String partial = currentLine.toString().trim();
      if (!partial.isEmpty()) {
        ctx.asyncProgressMessage = partial;
      }
    }

    @Override
    public void print(char c) {
      handleChar(c);
    }

    @Override
    public void print(char[] s) {
      if (s != null) {
        for (char c : s) handleChar(c);
        String partial = currentLine.toString().trim();
        if (!partial.isEmpty()) {
          ctx.asyncProgressMessage = partial;
        }
      }
    }

    @Override
    public void println(String s) {
      handleString((s != null ? s : "null") + "\n");
    }

    @Override
    public void println() {
      handleChar('\n');
    }

    private void handleString(String s) {
      for (int i = 0; i < s.length(); i++) {
        handleChar(s.charAt(i));
      }
    }

    private void handleChar(char c) {
      if (c == '\r') {
        String progress = currentLine.toString().trim();
        if (!progress.isEmpty()) {
          ctx.asyncProgressMessage = progress;
          String line = "  " + progress + elapsedSuffix();
          if (lastWasInPlace && !ctx.asyncProgressLines.isEmpty()) {
            ctx.asyncProgressLines.set(ctx.asyncProgressLines.size() - 1, line);
          } else {
            ctx.asyncProgressLines.add(line);
          }
          lastWasInPlace = true;
        }
        currentLine.setLength(0);
      } else if (c == '\n') {
        String line = currentLine.toString().trim();
        if (!line.isEmpty() && !LOGBACK_LINE.matcher(line).matches()) {
          // Milestone message: update spinner only. Adding to asyncProgressLines would
          // duplicate the text that the spinner already shows on the same frame.
          ctx.asyncProgressMessage = line;
          lastWasInPlace = false;
        }
        currentLine.setLength(0);
      } else {
        currentLine.append(c);
      }
    }

    private String elapsedSuffix() {
      long elapsedSec = (System.currentTimeMillis() - ctx.commandStartTimeMs) / 1000;
      if (elapsedSec <= 0) return "";
      return String.format(" (%02d:%02d)", elapsedSec / 60, elapsedSec % 60);
    }

    @Override
    public void flush() {
      // no-op — progress is routed immediately
    }

    List<String> getOutputLines() {
      currentLine.setLength(0);
      return List.of(); // progress lines are in ctx.asyncProgressLines, not here
    }
  }
}

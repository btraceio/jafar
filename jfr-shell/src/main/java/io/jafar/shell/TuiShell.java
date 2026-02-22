package io.jafar.shell;

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
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.scrollbar.Scrollbar;
import dev.tamboui.widgets.scrollbar.ScrollbarState;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.cli.TuiTableRenderer;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.jafar.shell.providers.ConstantPoolProvider;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Full-screen TUI shell using TamboUI for rendering. Launched via {@code --tui} flag as an
 * alternative to the readline-based {@link Shell}.
 *
 * <p>Layout: status bar | results (with optional detail split) | command input | tips | hints.
 */
public final class TuiShell implements AutoCloseable {
  private static final int READ_EXPIRED = -2;
  private static final int EOF = -1;
  private static final Pattern ANSI_ESCAPE = Pattern.compile("\033\\[[0-9;]*[A-Za-z]");
  private static final Path HISTORY_PATH =
      Path.of(System.getProperty("user.home"), ".jfr-shell", "history");
  private static final int MAX_HISTORY = 5000;
  private static final int COMPLETION_MAX_WIDTH = 50;
  private static final int COMPLETION_MAX_HEIGHT = 12;
  private static final int TIP_ROTATE_TICKS = 300; // ~30s at 100ms per tick
  private static final String[] TIPS = loadTips();
  private static final String[] SPINNER = {
    "\u280B", "\u2819", "\u2839", "\u2838", "\u283C", "\u2834", "\u2826", "\u2827", "\u2807",
    "\u280F"
  };

  private static String[] loadTips() {
    try (var in = TuiShell.class.getResourceAsStream("/tips.txt")) {
      if (in == null) return new String[] {"Type 'help' for available commands"};
      String[] tips =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .lines()
              .map(String::trim)
              .filter(l -> !l.isEmpty() && !l.startsWith("#"))
              .map(l -> "Tip: " + l)
              .toArray(String[]::new);
      Collections.shuffle(Arrays.asList(tips));
      return tips;
    } catch (IOException e) {
      return new String[] {"Type 'help' for available commands"};
    }
  }

  private enum Platform {
    MACOS,
    LINUX,
    WINDOWS
  }

  private static Platform detectPlatform() {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac") || os.contains("darwin")) return Platform.MACOS;
    if (os.contains("win")) return Platform.WINDOWS;
    return Platform.LINUX;
  }

  private static final Platform PLATFORM = detectPlatform();

  private final Terminal jlineTerminal;
  private final JLineShellBackend backend;
  private final dev.tamboui.terminal.Terminal<JLineShellBackend> tuiTerminal;
  private final SessionManager sessions;
  private final CommandDispatcher dispatcher;

  private static final class ResultTab {
    String name;
    final List<String> lines = new ArrayList<>();
    int scrollOffset;
    int hScrollOffset;
    int maxLineWidth;
    boolean pinned;
    String searchQuery = "";
    String detailSearchQuery = "";
    List<Integer> filteredIndices; // null = no filter
    int filteredMaxLineWidth;
    List<Map<String, Object>> tableData; // null when output is not tabular
    List<String> tableHeaders; // column names when tableData is set
    int selectedRow = -1; // -1 = no selection
    int dataStartLine = -1; // line index in lines where data rows begin
    List<Map<String, Object>>
        metadataClassCache; // metadata per row for drill-down (parallel to tableData)
    int sortColumn = -1; // -1 = no sort, 0..N = column index
    boolean sortAscending = true;
    long marqueeTick0; // renderTick when name was set (marquee epoch)
    int cpTypeIndex = -1; // sidebar selection index for browser tabs (-1 = not a browser tab)
    List<Map<String, Object>> browserTypes; // sidebar type list for this browser tab
    boolean isEventBrowserTab; // true if this tab was opened in event browser mode

    // Paginated rendering — render only a page of rows at a time
    List<Map<String, Object>> cpAllEntries; // full entries from provider (null for non-CP)
    List<String> cpColumnHeaders; // column names for manual row formatting
    int[] cpColumnWidths; // column widths for manual row formatting
    int cpRenderedCount; // entries rendered into lines/tableData so far

    ResultTab(String name) {
      this.name = name;
    }
  }

  private final List<ResultTab> tabs = new ArrayList<>();
  private int activeTabIndex;

  private final List<String> commandHistory = new ArrayList<>();
  private final TextInputState inputState = new TextInputState();
  private final TextInputState searchInputState = new TextInputState();

  private enum Focus {
    INPUT,
    RESULTS,
    DETAIL,
    SEARCH,
    HISTORY_SEARCH
  }

  private int historyIndex = -1;
  private boolean running = true;
  private int resultsAreaHeight = 10; // updated each render cycle
  private int detailAreaHeight = 10; // updated each render cycle
  private Focus focus = Focus.INPUT;
  private String lastCommand = "";
  private int activeDetailTabIndex = 0;
  private List<String> detailTabNames = List.of();
  private List<Object> detailTabValues = List.of();
  private final List<Integer> detailTabScrollOffsets = new ArrayList<>();
  private int detailCursorLine = -1; // selected line in detail pane (-1 = no cursor)
  private int detailHScrollOffset; // horizontal scroll offset for detail pane
  private List<String> detailLineTypeRefs; // type name for each detail line (null = not navigable)

  // Marquee animation for long tab titles
  private long renderTick;
  private long detailMarqueeTick0;
  private static final int MAX_TAB_TITLE_WIDTH = 25;
  private static final int PAUSE_START_TICKS = 50;
  private static final int PAUSE_END_TICKS = 20;
  private static final int SCROLL_SPEED = 2;

  // Search origin tracking
  private Focus searchOriginFocus = Focus.RESULTS;
  private boolean searchOriginCpTypes; // true when search targets the browser types sidebar

  // Browser types sidebar filter
  private String cpTypesSearchQuery = "";
  private List<Integer> cpTypesFilteredIndices; // null = no filter

  // Temporary hint message (auto-dismiss after HINT_MESSAGE_TICKS)
  private String hintMessage;
  private long hintMessageTick;
  private static final int HINT_MESSAGE_TICKS = 50; // ~5s at 100ms per tick

  // History search state
  private String historySearchQuery = "";
  private int historySearchIndex = -1;
  private String historySearchSavedInput = "";
  private boolean historySearchFailing; // true when query has no match

  // Completion state
  private ShellCompleter completer;
  private int completionWordStart;
  private String completionOriginalWord;
  private List<Candidate> completionAllCandidates;
  private List<Candidate> completionFiltered;
  private int completionSelectedIndex;
  private int completionScrollOffset;
  private boolean completionPopupVisible;
  private String completionOriginalInput;
  private Rect inputAreaRect;

  // Cell picker state
  private boolean cellPickerVisible;
  private List<String[]> cellPickerEntries; // [0]=column name, [1]=full value
  private int cellPickerSelectedIndex;
  private int cellPickerScrollOffset;

  // Session picker state
  private boolean sessionPickerVisible;
  private List<SessionManager.SessionRef> sessionPickerEntries;
  private int sessionPickerSelectedIndex;

  // Export popup state
  private boolean exportPopupVisible;
  private boolean exportPathPristine; // true until user edits; first char replaces all
  private final TextInputState exportPathState = new TextInputState();

  // Browser mode (shared by CP browser and event browser)
  private boolean cpBrowserMode;
  private boolean eventBrowserMode; // true when browsing events (vs CP)
  private List<Map<String, Object>> cpTypes;
  private int cpTypeSelectedIndex;
  private int cpTypeScrollOffset;
  private int cpTypesAreaHeight;

  private boolean cpTypesFocused;
  private long browserNavTime; // nanoTime of last Up/Down in sidebar
  private String browserNavPending; // type name awaiting load (null = none)
  private boolean browserNavKeepFocus; // keepTypesFocused for pending load
  private static final long BROWSER_NAV_DEBOUNCE_NS = 100_000_000L; // 100ms

  // Async command execution state
  private final ExecutorService commandExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "tui-command");
            t.setDaemon(true);
            return t;
          });
  private volatile boolean commandRunning;
  private long commandStartTick;
  private Future<?> commandFuture;
  private List<String> asyncOutputBuffer;
  private int asyncMaxLineWidth;
  private int asyncLinesBeforeDispatch;
  private volatile List<Map<String, Object>> asyncTableData;
  private volatile List<String> asyncTableHeaders;
  private volatile List<Map<String, Object>> asyncMetadataClasses;
  private volatile boolean eventBrowserPending; // async event summary → enter event browser

  public TuiShell() throws IOException {
    // Disable pager — TUI mode uses scrollable view buffer instead.
    System.setProperty("jfr.shell.pager", "off");

    this.jlineTerminal = TerminalBuilder.builder().system(true).build();
    this.backend = new JLineShellBackend(jlineTerminal);
    this.tuiTerminal = new dev.tamboui.terminal.Terminal<>(backend);

    ParsingContext ctx = ParsingContext.create();
    this.sessions = new SessionManager(ctx, (path, c) -> new JFRSession(path, c));
    this.dispatcher =
        new CommandDispatcher(
            sessions,
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
            },
            current -> {
              // Force TUI output format for all sessions opened in TUI mode.
              if (current != null) {
                current.outputFormat = "tui";
              }
            });
    this.completer = new ShellCompleter(sessions, dispatcher);
    loadHistory();
    tabs.add(new ResultTab("jfr>"));
    activeTabIndex = 0;
  }

  /** Pre-open a JFR recording if the path exists. */
  public void openIfPresent(Path jfrPath) {
    if (jfrPath == null || !Files.exists(jfrPath)) return;
    try {
      dispatcher.dispatch("open " + jfrPath);
    } catch (Exception ignore) {
    }
  }

  /** Enter full-screen mode and run the draw/event loop until exit. */
  public void run() throws IOException {
    backend.enterAlternateScreen();
    backend.enableRawMode();
    backend.hideCursor();

    try {
      while (running) {
        // Check if async command has finished
        if (commandFuture != null && commandFuture.isDone()) {
          finishAsyncCommand();
        }

        // Flush debounced browser navigation if idle long enough
        if (browserNavPending != null
            && System.nanoTime() - browserNavTime >= BROWSER_NAV_DEBOUNCE_NS) {
          String pending = browserNavPending;
          boolean keep = browserNavKeepFocus;
          browserNavPending = null;
          loadBrowserEntries(pending, keep);
        }

        tuiTerminal.draw(this::render);
        backend.showCursor();

        int key = backend.read(100);
        if (key == READ_EXPIRED) continue;
        if (key == EOF) {
          running = false;
          continue;
        }
        // Ignore keystrokes while a command is running
        if (commandRunning) continue;
        handleKey(key);
      }
    } finally {
      backend.showCursor();
      backend.disableRawMode();
      backend.leaveAlternateScreen();
    }
  }

  // ---- output capture ----

  private void addOutputLine(String s) {
    String stripped = stripAnsi(s);
    // A single println may contain embedded newlines; split to keep line count accurate.
    for (String line : stripped.split("\n", -1)) {
      String indented = "  " + line;
      // During async execution, write to the buffer instead of the active tab
      if (asyncOutputBuffer != null) {
        asyncOutputBuffer.add(indented);
        asyncMaxLineWidth = Math.max(asyncMaxLineWidth, indented.length());
      } else {
        ResultTab tab = tabs.get(activeTabIndex);
        tab.lines.add(indented);
        tab.maxLineWidth = Math.max(tab.maxLineWidth, indented.length());
      }
    }
  }

  private static String stripAnsi(String s) {
    return ANSI_ESCAPE.matcher(s).replaceAll("");
  }

  // ---- search / filter ----

  private void applySearchFilter(ResultTab tab, String query) {
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

  private static int effectiveLineCount(ResultTab tab) {
    return tab.filteredIndices != null ? tab.filteredIndices.size() : tab.lines.size();
  }

  private static String effectiveLine(ResultTab tab, int i) {
    if (tab.filteredIndices != null) {
      return tab.lines.get(tab.filteredIndices.get(i));
    }
    return tab.lines.get(i);
  }

  private static int effectiveMaxLineWidth(ResultTab tab) {
    return tab.filteredIndices != null ? tab.filteredMaxLineWidth : tab.maxLineWidth;
  }

  // ---- rendering ----

  private boolean showTabBar() {
    if (tabs.size() >= 2) return true;
    for (int i = 0; i < tabs.size(); i++) {
      if (tabs.get(i).pinned) return true;
    }
    return false;
  }

  private void render(Frame frame) {
    renderTick++;
    List<Constraint> constraints = new ArrayList<>();
    constraints.add(Constraint.length(1)); // status bar
    constraints.add(Constraint.fill()); // results
    if (focus == Focus.SEARCH) constraints.add(Constraint.length(1)); // search bar
    if (focus == Focus.HISTORY_SEARCH) constraints.add(Constraint.length(1)); // history search bar
    constraints.add(Constraint.length(3)); // input
    constraints.add(Constraint.length(1)); // tips
    constraints.add(Constraint.length(1)); // hints

    List<Rect> areas =
        Layout.vertical().constraints(constraints.toArray(new Constraint[0])).split(frame.area());

    int idx = 0;
    renderStatusBar(frame, areas.get(idx++));

    Rect resultsRect = areas.get(idx++);
    ResultTab activeTab = tabs.get(activeTabIndex);
    if (!detailTabNames.isEmpty()
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

    if (focus == Focus.SEARCH) renderSearchBar(frame, areas.get(idx++));
    if (focus == Focus.HISTORY_SEARCH) renderHistorySearchBar(frame, areas.get(idx++));
    Rect inputRect = areas.get(idx++);
    inputAreaRect = inputRect;
    renderInput(frame, inputRect);
    renderTipLine(frame, areas.get(idx++));
    renderHints(frame, areas.get(idx));

    // Render completion popup overlay (after all other rendering so it appears on top)
    if (completionPopupVisible && completionFiltered != null && !completionFiltered.isEmpty()) {
      renderCompletionPopup(frame);
    }
    if (cellPickerVisible && cellPickerEntries != null && !cellPickerEntries.isEmpty()) {
      renderCellPicker(frame);
    }
    if (sessionPickerVisible && sessionPickerEntries != null && !sessionPickerEntries.isEmpty()) {
      renderSessionPicker(frame);
    }
    if (exportPopupVisible) {
      renderExportPopup(frame);
    }
  }

  private static String marqueeTitle(String fullTitle, long tick) {
    int len = fullTitle.length();
    if (len <= MAX_TAB_TITLE_WIDTH) return fullTitle;
    int scrollDistance = len - MAX_TAB_TITLE_WIDTH;
    int scrollTicks = scrollDistance * SCROLL_SPEED;
    int cycleLength = PAUSE_START_TICKS + scrollTicks + PAUSE_END_TICKS;
    int tickInCycle = (int) (tick % cycleLength);

    int offset;
    if (tickInCycle < PAUSE_START_TICKS) {
      offset = 0;
    } else if (tickInCycle < PAUSE_START_TICKS + scrollTicks) {
      offset = (tickInCycle - PAUSE_START_TICKS) / SCROLL_SPEED;
    } else {
      offset = scrollDistance;
    }

    // Reserve 1 char for ellipsis indicators
    boolean showLeadingEllipsis = offset > 0;
    boolean showTrailingEllipsis = offset < scrollDistance;

    if (showLeadingEllipsis && showTrailingEllipsis) {
      return "\u2026"
          + fullTitle.substring(offset + 1, offset + MAX_TAB_TITLE_WIDTH - 1)
          + "\u2026";
    } else if (showLeadingEllipsis) {
      return "\u2026" + fullTitle.substring(offset + 1, offset + MAX_TAB_TITLE_WIDTH);
    } else if (showTrailingEllipsis) {
      return fullTitle.substring(offset, offset + MAX_TAB_TITLE_WIDTH - 1) + "\u2026";
    } else {
      return fullTitle.substring(offset, offset + MAX_TAB_TITLE_WIDTH);
    }
  }

  private void renderResultTabBar(Frame frame, Rect area) {
    String[] titles = new String[tabs.size()];
    for (int i = 0; i < tabs.size(); i++) {
      ResultTab tab = tabs.get(i);
      String prefix = tab.pinned ? "\uD83D\uDCCC " : "\u25B7 ";
      titles[i] = prefix + marqueeTitle(tab.name, renderTick - tab.marqueeTick0);
    }
    Tabs tabsWidget =
        Tabs.builder()
            .titles(titles)
            .highlightStyle(Style.create().bold().fg(Color.CYAN))
            .divider(" | ")
            .style(Style.create().bg(Color.DARK_GRAY).fg(Color.WHITE))
            .build();
    TabsState tabsState = new TabsState(activeTabIndex);
    frame.renderStatefulWidget(tabsWidget, area, tabsState);
  }

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

    String altMod = (PLATFORM == Platform.MACOS) ? "Opt" : "Alt";
    String sessionHint = "";
    if (sessions.list().size() > 1) {
      sessionHint = " | " + altMod + "+s:switch";
    }

    String status = " JFR Shell TUI" + sessionInfo + backendName + sessionHint;
    // Pad to fill the entire status bar width
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

  private void renderResults(Frame frame, Rect area) {
    ResultTab activeTab = tabs.get(activeTabIndex);

    String title = "Results";
    if (activeTab.tableData != null) {
      if (activeTab.filteredIndices != null) {
        int filteredPos = 0;
        if (activeTab.dataStartLine >= 0) {
          int selLineIdx = activeTab.dataStartLine + activeTab.selectedRow;
          int idx = activeTab.filteredIndices.indexOf(selLineIdx);
          filteredPos = idx >= 0 ? idx + 1 : 0;
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
      int lineCount0 = effectiveLineCount(activeTab);
      if (lineCount0 > 0) {
        int scrollOff = activeTab.scrollOffset;
        int endLine = Math.min(scrollOff + resultsAreaHeight, lineCount0);
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
    // Show live search query in title when searching results (non-browser mode only)
    if (!cpBrowserMode
        && focus == Focus.SEARCH
        && searchOriginFocus == Focus.RESULTS
        && !searchOriginCpTypes) {
      String q = searchInputState.text();
      if (!q.isEmpty()) {
        title += " /" + q;
      }
    } else if (!cpBrowserMode && !activeTab.searchQuery.isEmpty() && focus != Focus.SEARCH) {
      title += " /" + activeTab.searchQuery;
    }
    Title blockTitle = mnemonicTitle(title, 0);

    Block.Builder blockBuilder =
        Block.builder().title(blockTitle).borders(Borders.ALL).borderType(BorderType.ROUNDED);
    if (focus == Focus.RESULTS && !cpBrowserMode) {
      blockBuilder.borderColor(Color.CYAN);
    }
    if (!cpBrowserMode
        && focus == Focus.SEARCH
        && searchOriginFocus == Focus.RESULTS
        && !searchOriginCpTypes) {
      blockBuilder.borderColor(Color.YELLOW);
    }
    if (!cpBrowserMode && activeTab.filteredIndices != null && !activeTab.searchQuery.isEmpty()) {
      blockBuilder.titleBottom(
          Title.from(
              Span.styled(
                  " filter: " + activeTab.searchQuery + " ",
                  Style.create().fg(Color.YELLOW).italic())));
    }
    Block block = blockBuilder.build();

    Rect inner = block.inner(area);
    frame.renderWidget(block, area);

    // Render result sub-tabs inside the block when multiple tabs exist
    if (showTabBar()) {
      List<Rect> tabSplit =
          Layout.vertical().constraints(Constraint.length(1), Constraint.fill()).split(inner);
      renderResultTabBar(frame, tabSplit.get(0));
      inner = tabSplit.get(1);
    }

    // Browser mode: split inner area for types sidebar + entries sub-block
    if (cpBrowserMode) {
      List<Rect> hSplit =
          Layout.horizontal()
              .constraints(Constraint.percentage(30), Constraint.percentage(70))
              .split(inner);
      renderCpTypes(frame, hSplit.get(0));

      String entriesTitle = getSelectedCpTypeName();
      boolean entriesSearchActive =
          focus == Focus.SEARCH && searchOriginFocus == Focus.RESULTS && !searchOriginCpTypes;
      // Show live filter in entries title
      if (entriesSearchActive) {
        String q = searchInputState.text();
        if (!q.isEmpty()) entriesTitle += " /" + q;
      } else if (!activeTab.searchQuery.isEmpty()) {
        entriesTitle += " /" + activeTab.searchQuery;
      }
      Block.Builder cpEntriesBuilder =
          Block.builder()
              .title(Title.from(entriesTitle))
              .borders(Borders.ALL)
              .borderType(BorderType.ROUNDED);
      if (focus == Focus.RESULTS && !cpTypesFocused) {
        cpEntriesBuilder.borderColor(Color.CYAN);
      }
      if (entriesSearchActive) {
        cpEntriesBuilder.borderColor(Color.YELLOW);
      }
      Block cpEntriesBlock = cpEntriesBuilder.build();
      inner = cpEntriesBlock.inner(hSplit.get(1));
      frame.renderWidget(cpEntriesBlock, hSplit.get(1));
    }

    // Show spinner while a command is running (after a short delay to avoid flicker)
    if (commandRunning && renderTick - commandStartTick > 1) {
      int spinIdx = (int) (renderTick % SPINNER.length);
      Paragraph spinner = Paragraph.from("  " + SPINNER[spinIdx] + " Running...");
      frame.renderWidget(spinner, inner);
      resultsAreaHeight = inner.height();
      return;
    }

    int lineCount = effectiveLineCount(activeTab);
    int maxWidth = effectiveMaxLineWidth(activeTab);

    if (lineCount == 0) {
      resultsAreaHeight = inner.height();
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

    // Determine which scrollbars are needed.
    boolean needsVScroll = lineCount > inner.height();
    boolean needsHScroll = maxWidth > inner.width();

    // Carve out scrollbar areas from the inner rect.
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

    // Clamp horizontal scroll offset.
    int maxHScroll = Math.max(0, maxWidth - visibleWidth);
    activeTab.hScrollOffset = Math.min(activeTab.hScrollOffset, maxHScroll);

    // Sticky header: when table data is present and no filter is active, split off 1 line
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
      resultsAreaHeight = 1;
      return;
    }
    resultsAreaHeight = visibleHeight;

    // Render sticky header if present
    if (headerArea != null) {
      Style headerStyle = Style.create().bold().fg(Color.CYAN);
      String headerText = effectiveLine(activeTab, headerLine);
      // Inject sort indicator into the header
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

    // Determine line range and scroll offset for the scrollable area
    int scrollLineCount;
    int scrollBase;
    int highlightLine;
    // Line index of the selected row (for highlight in filtered mode)
    int selectedLineIdx =
        activeTab.tableData != null && activeTab.selectedRow >= 0 && activeTab.dataStartLine >= 0
            ? activeTab.dataStartLine + activeTab.selectedRow
            : -1;
    if (hasTable) {
      // Show only data rows + footer in scrollable area
      scrollBase = activeTab.dataStartLine;
      scrollLineCount = Math.max(0, lineCount - scrollBase);
      highlightLine =
          activeTab.selectedRow >= 0 ? activeTab.selectedRow : -1; // relative to scrollBase
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
    List<Line> styledLines = new ArrayList<>(end - start);
    for (int i = start; i < end; i++) {
      String line = effectiveLine(activeTab, scrollBase + i);
      String visible = applyHScroll(line, activeTab.hScrollOffset, visibleWidth);
      // Highlight: either the table row (unfiltered) or the matching filtered line
      boolean isHighlighted = (i == highlightLine);
      if (!isHighlighted && activeTab.filteredIndices != null && selectedLineIdx >= 0) {
        int actualLineIdx = activeTab.filteredIndices.get(i);
        isHighlighted = (actualLineIdx == selectedLineIdx);
      }
      if (isHighlighted) {
        // Pad to fill the visible width so highlight spans the entire row
        if (visible.length() < visibleWidth) {
          visible = visible + " ".repeat(visibleWidth - visible.length());
        }
        styledLines.add(Line.from(Span.styled(visible, highlightStyle)));
      } else if (!activeTab.searchQuery.isEmpty()) {
        styledLines.add(buildHighlightedLine(visible, activeTab.searchQuery, null));
      } else {
        styledLines.add(Line.from(visible));
      }
    }
    Paragraph results = Paragraph.builder().text(new Text(styledLines, null)).build();
    frame.renderWidget(results, contentArea);

    // Vertical scrollbar
    if (vScrollbarArea != null) {
      ScrollbarState vState =
          new ScrollbarState(scrollLineCount)
              .viewportContentLength(visibleHeight)
              .position(activeTab.scrollOffset);
      frame.renderStatefulWidget(Scrollbar.vertical(), vScrollbarArea, vState);
    }

    // Horizontal scrollbar
    if (hScrollbarArea != null) {
      ScrollbarState hState =
          new ScrollbarState(maxWidth)
              .viewportContentLength(visibleWidth)
              .position(activeTab.hScrollOffset);
      frame.renderStatefulWidget(Scrollbar.horizontal(), hScrollbarArea, hState);
    }
  }

  private void renderCpTypes(Frame frame, Rect area) {
    String sidebarTitle = eventBrowserMode ? "Event Types" : "Constant Types";
    // Show live or sticky filter in sidebar title
    if (focus == Focus.SEARCH && searchOriginCpTypes) {
      String q = searchInputState.text();
      if (!q.isEmpty()) sidebarTitle += " /" + q;
    } else if (!cpTypesSearchQuery.isEmpty()) {
      sidebarTitle += " /" + cpTypesSearchQuery;
    }
    Block.Builder blockBuilder =
        Block.builder()
            .title(Title.from(sidebarTitle))
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED);
    if (focus == Focus.RESULTS && cpTypesFocused) {
      blockBuilder.borderColor(Color.CYAN);
    }
    if (focus == Focus.SEARCH && searchOriginCpTypes) {
      blockBuilder.borderColor(Color.YELLOW);
    }
    Block block = blockBuilder.build();
    Rect inner = block.inner(area);
    frame.renderWidget(block, area);

    if (cpTypes == null || cpTypes.isEmpty()) return;

    // Determine visible type indices (filtered or all)
    int totalVisible =
        cpTypesFilteredIndices != null ? cpTypesFilteredIndices.size() : cpTypes.size();

    // Scrollbar
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
    cpTypesAreaHeight = Math.max(1, visibleHeight);

    // Clamp scroll
    int maxScroll = Math.max(0, totalVisible - visibleHeight);
    cpTypeScrollOffset = Math.min(cpTypeScrollOffset, maxScroll);

    int start = cpTypeScrollOffset;
    int end = Math.min(start + visibleHeight, totalVisible);

    Style highlightStyle = Style.create().reversed();
    List<Line> lines = new ArrayList<>(end - start);
    for (int vi = start; vi < end; vi++) {
      int i = cpTypesFilteredIndices != null ? cpTypesFilteredIndices.get(vi) : vi;
      Map<String, Object> typeRow = cpTypes.get(i);
      String name = String.valueOf(typeRow.getOrDefault("name", ""));
      String countKey = eventBrowserMode ? "count" : "totalSize";
      String count = String.valueOf(typeRow.getOrDefault(countKey, ""));
      String display = " " + name + " (" + count + ")";
      if (display.length() > contentArea.width()) {
        display = display.substring(0, contentArea.width());
      }
      if (i == cpTypeSelectedIndex) {
        if (display.length() < contentArea.width()) {
          display = display + " ".repeat(contentArea.width() - display.length());
        }
        lines.add(Line.from(Span.styled(display, highlightStyle)));
      } else if (!cpTypesSearchQuery.isEmpty()) {
        lines.add(buildHighlightedLine(display, cpTypesSearchQuery, null));
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
              .position(cpTypeScrollOffset);
      frame.renderStatefulWidget(Scrollbar.vertical(), vScrollbarArea, vState);
    }
  }

  private void renderDetailSection(Frame frame, Rect area) {
    if (detailTabNames.isEmpty()) return;

    // Render the detail content block (which includes the D̲etails title)
    renderDetailContent(frame, area);
  }

  private void renderDetailTabBar(Frame frame, Rect area) {
    String[] titles = new String[detailTabNames.size()];
    for (int i = 0; i < detailTabNames.size(); i++) {
      titles[i] = marqueeTitle(detailTabNames.get(i), renderTick - detailMarqueeTick0);
    }
    Tabs detailTabs =
        Tabs.builder()
            .titles(titles)
            .highlightStyle(Style.create().bold().fg(Color.YELLOW))
            .divider(" | ")
            .style(
                Style.create()
                    .bg(focus == Focus.DETAIL ? Color.DARK_GRAY : Color.BLACK)
                    .fg(Color.WHITE))
            .build();
    TabsState detailTabsState = new TabsState(activeDetailTabIndex);
    frame.renderStatefulWidget(detailTabs, area, detailTabsState);
  }

  private void renderDetailContent(Frame frame, Rect area) {
    if (activeDetailTabIndex >= detailTabValues.size()) return;

    ResultTab activeTab = tabs.get(activeTabIndex);
    String detailTitle = "Details";
    if (focus == Focus.SEARCH && searchOriginFocus == Focus.DETAIL) {
      String q = searchInputState.text();
      if (!q.isEmpty()) {
        detailTitle += " /" + q;
      }
    } else if (activeTab.detailSearchQuery != null
        && !activeTab.detailSearchQuery.isEmpty()
        && focus != Focus.SEARCH) {
      detailTitle += " /" + activeTab.detailSearchQuery;
    }
    Block.Builder blockBuilder =
        Block.builder()
            .title(mnemonicTitle(detailTitle, 0))
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED);
    if (focus == Focus.DETAIL) {
      blockBuilder.borderColor(Color.CYAN);
    }
    if (focus == Focus.SEARCH && searchOriginFocus == Focus.DETAIL) {
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

    // Render detail sub-tabs inside the block (always show tab name)
    if (!detailTabNames.isEmpty()) {
      List<Rect> tabSplit =
          Layout.vertical().constraints(Constraint.length(1), Constraint.fill()).split(inner);
      renderDetailTabBar(frame, tabSplit.get(0));
      inner = tabSplit.get(1);
    }

    Object tabValue = detailTabValues.get(activeDetailTabIndex);
    boolean metadataMode = activeTab.metadataClassCache != null && tabValue instanceof Map<?, ?>;

    String[] allLines;
    if (metadataMode) {
      @SuppressWarnings("unchecked")
      Map<String, Object> meta = (Map<String, Object>) tabValue;
      allLines = buildMetadataDetailLines(meta, activeTab);
    } else {
      allLines = buildDetailLines(tabValue, detailTabNames.get(activeDetailTabIndex));
    }

    // Apply detail-specific search filter if active
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

    // Determine if vertical scrollbar is needed
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
    detailAreaHeight = Math.max(1, visibleHeight);
    int scrollOffset = getDetailScrollOffset();
    int maxScroll = Math.max(0, totalLines - visibleHeight);
    scrollOffset = Math.min(scrollOffset, maxScroll);
    setDetailScrollOffset(scrollOffset);

    int start = scrollOffset;
    int end = Math.min(start + visibleHeight, totalLines);

    boolean showCursor = metadataMode && focus == Focus.DETAIL && detailCursorLine >= 0;
    Style cursorStyle = Style.create().reversed();
    Style navigableStyle = Style.create().fg(Color.CYAN).bold();

    List<Line> styledLines = new ArrayList<>(end - start);
    int detailWidth = contentArea.width();
    for (int i = start; i < end; i++) {
      String line = applyHScroll(detailLines[i], detailHScrollOffset, detailWidth);
      if (showCursor && i == detailCursorLine) {
        // Pad to full width for highlight
        if (line.length() < detailWidth) {
          line = line + " ".repeat(detailWidth - line.length());
        }
        styledLines.add(Line.from(Span.styled(line, cursorStyle)));
      } else if (metadataMode
          && detailLineTypeRefs != null
          && i < detailLineTypeRefs.size()
          && detailLineTypeRefs.get(i) != null) {
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

  private String[] buildDetailLines(Object value, String tabName) {
    // Stack trace rendering — flat list with tree connectors
    if (value instanceof Map<?, ?> m && m.containsKey("frames")) {
      return buildStackTraceLines(m);
    }
    // "frames" tab from CP entries: value is the array itself, not a wrapping Map
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
    // Also render the truncated flag if present
    Object truncated = stackMap.get("truncated");
    if (truncated != null && Boolean.TRUE.equals(truncated)) {
      lines.add("   ... (truncated)");
    }
    return lines.toArray(new String[0]);
  }

  /** Unwrap ArrayType / ComplexType for display. */
  private static Object resolveForDisplay(Object val) {
    if (val instanceof ComplexType ct) return ct.getValue();
    if (val instanceof ArrayType at) return at.getArray();
    return val;
  }

  /** Render a Map as a tree with box-drawing connectors. */
  private static void formatTree(List<String> lines, Map<?, ?> map, String indent) {
    var entries = new ArrayList<>(map.entrySet());
    for (int i = 0; i < entries.size(); i++) {
      Map.Entry<?, ?> entry = entries.get(i);
      boolean last = (i == entries.size() - 1);
      String connector = last ? "\u2514\u2500 " : "\u251C\u2500 ";
      String childIndent = indent + (last ? "   " : "\u2502  ");
      String key = String.valueOf(entry.getKey());
      Object val = resolveForDisplay(entry.getValue());
      // Unwrap single-entry map wrappers
      while (val instanceof Map<?, ?> inner && inner.size() == 1) {
        val = resolveForDisplay(inner.values().iterator().next());
      }
      if (val instanceof Map<?, ?> nested && nested.size() > 1) {
        lines.add(indent + connector + key);
        formatTree(lines, nested, childIndent);
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

  private static void formatArrayTree(List<String> lines, Object arr, String indent) {
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

  private static void formatCollectionTree(List<String> lines, Collection<?> coll, String indent) {
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

  private int getDetailScrollOffset() {
    if (activeDetailTabIndex < detailTabScrollOffsets.size()) {
      return detailTabScrollOffsets.get(activeDetailTabIndex);
    }
    return 0;
  }

  private void setDetailScrollOffset(int offset) {
    while (detailTabScrollOffsets.size() <= activeDetailTabIndex) {
      detailTabScrollOffsets.add(0);
    }
    detailTabScrollOffsets.set(activeDetailTabIndex, offset);
  }

  private void buildDetailTabs(ResultTab tab) {
    detailHScrollOffset = 0;
    if (tab.tableData == null || tab.selectedRow < 0 || tab.selectedRow >= tab.tableData.size()) {
      detailTabNames = List.of();
      detailTabValues = List.of();
      activeDetailTabIndex = 0;
      detailTabScrollOffsets.clear();
      detailCursorLine = -1;
      detailLineTypeRefs = null;
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
        detailTabNames = List.of(typeName);
        detailMarqueeTick0 = renderTick;
        detailTabValues = List.of(meta);
        activeDetailTabIndex = 0;
        detailTabScrollOffsets.clear();
        detailTabScrollOffsets.add(0);
        buildMetadataDetailRefs(meta, tab);
        return;
      }
      // No metadata for this row — fall through to empty detail
      detailTabNames = List.of();
      detailTabValues = List.of();
      activeDetailTabIndex = 0;
      detailTabScrollOffsets.clear();
      detailCursorLine = -1;
      detailLineTypeRefs = null;
      return;
    }

    detailCursorLine = -1;
    detailLineTypeRefs = null;

    Map<String, Object> row = tab.tableData.get(tab.selectedRow);
    List<String> names = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    List<String> headers =
        tab.tableHeaders != null ? tab.tableHeaders : new ArrayList<>(row.keySet());
    for (String header : headers) {
      Object val = row.get(header);
      if (isComplexValue(val)) {
        names.add(header);
        values.add(val);
      }
    }
    detailTabNames = names;
    detailMarqueeTick0 = renderTick;
    detailTabValues = values;
    activeDetailTabIndex = Math.min(activeDetailTabIndex, Math.max(0, names.size() - 1));
    detailTabScrollOffsets.clear();
    for (int i = 0; i < names.size(); i++) {
      detailTabScrollOffsets.add(0);
    }
  }

  /**
   * Build detail line type references for metadata mode. Populates {@link #detailLineTypeRefs} with
   * the type name for each detail line (null if not navigable). Resets {@link #detailCursorLine}.
   */
  @SuppressWarnings("unchecked")
  private void buildMetadataDetailRefs(Map<String, Object> meta, ResultTab tab) {
    // Collect navigable type names (types that exist in the master table)
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
      refs.add(null); // "Fields (N):" header line
      int idx = 0;
      int size = fbm.size();
      for (Map.Entry<?, ?> entry : fbm.entrySet()) {
        idx++;
        String typeName = null;
        if (entry.getValue() instanceof Map<?, ?> fm) {
          Object typeObj = fm.get("type");
          if (typeObj != null) {
            typeName = typeObj.toString();
          }
        }
        // Only mark as navigable if the type exists in the master table
        refs.add(typeName != null && navigableTypes.contains(typeName) ? typeName : null);
      }
    }

    // Settings section
    Object settingsByName = meta.get("settingsByName");
    if (settingsByName instanceof Map<?, ?> sbm && !sbm.isEmpty()) {
      refs.add(null); // "Settings (N):" header line
      for (int i = 0; i < sbm.size(); i++) {
        refs.add(null); // settings are not navigable
      }
    }

    // Annotations section
    Object classAnnotations = meta.get("classAnnotations");
    if (classAnnotations instanceof List<?> ca && !ca.isEmpty()) {
      refs.add(null); // "Annotations (N):" header line
      for (int i = 0; i < ca.size(); i++) {
        refs.add(null);
      }
    }

    detailLineTypeRefs = refs;
    detailCursorLine = refs.isEmpty() ? -1 : 0;
  }

  /**
   * Build detail display lines for a metadata map. Structure matches the indices in {@link
   * #detailLineTypeRefs}.
   */
  @SuppressWarnings("unchecked")
  private String[] buildMetadataDetailLines(Map<String, Object> meta, ResultTab tab) {
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

  private static boolean isComplexValue(Object val) {
    if (val == null) return false;
    if (val instanceof ComplexType ct) return isComplexValue(ct.getValue());
    if (val instanceof Map<?, ?> m) {
      if (m.size() <= 1) return false; // single-entry wrapper, stays in table only
      return true;
    }
    if (val instanceof ArrayType) return true;
    if (val instanceof Collection<?>) return true;
    if (val.getClass().isArray()) return true;
    return false;
  }

  private void renderInput(Frame frame, Rect area) {
    Block.Builder inputBlockBuilder =
        Block.builder()
            .title(mnemonicTitle("Command >", 0))
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED);
    if (focus == Focus.INPUT) {
      inputBlockBuilder.borderColor(Color.CYAN);
    }
    TextInput textInput = TextInput.builder().block(inputBlockBuilder.build()).build();
    textInput.renderWithCursor(area, frame.buffer(), inputState, frame);
  }

  private void renderSearchBar(Frame frame, Rect area) {
    String prefix = searchOriginFocus == Focus.DETAIL ? "/detail " : "/ ";
    String text = prefix + searchInputState.text();
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
    String matched = inputState.text();
    String prefix = historySearchFailing ? "(failing reverse-i-search) " : "(reverse-i-search) ";
    String text = prefix + historySearchQuery + ": " + matched;
    if (text.length() < area.width()) {
      text = text + " ".repeat(area.width() - text.length());
    }
    Style style =
        historySearchFailing
            ? Style.create().bg(Color.DARK_GRAY).fg(Color.RED)
            : Style.create().bg(Color.DARK_GRAY).fg(Color.YELLOW);
    Paragraph bar = Paragraph.builder().text(Text.raw(text)).style(style).build();
    frame.renderWidget(bar, area);
  }

  private void showHintMessage(String message) {
    hintMessage = message;
    hintMessageTick = renderTick;
  }

  private void renderTipLine(Frame frame, Rect area) {
    int tipIndex = (int) ((renderTick / TIP_ROTATE_TICKS) % TIPS.length);
    String tip = TIPS[tipIndex];
    Paragraph bar =
        Paragraph.builder()
            .text(Text.from(Line.from(Span.styled(tip, Style.create().fg(Color.DARK_GRAY)))))
            .build();
    frame.renderWidget(bar, area);
  }

  private void renderHints(Frame frame, Rect area) {
    // Show temporary hint message if active (auto-dismiss after timeout)
    if (hintMessage != null) {
      if (renderTick - hintMessageTick > HINT_MESSAGE_TICKS) {
        hintMessage = null;
      } else {
        String msg = " " + hintMessage;
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

    String altMod = (PLATFORM == Platform.MACOS) ? "Opt" : "Alt";
    String hints;
    if (focus == Focus.HISTORY_SEARCH) {
      hints = " Type to search  Ctrl+R:older  Enter:accept  Esc:cancel";
    } else if (focus == Focus.SEARCH) {
      String target = searchOriginFocus == Focus.DETAIL ? "detail" : "results";
      hints = " Type to filter (" + target + ")  Ctrl+L:both  Enter:confirm  Esc:cancel";
    } else if (focus == Focus.DETAIL) {
      boolean hasCursor = detailLineTypeRefs != null && detailCursorLine >= 0;
      String drillHint = "";
      if (hasCursor
          && detailCursorLine < detailLineTypeRefs.size()
          && detailLineTypeRefs.get(detailCursorLine) != null) {
        drillHint = "Enter:drill-down  ";
      }
      String cursorHint = hasCursor ? "\u2191\u2193:select  " : "\u2191\u2193:scroll  ";
      String resultTabHint = tabs.size() > 1 ? "{}:pins  " : "";
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
    } else if (focus == Focus.RESULTS && cpBrowserMode) {
      if (cpTypesFocused) {
        String pinsHint = tabs.size() > 1 ? "  {}:pins" : "";
        hints = " \u2191\u2193:select  Enter/\u2192:view entries  Esc:close" + pinsHint;
      } else {
        ResultTab activeTab = tabs.get(activeTabIndex);
        String rowHint =
            (activeTab.tableData != null && activeTab.selectedRow >= 0)
                ? "\u2191\u2193:row  "
                : "\u2191\u2193:scroll  ";
        String sortHint = "";
        if (activeTab.tableData != null && activeTab.tableHeaders != null) {
          sortHint =
              activeTab.sortColumn >= 0
                  ? "<>:sort col  " + altMod + "+r:reverse  "
                  : "<>:sort col  ";
        }
        String filterHint =
            activeTab.filteredIndices != null ? "/:search  Esc:clear  " : "/:search  ";
        String detailJump = detailTabNames.isEmpty() ? "" : altMod + "+d:detail  ";
        String tabSwitchHint = detailTabNames.isEmpty() ? "" : "[]:subtabs  ";
        String resultTabHint = tabs.size() > 1 ? "{}:pins  " : "";
        String pinHint = activeTab.pinned ? "Ctrl+P:unpin  " : "Ctrl+P:pin  ";
        String exportHint = activeTab.tableData != null ? "Ctrl+E:export  " : "";
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
    } else if (focus == Focus.RESULTS) {
      ResultTab activeTab = tabs.get(activeTabIndex);
      String rowHint =
          (activeTab.tableData != null && activeTab.selectedRow >= 0)
              ? "\u2191\u2193:row  "
              : "\u2191\u2193\u2190\u2192:scroll  ";
      String sortHint = "";
      if (activeTab.tableData != null && activeTab.tableHeaders != null) {
        sortHint =
            activeTab.sortColumn >= 0 ? "<>:sort col  " + altMod + "+r:reverse  " : "<>:sort col  ";
      }
      String filterHint =
          activeTab.filteredIndices != null ? "/:search  Esc:clear  " : "/:search  ";
      String detailJump = detailTabNames.isEmpty() ? "" : altMod + "+d:detail  ";
      String tabSwitchHint = detailTabNames.isEmpty() ? "" : "[]:subtabs  ";
      String resultTabHint = tabs.size() > 1 ? "{}:pins  " : "";
      String pinHint = activeTab.pinned ? "Ctrl+P:unpin  " : "Ctrl+P:pin  ";
      String exportHint = activeTab.tableData != null ? "Ctrl+E:export  " : "";
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
      if (completionPopupVisible) {
        hints = " \u2191\u2193:select  Enter/Tab:accept  Esc:cancel  Type to filter";
      } else if (cellPickerVisible) {
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

  // ---- key handling ----

  private void handleKey(int key) throws IOException {
    // Intercept keys when completion popup is visible
    if (sessionPickerVisible) {
      handleSessionPickerKey(key);
      return;
    }
    if (completionPopupVisible) {
      handleCompletionPopupKey(key);
      return;
    }
    if (cellPickerVisible) {
      handleCellPickerKey(key);
      return;
    }
    if (exportPopupVisible) {
      handleExportPopupKey(key);
      return;
    }
    // Global keys (work in any focus)
    switch (key) {
      case 3: // Ctrl+C
        if (focus == Focus.HISTORY_SEARCH) {
          // Cancel history search — restore saved input
          inputState.setText(historySearchSavedInput);
          focus = Focus.INPUT;
        } else if (focus == Focus.SEARCH) {
          cancelSearch();
        } else if (focus == Focus.DETAIL) {
          focus = Focus.RESULTS;
        } else if (focus == Focus.RESULTS) {
          focus = Focus.INPUT;
        } else if (inputState.text().isEmpty()) {
          running = false;
        } else {
          inputState.clear();
        }
        return;
      case 4: // Ctrl+D — exit only when input is empty (standard readline behavior)
        if (focus == Focus.INPUT && inputState.text().isEmpty()) {
          running = false;
        }
        return;
      case 5: // Ctrl+E — export active tab data to CSV (only from RESULTS/DETAIL)
        if (focus == Focus.RESULTS || focus == Focus.DETAIL) {
          exportActiveTab();
        } else if (focus == Focus.INPUT) {
          inputState.moveCursorToEnd();
        }
        return;
      case 16: // Ctrl+P — toggle pin on active tab
        togglePin();
        return;
      case 9: // Tab — trigger command completion (switch to INPUT if needed)
        if (focus != Focus.INPUT) {
          focus = Focus.INPUT;
        }
        openCompletionPopup();
        return;
      case 27: // ESC sequence or plain Escape
        handleEscapeSequence();
        return;
      case 18: // Ctrl+R — history search from any focus
        if (focus == Focus.HISTORY_SEARCH) {
          // Advance to next distinct older match (skip duplicates)
          if (!historySearchQuery.isEmpty()) {
            String current =
                historySearchIndex >= 0 && historySearchIndex < commandHistory.size()
                    ? commandHistory.get(historySearchIndex)
                    : "";
            int match =
                findDistinctHistoryMatch(historySearchQuery, historySearchIndex - 1, current);
            if (match < 0) {
              // Wrap around from the end
              match =
                  findDistinctHistoryMatch(historySearchQuery, commandHistory.size() - 1, current);
            }
            if (match >= 0) {
              historySearchIndex = match;
              historySearchFailing = false;
              inputState.setText(commandHistory.get(match));
            }
          }
        } else {
          if (focus != Focus.INPUT) {
            focus = Focus.INPUT;
          }
          historySearchSavedInput = inputState.text();
          historySearchQuery = "";
          historySearchIndex = commandHistory.size();
          historySearchFailing = false;
          focus = Focus.HISTORY_SEARCH;
        }
        return;
      case 6: // Ctrl+F — enter search mode (only from RESULTS/DETAIL)
      case 31: // Ctrl+/ — enter search mode (only from RESULTS/DETAIL)
        if (focus == Focus.RESULTS || focus == Focus.DETAIL) {
          enterSearchMode();
        }
        return;
      default:
        break;
    }

    // Opt/Alt+1-9: switch detail tab (global — works from any focus)
    int detailIdx = macOptDigit(key);
    if (detailIdx >= 0 && detailIdx < detailTabNames.size()) {
      activeDetailTabIndex = detailIdx;
      return;
    }

    // macOS Opt+R/D/C: quick focus switch (Opt+R reverses sort when in RESULTS)
    if (key == MAC_OPT_R) {
      if (focus == Focus.RESULTS) {
        reverseSortIfActive();
      } else {
        focus = Focus.RESULTS;
      }
      return;
    }
    if (key == MAC_OPT_D && !detailTabNames.isEmpty()) {
      focus = Focus.DETAIL;
      return;
    }
    if (key == MAC_OPT_C) {
      focus = Focus.INPUT;
      return;
    }
    if (key == MAC_OPT_S) {
      openSessionPicker();
      return;
    }

    // {}: switch result tabs — works from any non-typing focus
    if (focus != Focus.INPUT && focus != Focus.SEARCH && focus != Focus.HISTORY_SEARCH) {
      if (key == '{' && tabs.size() > 1) {
        switchTab((activeTabIndex - 1 + tabs.size()) % tabs.size());
        return;
      }
      if (key == '}' && tabs.size() > 1) {
        switchTab((activeTabIndex + 1) % tabs.size());
        return;
      }
    }

    // Focus-specific keys
    if (focus == Focus.HISTORY_SEARCH) {
      handleHistorySearchKey(key);
    } else if (focus == Focus.SEARCH) {
      handleSearchKey(key);
    } else if (focus == Focus.DETAIL) {
      handleDetailKey(key);
    } else if (focus == Focus.RESULTS) {
      handleResultsKey(key);
    } else {
      handleInputKey(key);
    }
  }

  private void handleResultsKey(int key) {
    switch (key) {
      case 13: // Enter
      case 10:
        if (cpBrowserMode && cpTypesFocused) {
          browserNavPending = null; // cancel debounce
          String name = getSelectedCpTypeName();
          if (!name.isEmpty()) loadBrowserEntries(name, false);
        } else {
          if (!detailTabNames.isEmpty()) {
            detailHScrollOffset = 0;
          }
          focus = detailTabNames.isEmpty() ? Focus.INPUT : Focus.DETAIL;
        }
        break;
      case '/': // Enter search mode (vim convention)
        enterSearchMode();
        break;
      case '<': // Sort column left
      case '>': // Sort column right
        {
          ResultTab rt = tabs.get(activeTabIndex);
          if (rt.tableData != null && rt.tableHeaders != null && !rt.tableHeaders.isEmpty()) {
            int colCount = rt.tableHeaders.size();
            if (rt.sortColumn < 0) {
              rt.sortColumn = 0;
            } else {
              rt.sortColumn =
                  key == '<'
                      ? (rt.sortColumn - 1 + colCount) % colCount
                      : (rt.sortColumn + 1) % colCount;
            }
            rt.sortAscending = true;
            applySortAndRerender(rt);
          }
        }
        break;
      case '[':
        if (!detailTabNames.isEmpty()) {
          activeDetailTabIndex =
              (activeDetailTabIndex - 1 + detailTabNames.size()) % detailTabNames.size();
        }
        break;
      case ']':
        if (!detailTabNames.isEmpty()) {
          activeDetailTabIndex = (activeDetailTabIndex + 1) % detailTabNames.size();
        }
        break;
      default:
        if (key == '@') {
          focus = Focus.INPUT;
          if (!openCellPicker()) {
            inputState.insert('@');
          }
          break;
        }
        if (key >= 32 && key < 127) {
          // Printable char — switch to input and insert it
          focus = Focus.INPUT;
          inputState.insert((char) key);
          historyIndex = -1;
        }
        break;
    }
  }

  private void handleDetailKey(int key) {
    switch (key) {
      case '/': // Enter search mode for detail
        enterSearchMode();
        break;
      case 13: // Enter — drill-down in metadata mode, or switch to input
      case 10:
        if (detailLineTypeRefs != null
            && detailCursorLine >= 0
            && detailCursorLine < detailLineTypeRefs.size()) {
          String targetType = detailLineTypeRefs.get(detailCursorLine);
          if (targetType != null) {
            navigateToType(targetType);
            break;
          }
        }
        focus = Focus.INPUT;
        break;
      case '[':
        if (!detailTabNames.isEmpty()) {
          activeDetailTabIndex =
              (activeDetailTabIndex - 1 + detailTabNames.size()) % detailTabNames.size();
        }
        break;
      case ']':
        if (!detailTabNames.isEmpty()) {
          activeDetailTabIndex = (activeDetailTabIndex + 1) % detailTabNames.size();
        }
        break;
      default:
        if (key == '@') {
          focus = Focus.INPUT;
          if (!openCellPicker()) {
            inputState.insert('@');
          }
          break;
        }
        if (key >= 32 && key < 127) {
          focus = Focus.INPUT;
          inputState.insert((char) key);
          historyIndex = -1;
        }
        break;
    }
  }

  private void handleInputKey(int key) {
    switch (key) {
      case 13: // Enter
      case 10:
        submitCommand();
        break;
      case 127: // Backspace
      case 8:
        inputState.deleteBackward();
        break;
      default:
        if (key == '@' && openCellPicker()) {
          break;
        }
        if (key >= 32 && key < 127) {
          inputState.insert((char) key);
          historyIndex = -1;
        }
        break;
    }
  }

  private void enterSearchMode() {
    searchOriginFocus = (focus == Focus.DETAIL) ? Focus.DETAIL : Focus.RESULTS;
    searchOriginCpTypes = (cpBrowserMode && cpTypesFocused && focus == Focus.RESULTS);
    focus = Focus.SEARCH;
    ResultTab tab = tabs.get(activeTabIndex);
    if (searchOriginFocus == Focus.DETAIL) {
      searchInputState.setText(tab.detailSearchQuery);
    } else if (searchOriginCpTypes) {
      searchInputState.setText(cpTypesSearchQuery);
    } else {
      searchInputState.setText(tab.searchQuery);
    }
  }

  private void handleSearchKey(int key) {
    ResultTab tab = tabs.get(activeTabIndex);
    switch (key) {
      case 12: // Ctrl+L — apply search to both views
        {
          String query = searchInputState.text();
          applySearchFilter(tab, query);
          tab.detailSearchQuery = query;
          if (cpBrowserMode) {
            applyCpTypesFilter(query);
          }
          focus = searchOriginFocus;
        }
        break;
      case 13: // Enter — confirm filter
      case 10:
        if (searchOriginFocus == Focus.DETAIL) {
          tab.detailSearchQuery = searchInputState.text();
        } else if (searchOriginCpTypes) {
          applyCpTypesFilter(searchInputState.text());
        } else {
          applySearchFilter(tab, searchInputState.text());
        }
        focus = searchOriginFocus;
        break;
      case 127: // Backspace
      case 8:
        if (searchInputState.text().isEmpty()) {
          cancelSearch();
        } else {
          searchInputState.deleteBackward();
          applyLiveSearchFilter(tab, searchInputState.text());
        }
        break;
      default:
        if (key >= 32 && key < 127) {
          searchInputState.insert((char) key);
          applyLiveSearchFilter(tab, searchInputState.text());
        }
        break;
    }
  }

  private void applyLiveSearchFilter(ResultTab tab, String query) {
    if (searchOriginFocus == Focus.DETAIL) {
      tab.detailSearchQuery = query;
    } else if (searchOriginCpTypes) {
      applyCpTypesFilter(query);
    } else {
      applySearchFilter(tab, query);
    }
  }

  private void cancelSearch() {
    ResultTab tab = tabs.get(activeTabIndex);
    if (searchOriginFocus == Focus.DETAIL) {
      tab.detailSearchQuery = "";
    } else if (searchOriginCpTypes) {
      cpTypesSearchQuery = "";
      cpTypesFilteredIndices = null;
    } else {
      tab.searchQuery = "";
      tab.filteredIndices = null;
      tab.filteredMaxLineWidth = 0;
      tab.scrollOffset = 0;
    }
    focus = searchOriginFocus;
  }

  private void applyCpTypesFilter(String query) {
    cpTypesSearchQuery = query;
    if (query.isEmpty() || cpTypes == null) {
      cpTypesFilteredIndices = null;
      return;
    }
    String lower = query.toLowerCase();
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < cpTypes.size(); i++) {
      String name = String.valueOf(cpTypes.get(i).getOrDefault("name", ""));
      if (name.toLowerCase().contains(lower)) {
        indices.add(i);
      }
    }
    cpTypesFilteredIndices = indices;
    cpTypeScrollOffset = 0;
  }

  private void handleHistorySearchKey(int key) {
    switch (key) {
      case 13: // Enter — accept match
      case 10:
        focus = Focus.INPUT;
        break;
      case 127: // Backspace
      case 8:
        if (historySearchQuery.isEmpty()) {
          // Backspace on empty query — cancel
          inputState.setText(historySearchSavedInput);
          focus = Focus.INPUT;
        } else {
          historySearchQuery = historySearchQuery.substring(0, historySearchQuery.length() - 1);
          // Re-search from end with shortened query
          int match = findHistoryMatch(historySearchQuery, commandHistory.size() - 1);
          if (match >= 0) {
            historySearchIndex = match;
            historySearchFailing = false;
            inputState.setText(commandHistory.get(match));
          } else if (historySearchQuery.isEmpty()) {
            historySearchIndex = commandHistory.size();
            historySearchFailing = false;
            inputState.setText(historySearchSavedInput);
          } else {
            historySearchIndex = -1;
            historySearchFailing = true;
          }
        }
        break;
      default:
        if (key >= 32 && key < 127) {
          historySearchQuery += (char) key;
          int match = findHistoryMatch(historySearchQuery, historySearchIndex);
          if (match < 0) {
            // Try from the end if current position yielded no match
            match = findHistoryMatch(historySearchQuery, commandHistory.size() - 1);
          }
          if (match >= 0) {
            historySearchIndex = match;
            historySearchFailing = false;
            inputState.setText(commandHistory.get(match));
          } else {
            historySearchFailing = true;
          }
        }
        break;
    }
  }

  private int findHistoryMatch(String query, int fromIndex) {
    if (query.isEmpty() || commandHistory.isEmpty()) return -1;
    String lower = query.toLowerCase();
    for (int i = Math.min(fromIndex, commandHistory.size() - 1); i >= 0; i--) {
      if (commandHistory.get(i).toLowerCase().contains(lower)) {
        return i;
      }
    }
    return -1;
  }

  private int findDistinctHistoryMatch(String query, int fromIndex, String skipValue) {
    if (query.isEmpty() || commandHistory.isEmpty()) return -1;
    String lower = query.toLowerCase();
    for (int i = Math.min(fromIndex, commandHistory.size() - 1); i >= 0; i--) {
      String entry = commandHistory.get(i);
      if (entry.toLowerCase().contains(lower) && !entry.equals(skipValue)) {
        return i;
      }
    }
    return -1;
  }

  // macOS Opt+digit Unicode code points (US keyboard layout).
  // Maps to 0-based detail tab index, or -1 if not a match.
  private static final int[] MAC_OPT_DIGITS = {
    0x00A1, // Opt+1 → ¡
    0x2122, // Opt+2 → ™
    0x00A3, // Opt+3 → £
    0x00A2, // Opt+4 → ¢
    0x221E, // Opt+5 → ∞
    0x00A7, // Opt+6 → §
    0x00B6, // Opt+7 → ¶
    0x2022, // Opt+8 → •
    0x00AA, // Opt+9 → ª
  };

  private static int macOptDigit(int codePoint) {
    for (int i = 0; i < MAC_OPT_DIGITS.length; i++) {
      if (codePoint == MAC_OPT_DIGITS[i]) return i;
    }
    return -1;
  }

  /** Build a title Line with one character underlined as a mnemonic hint. */
  private static Title mnemonicTitle(String text, int mnemonicIndex) {
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

  // macOS Opt+R/D/C Unicode code points (US keyboard layout) for quick focus switch.
  private static final int MAC_OPT_R = 0x00AE; // Opt+r → ®
  private static final int MAC_OPT_D = 0x2202; // Opt+d → ∂
  private static final int MAC_OPT_C = 0x00E7; // Opt+c → ç
  private static final int MAC_OPT_S = 0x00DF; // Opt+s → ß

  // Modifier codes in CSI sequences: ESC [ 1 ; <mod> <dir>
  private static final int MOD_NONE = 0;
  private static final int MOD_SHIFT = '2';
  private static final int MOD_ALT = '3';
  private static final int MOD_CTRL = '5';

  private void handleEscapeSequence() throws IOException {
    int next = backend.read(50);
    if (next == READ_EXPIRED || next == EOF) {
      // Plain Escape
      if (focus == Focus.HISTORY_SEARCH) {
        // Cancel history search — restore saved input
        inputState.setText(historySearchSavedInput);
        focus = Focus.INPUT;
      } else if (focus == Focus.SEARCH) {
        cancelSearch();
      } else if (focus == Focus.DETAIL) {
        ResultTab dtab = tabs.get(activeTabIndex);
        if (dtab.detailSearchQuery != null && !dtab.detailSearchQuery.isEmpty()) {
          dtab.detailSearchQuery = "";
        } else {
          focus = Focus.RESULTS;
        }
      } else if (focus == Focus.RESULTS && cpBrowserMode) {
        if (!cpTypesFocused) {
          // Clear entries filter first if active
          ResultTab escTab = tabs.get(activeTabIndex);
          if (escTab.filteredIndices != null) {
            escTab.searchQuery = "";
            escTab.filteredIndices = null;
            escTab.filteredMaxLineWidth = 0;
            escTab.scrollOffset = 0;
          } else {
            cpTypesFocused = true;
          }
        } else {
          // Clear types filter first if active
          if (cpTypesFilteredIndices != null) {
            cpTypesSearchQuery = "";
            cpTypesFilteredIndices = null;
          } else {
            if (eventBrowserMode) {
              exitEventBrowserMode();
            } else {
              exitCpBrowserMode();
            }
            focus = Focus.INPUT;
          }
        }
      } else if (focus == Focus.RESULTS) {
        ResultTab escTab = tabs.get(activeTabIndex);
        if (escTab.filteredIndices != null) {
          escTab.searchQuery = "";
          escTab.filteredIndices = null;
          escTab.filteredMaxLineWidth = 0;
          escTab.scrollOffset = 0;
        } else {
          focus = Focus.INPUT;
        }
      } else {
        inputState.clear();
      }
      return;
    }
    // ESC 1-9 — Opt+1-9 on macOS — switch detail tab (works from any focus)
    if (next >= '1' && next <= '9') {
      int tabIdx = next - '1'; // 0-based
      if (tabIdx < detailTabNames.size()) {
        activeDetailTabIndex = tabIdx;
      }
      return;
    }
    // ESC b / ESC f — sent by macOS terminals for Option+Left/Right (default settings)
    if (next == 'b' && tabs.size() > 1) {
      switchTab((activeTabIndex - 1 + tabs.size()) % tabs.size());
      return;
    }
    if (next == 'f' && tabs.size() > 1) {
      switchTab((activeTabIndex + 1) % tabs.size());
      return;
    }
    // ESC r/d/c — Alt+R/D/C on Linux — quick focus switch (Alt+R reverses sort in RESULTS)
    if (next == 'r') {
      if (focus == Focus.RESULTS) {
        reverseSortIfActive();
      } else {
        focus = Focus.RESULTS;
      }
      return;
    }
    if (next == 'd' && !detailTabNames.isEmpty()) {
      focus = Focus.DETAIL;
      return;
    }
    if (next == 'c') {
      focus = Focus.INPUT;
      return;
    }
    if (next == 's') {
      openSessionPicker();
      return;
    }
    if (next != '[') return;

    int code = backend.read(50);
    if (code == READ_EXPIRED || code == EOF) return;

    // ESC [ Z — Shift+Tab (backtab) — cycle focus
    if (code == 'Z') {
      if (focus == Focus.INPUT) {
        focus = Focus.RESULTS;
      } else if (focus == Focus.RESULTS) {
        if (!detailTabNames.isEmpty()) {
          focus = Focus.DETAIL;
        } else {
          focus = Focus.INPUT;
        }
      } else {
        focus = Focus.INPUT;
      }
      return;
    }

    // Simple sequences: ESC [ <letter>  (no modifier)
    if (code >= 'A' && code <= 'Z') {
      dispatchArrow(code, MOD_NONE);
      return;
    }

    // Parameterized: ESC [ <digit> ...
    if (code >= '0' && code <= '9') {
      int second = backend.read(50);
      if (second == READ_EXPIRED || second == EOF) return;

      if (second == '~') {
        if (code == '3') {
          // ESC [ 3 ~ — Delete (Fn+Backspace on macOS)
          if (focus == Focus.SEARCH) searchInputState.deleteForward();
          else inputState.deleteForward();
        }
        // ESC [ N ~  (PgUp/PgDn — always scroll results regardless of focus)
        else if (code == '5') scrollResults(-resultsAreaHeight);
        else if (code == '6') scrollResults(resultsAreaHeight);
      } else if (second == ';') {
        // ESC [ 1 ; <mod> <dir>
        int mod = backend.read(50);
        if (mod == READ_EXPIRED || mod == EOF) return;
        int dir = backend.read(50);
        if (dir == READ_EXPIRED || dir == EOF) return;
        dispatchArrow(dir, mod);
      }
    }
  }

  private void dispatchArrow(int direction, int modifier) {
    // Shift+Up/Down: navigate command history from any focus
    if (modifier == MOD_SHIFT && (direction == 'A' || direction == 'B')) {
      focus = Focus.INPUT;
      navigateHistory(direction == 'A' ? -1 : 1);
      return;
    }

    // Ctrl+Up/Down: fast scroll results (page at a time)
    if (modifier == MOD_CTRL && (direction == 'A' || direction == 'B')) {
      if (focus == Focus.DETAIL) {
        int delta = direction == 'A' ? -detailAreaHeight : detailAreaHeight;
        setDetailScrollOffset(Math.max(0, getDetailScrollOffset() + delta));
      } else {
        scrollResults(direction == 'A' ? -resultsAreaHeight : resultsAreaHeight);
      }
      return;
    }

    // Ctrl/Alt+Left/Right: tab switch
    if ((modifier == MOD_CTRL || modifier == MOD_ALT)
        && (direction == 'C' || direction == 'D')
        && tabs.size() > 1) {
      if (direction == 'D') {
        switchTab((activeTabIndex - 1 + tabs.size()) % tabs.size());
      } else {
        switchTab((activeTabIndex + 1) % tabs.size());
      }
      return;
    }

    if (focus == Focus.DETAIL) {
      // Detail pane: cursor movement in metadata mode, scroll otherwise
      boolean hasCursor = detailLineTypeRefs != null && detailCursorLine >= 0;
      switch (direction) {
        case 'A':
          if (hasCursor) {
            moveDetailCursor(-1);
          } else {
            setDetailScrollOffset(Math.max(0, getDetailScrollOffset() - 1));
          }
          break;
        case 'B':
          if (hasCursor) {
            moveDetailCursor(1);
          } else {
            setDetailScrollOffset(getDetailScrollOffset() + 1);
          }
          break;
        case 'C': // Right — horizontal scroll detail
          {
            int hStep = (modifier == MOD_SHIFT) ? 20 : 4;
            detailHScrollOffset += hStep;
          }
          break;
        case 'D': // Left — horizontal scroll detail
          {
            int hStep = (modifier == MOD_SHIFT) ? 20 : 4;
            detailHScrollOffset = Math.max(0, detailHScrollOffset - hStep);
          }
          break;
        default:
          break;
      }
    } else if (focus == Focus.RESULTS) {
      if (cpBrowserMode && cpTypesFocused) {
        dispatchCpTypesArrow(direction);
        return;
      }
      ResultTab rt = tabs.get(activeTabIndex);
      boolean hasTable = rt.tableData != null && rt.selectedRow >= 0;
      // Results pane: arrows scroll/select rows
      int hStep = (modifier == MOD_SHIFT) ? 20 : 4;
      switch (direction) {
        case 'A':
          if (hasTable) {
            moveSelectedRow(rt, -1);
          } else {
            scrollResults(-1);
          }
          break;
        case 'B':
          if (hasTable) {
            moveSelectedRow(rt, 1);
          } else {
            scrollResults(1);
          }
          break;
        case 'C':
          scrollHorizontal(hStep);
          break;
        case 'D':
          if (cpBrowserMode && !cpTypesFocused && rt.hScrollOffset == 0) {
            cpTypesFocused = true;
          } else {
            scrollHorizontal(-hStep);
          }
          break;
        case 'H':
          tabs.get(activeTabIndex).scrollOffset = 0;
          break;
        case 'F':
          tabs.get(activeTabIndex).scrollOffset = Integer.MAX_VALUE;
          break;
        default:
          break;
      }
    } else {
      // Input pane: unmodified → cursor/history; Shift+Left/Right → scroll results
      if (modifier == MOD_SHIFT) {
        switch (direction) {
          case 'C':
            scrollHorizontal(10);
            break;
          case 'D':
            scrollHorizontal(-10);
            break;
          default:
            break;
        }
      } else {
        switch (direction) {
          case 'C':
            inputState.moveCursorRight();
            break;
          case 'D':
            inputState.moveCursorLeft();
            break;
          case 'H':
            inputState.moveCursorToStart();
            break;
          case 'F':
            inputState.moveCursorToEnd();
            break;
          default:
            break;
        }
      }
    }
  }

  // ---- tab management ----

  private void togglePin() {
    ResultTab tab = tabs.get(activeTabIndex);
    if (tab.pinned) {
      // Unpin — remove any other unpinned tab first (only one unpinned tab allowed)
      int existingUnpinned = findUnpinnedTab();
      if (existingUnpinned >= 0) {
        tabs.remove(existingUnpinned);
        if (activeTabIndex > existingUnpinned) {
          activeTabIndex--;
        } else if (activeTabIndex >= tabs.size()) {
          activeTabIndex = tabs.size() - 1;
        }
      }
      tab.pinned = false;
      tab.name = "jfr>";
      tab.marqueeTick0 = renderTick;
    } else {
      tab.pinned = true;
    }
  }

  private void switchTab(int newIndex) {
    if (newIndex == activeTabIndex) return;
    int oldIndex = activeTabIndex;
    // Save browser state for old tab
    if (cpBrowserMode) {
      tabs.get(oldIndex).cpTypeIndex = cpTypeSelectedIndex;
    }
    activeTabIndex = newIndex;
    // Restore browser state for new tab
    ResultTab newTab = tabs.get(newIndex);
    if (newTab.cpTypeIndex >= 0 && newTab.browserTypes != null) {
      cpBrowserMode = true;
      eventBrowserMode = newTab.isEventBrowserTab;
      cpTypes = newTab.browserTypes;
      cpTypeSelectedIndex = newTab.cpTypeIndex;
      cpTypesFocused = false;
    } else {
      if (newTab.cpTypeIndex >= 0) newTab.cpTypeIndex = -1;
      cpBrowserMode = false;
      eventBrowserMode = false;
      cpTypesFocused = false;
    }
    buildDetailTabs(newTab);
  }

  /** Find the index of the unpinned tab, or -1 if none exists. */
  private int findUnpinnedTab() {
    for (int i = tabs.size() - 1; i >= 0; i--) {
      if (!tabs.get(i).pinned) return i;
    }
    return -1;
  }

  // ---- CP browser mode ----

  private static boolean isCpSummaryCommand(String command) {
    String[] parts = command.trim().split("\\s+");
    if (parts.length == 0) return false;
    // Accept "cp ...", "constants ...", and "show cp ..."
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
    // bare "cp" / "show cp" or with --summary (no type argument)
    for (int i = cpIndex + 1; i < parts.length; i++) {
      String p = parts[i];
      if ("--summary".equals(p) || "--format".equals(p) || "--range".equals(p)) {
        // skip known flags (--format/--range consume next arg)
        if (("--format".equals(p) || "--range".equals(p)) && i + 1 < parts.length) i++;
        continue;
      }
      // Non-flag argument = type name, not a summary command
      return false;
    }
    return true;
  }

  private void enterCpBrowserMode(ResultTab tab) {
    cpBrowserMode = true;
    eventBrowserMode = false;
    cpTypes = tab.tableData;
    cpTypeSelectedIndex = 0;
    cpTypeScrollOffset = 0;
    cpTypesFocused = true;
    tab.cpTypeIndex = 0;
    tab.browserTypes = cpTypes;
    tab.isEventBrowserTab = false;
    // Clear tab for entries (will be populated on first type selection)
    tab.tableData = null;
    tab.tableHeaders = null;
    tab.selectedRow = -1;
    tab.dataStartLine = -1;
    tab.lines.clear();
    tab.maxLineWidth = 0;
    // Clear detail state
    detailTabNames = List.of();
    detailTabValues = List.of();
    activeDetailTabIndex = 0;
    detailTabScrollOffsets.clear();
    detailCursorLine = -1;
    detailLineTypeRefs = null;
    focus = Focus.RESULTS;

    // Auto-load entries for the first type
    String firstName = getSelectedCpTypeName();
    if (!firstName.isEmpty()) loadCpEntries(firstName, true);
  }

  private void exitCpBrowserMode() {
    cpBrowserMode = false;
    eventBrowserMode = false;
    cpTypeSelectedIndex = 0;
    cpTypeScrollOffset = 0;
    cpTypesFocused = false;
    browserNavPending = null;
    cpTypesSearchQuery = "";
    cpTypesFilteredIndices = null;
    // Keep cpTypes cached so pinned browser tabs can restore
  }

  // ---- Event browser mode ----

  private static boolean isEventsSummaryCommand(String command) {
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
    // bare "events" / "show events" or with flags but no type argument
    for (int i = evtIndex + 1; i < parts.length; i++) {
      String p = parts[i];
      if ("--format".equals(p) || "--limit".equals(p) || "--range".equals(p)) {
        if (i + 1 < parts.length) i++; // skip flag value
        continue;
      }
      // Non-flag argument = type name, not a summary command
      return false;
    }
    return true;
  }

  private void enterEventBrowserMode(ResultTab tab) {
    cpBrowserMode = true;
    eventBrowserMode = true;
    cpTypes = tab.tableData;
    cpTypeSelectedIndex = 0;
    cpTypeScrollOffset = 0;
    cpTypesFocused = true;
    tab.cpTypeIndex = 0;
    tab.browserTypes = cpTypes;
    tab.isEventBrowserTab = true;
    // Clear tab for entries (will be populated on first type selection)
    tab.tableData = null;
    tab.tableHeaders = null;
    tab.selectedRow = -1;
    tab.dataStartLine = -1;
    tab.lines.clear();
    tab.maxLineWidth = 0;
    // Clear detail state
    detailTabNames = List.of();
    detailTabValues = List.of();
    activeDetailTabIndex = 0;
    detailTabScrollOffsets.clear();
    detailCursorLine = -1;
    detailLineTypeRefs = null;
    focus = Focus.RESULTS;

    // Auto-load events for the first type
    String firstName = getSelectedCpTypeName();
    if (!firstName.isEmpty()) loadEventEntries(firstName, true);
  }

  private void exitEventBrowserMode() {
    cpBrowserMode = false;
    eventBrowserMode = false;
    cpTypeSelectedIndex = 0;
    cpTypeScrollOffset = 0;
    cpTypesFocused = false;
    browserNavPending = null;
    cpTypesSearchQuery = "";
    cpTypesFilteredIndices = null;
  }

  private void loadEventEntries(String typeName, boolean keepTypesFocused) {
    JFRSession session = sessions.current().map(ref -> ref.session).orElse(null);
    if (session == null) return;

    List<Map<String, Object>> entries;
    try {
      var query = JfrPathParser.parse("events/" + typeName);
      entries = new JfrPathEvaluator().evaluateWithLimit(session, query, 500);
    } catch (Exception e) {
      ResultTab tab = tabs.get(activeTabIndex);
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
      if (!keepTypesFocused) cpTypesFocused = false;
      return;
    }

    ResultTab tab = tabs.get(activeTabIndex);
    tab.name = typeName;
    tab.marqueeTick0 = renderTick;
    tab.cpTypeIndex = cpTypeSelectedIndex;
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
      detailTabNames = List.of();
      detailTabValues = List.of();
      activeDetailTabIndex = 0;
      detailTabScrollOffsets.clear();
      detailCursorLine = -1;
      detailLineTypeRefs = null;
      if (!keepTypesFocused) cpTypesFocused = false;
      return;
    }

    // Compute columns as union of keys from sampled rows
    Set<String> cols = new LinkedHashSet<>();
    int sample = Math.min(entries.size(), 200);
    for (int i = 0; i < sample; i++) cols.addAll(entries.get(i).keySet());
    if (cols.size() <= 2 && entries.size() > sample) {
      int sample2 = Math.min(entries.size(), 1000);
      for (int i = 0; i < sample2; i++) cols.addAll(entries.get(i).keySet());
    }
    List<String> headers = new ArrayList<>(cols);

    int[] widths = TuiTableRenderer.computeMaxWidths(headers, entries);

    // Store pagination state on the tab
    tab.cpAllEntries = entries;
    tab.cpColumnHeaders = headers;
    tab.cpColumnWidths = widths;

    // Render header line
    StringBuilder sb = new StringBuilder("  ");
    for (int c = 0; c < headers.size(); c++) {
      if (c > 0) sb.append("  ");
      sb.append(String.format("%-" + widths[c] + "s", headers.get(c)));
    }
    String headerLine = sb.toString();
    tab.lines.add(headerLine);
    tab.maxLineWidth = Math.max(tab.maxLineWidth, headerLine.length());

    // Render first page
    int pageSize = Math.min(entries.size(), Math.max(resultsAreaHeight + 5, 20));
    renderCpPage(tab, 0, pageSize);

    tab.tableData = new ArrayList<>(entries.subList(0, pageSize));
    tab.tableHeaders = headers;
    tab.cpRenderedCount = pageSize;
    tab.selectedRow = 0;
    tab.dataStartLine = 1;

    buildDetailTabs(tab);

    tab.scrollOffset = 0;
    if (!keepTypesFocused) cpTypesFocused = false;
  }

  private String getSelectedCpTypeName() {
    if (cpTypes != null && cpTypeSelectedIndex < cpTypes.size()) {
      return String.valueOf(cpTypes.get(cpTypeSelectedIndex).getOrDefault("name", ""));
    }
    return "";
  }

  /**
   * Find the previous raw cpTypes index that is visible (passes the filter). Returns -1 if none.
   */
  private int findPrevCpType(int currentIndex) {
    if (cpTypesFilteredIndices == null) {
      return currentIndex > 0 ? currentIndex - 1 : -1;
    }
    // Walk the filtered list to find the entry just before currentIndex
    int prev = -1;
    for (int idx : cpTypesFilteredIndices) {
      if (idx >= currentIndex) break;
      prev = idx;
    }
    return prev;
  }

  /** Find the next raw cpTypes index that is visible (passes the filter). Returns -1 if none. */
  private int findNextCpType(int currentIndex) {
    if (cpTypesFilteredIndices == null) {
      return (cpTypes != null && currentIndex < cpTypes.size() - 1) ? currentIndex + 1 : -1;
    }
    for (int idx : cpTypesFilteredIndices) {
      if (idx > currentIndex) return idx;
    }
    return -1;
  }

  /**
   * Convert a raw cpTypes index to its position in the visible (filtered) list. Returns -1 if not
   * visible.
   */
  private int cpTypesVisibleIndex(int rawIndex) {
    if (cpTypesFilteredIndices == null) return rawIndex;
    int pos = cpTypesFilteredIndices.indexOf(rawIndex);
    return pos; // -1 if not found
  }

  private void dispatchCpTypesArrow(int direction) {
    switch (direction) {
      case 'A': // Up
        {
          int prev = findPrevCpType(cpTypeSelectedIndex);
          if (prev >= 0) {
            cpTypeSelectedIndex = prev;
            // Scroll offset is in filtered-list space
            int visIdx = cpTypesVisibleIndex(prev);
            if (visIdx >= 0 && visIdx < cpTypeScrollOffset) {
              cpTypeScrollOffset = visIdx;
            }
            String upName = getSelectedCpTypeName();
            if (!upName.isEmpty()) {
              browserNavPending = upName;
              browserNavKeepFocus = true;
              browserNavTime = System.nanoTime();
            }
          }
        }
        break;
      case 'B': // Down
        {
          int next = findNextCpType(cpTypeSelectedIndex);
          if (next >= 0) {
            cpTypeSelectedIndex = next;
            int visIdx = cpTypesVisibleIndex(next);
            if (visIdx >= 0 && visIdx >= cpTypeScrollOffset + cpTypesAreaHeight) {
              cpTypeScrollOffset = visIdx - cpTypesAreaHeight + 1;
            }
            String downName = getSelectedCpTypeName();
            if (!downName.isEmpty()) {
              browserNavPending = downName;
              browserNavKeepFocus = true;
              browserNavTime = System.nanoTime();
            }
          }
        }
        break;
      case 'C': // Right — load entries and move focus to entries
        {
          browserNavPending = null; // cancel any pending debounce
          String name = getSelectedCpTypeName();
          if (!name.isEmpty()) loadBrowserEntries(name, false);
        }
        break;
      default:
        break;
    }
  }

  private void loadBrowserEntries(String typeName, boolean keepTypesFocused) {
    if (eventBrowserMode) {
      loadEventEntries(typeName, keepTypesFocused);
    } else {
      loadCpEntries(typeName, keepTypesFocused);
    }
  }

  private void loadCpEntries(String typeName, boolean keepTypesFocused) {
    Path recording = sessions.current().map(ref -> ref.session.getRecordingPath()).orElse(null);
    if (recording == null) return;

    List<Map<String, Object>> entries;
    try {
      entries = ConstantPoolProvider.loadEntries(recording, typeName);
    } catch (Exception e) {
      ResultTab tab = tabs.get(activeTabIndex);
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
      if (!keepTypesFocused) cpTypesFocused = false;

      return;
    }

    ResultTab tab = tabs.get(activeTabIndex);
    tab.name = "cp: " + typeName;
    tab.marqueeTick0 = renderTick;
    tab.cpTypeIndex = cpTypeSelectedIndex;
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
      detailTabNames = List.of();
      detailTabValues = List.of();
      activeDetailTabIndex = 0;
      detailTabScrollOffsets.clear();
      detailCursorLine = -1;
      detailLineTypeRefs = null;
      if (!keepTypesFocused) cpTypesFocused = false;

      return;
    }

    // Compute columns as union of keys from sampled rows
    Set<String> cols = new LinkedHashSet<>();
    int sample = Math.min(entries.size(), 200);
    for (int i = 0; i < sample; i++) cols.addAll(entries.get(i).keySet());
    if (cols.size() <= 2 && entries.size() > sample) {
      int sample2 = Math.min(entries.size(), 1000);
      for (int i = 0; i < sample2; i++) cols.addAll(entries.get(i).keySet());
    }
    List<String> headers = new ArrayList<>(cols);

    // Move "id" column first if present
    int idIdx = headers.indexOf("id");
    if (idIdx > 0) {
      headers.remove(idIdx);
      headers.add(0, "id");
    }

    int[] widths = TuiTableRenderer.computeMaxWidths(headers, entries);

    // Store pagination state on the tab
    tab.cpAllEntries = entries;
    tab.cpColumnHeaders = headers;
    tab.cpColumnWidths = widths;

    // Render header line
    StringBuilder sb = new StringBuilder("  ");
    for (int c = 0; c < headers.size(); c++) {
      if (c > 0) sb.append("  ");
      sb.append(String.format("%-" + widths[c] + "s", headers.get(c)));
    }
    String headerLine = sb.toString();
    tab.lines.add(headerLine);
    tab.maxLineWidth = Math.max(tab.maxLineWidth, headerLine.length());

    // Render first page
    int pageSize = Math.min(entries.size(), Math.max(resultsAreaHeight + 5, 20));
    renderCpPage(tab, 0, pageSize);

    tab.tableData = new ArrayList<>(entries.subList(0, pageSize));
    tab.tableHeaders = headers;
    tab.cpRenderedCount = pageSize;
    tab.selectedRow = 0;
    tab.dataStartLine = 1;
    buildDetailTabs(tab);

    tab.scrollOffset = 0;
    if (!keepTypesFocused) cpTypesFocused = false;
  }

  private void renderCpPage(ResultTab tab, int from, int to) {
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

  private void ensureCpEntriesLoaded(ResultTab tab, int upToRow) {
    if (tab.cpAllEntries == null || tab.cpRenderedCount >= tab.cpAllEntries.size()) return;
    if (upToRow < tab.cpRenderedCount) return;
    int pageSize = Math.max(resultsAreaHeight + 5, 20);
    int nextEnd = Math.min(tab.cpAllEntries.size(), tab.cpRenderedCount + pageSize);
    renderCpPage(tab, tab.cpRenderedCount, nextEnd);
    for (int i = tab.cpRenderedCount; i < nextEnd; i++) {
      tab.tableData.add(tab.cpAllEntries.get(i));
    }
    tab.cpRenderedCount = nextEnd;
  }

  // ---- command execution ----

  private void submitCommand() {
    String command = inputState.text().trim();
    inputState.clear();
    historyIndex = -1;

    if (command.isEmpty()) return;

    // Exit browser mode on any new command (keep cpTypes for pinned browser tabs)
    if (cpBrowserMode) {
      cpBrowserMode = false;
      eventBrowserMode = false;
      cpTypesFocused = false;
      cpTypesSearchQuery = "";
      cpTypesFilteredIndices = null;
    }

    if ("exit".equalsIgnoreCase(command) || "quit".equalsIgnoreCase(command)) {
      running = false;
      return;
    }

    if ("clear".equalsIgnoreCase(command)) {
      ResultTab tab = tabs.get(activeTabIndex);
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
      detailCursorLine = -1;
      detailLineTypeRefs = null;
      buildDetailTabs(tab);
      return;
    }

    // pin [name] — pin the active tab
    if (command.equalsIgnoreCase("pin") || command.toLowerCase().startsWith("pin ")) {
      ResultTab tab = tabs.get(activeTabIndex);
      if (!tab.pinned) {
        String name = command.length() > 4 ? command.substring(4).trim() : "";
        if (!name.isEmpty()) {
          tab.name = name;
          tab.marqueeTick0 = renderTick;
        }
        tab.pinned = true;
      }
      return;
    }

    // closetab [name] — close a pinned tab (uses 'closetab' to avoid shadowing session 'close')
    if (command.equalsIgnoreCase("closetab") || command.toLowerCase().startsWith("closetab ")) {
      String name = command.length() > 9 ? command.substring(9).trim() : "";
      int targetIndex = -1;
      if (name.isEmpty()) {
        // Close active tab only if pinned
        if (tabs.get(activeTabIndex).pinned) {
          targetIndex = activeTabIndex;
        }
      } else {
        for (int i = 0; i < tabs.size(); i++) {
          if (name.equalsIgnoreCase(tabs.get(i).name)) {
            targetIndex = i;
            break;
          }
        }
      }
      if (targetIndex >= 0 && tabs.size() > 1) {
        tabs.remove(targetIndex);
        if (activeTabIndex >= tabs.size()) {
          activeTabIndex = tabs.size() - 1;
        } else if (activeTabIndex > targetIndex) {
          activeTabIndex--;
        }
      }
      return;
    }

    // tabs — list open tabs
    if ("tabs".equalsIgnoreCase(command)) {
      ResultTab tab = tabs.get(activeTabIndex);
      for (int i = 0; i < tabs.size(); i++) {
        ResultTab t = tabs.get(i);
        String marker = (i == activeTabIndex) ? " * " : "   ";
        String pinLabel = t.pinned ? " [pinned]" : "";
        tab.lines.add(marker + t.name + pinLabel + " (" + t.lines.size() + " lines)");
      }
      return;
    }

    commandHistory.add(command);
    lastCommand = command;

    // Run command in the unpinned tab, creating one if all are pinned
    int currentIdx = findUnpinnedTab();
    if (currentIdx < 0) {
      tabs.add(new ResultTab(command));
      currentIdx = tabs.size() - 1;
    }
    activeTabIndex = currentIdx;
    ResultTab activeTab = tabs.get(activeTabIndex);
    activeTab.name = command;
    activeTab.marqueeTick0 = renderTick;
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
    activeTab.selectedRow = -1;
    activeTab.metadataClassCache = null;
    activeTab.sortColumn = -1;
    activeTab.sortAscending = true;
    activeTab.cpAllEntries = null;
    activeTab.cpColumnHeaders = null;
    activeTab.cpColumnWidths = null;
    activeTab.cpRenderedCount = 0;

    // CP browser mode: intercept bare "cp" / "show cp" before dispatch
    if (isCpSummaryCommand(command) && ConstantPoolProvider.isSupported()) {
      Path cpRec = sessions.current().map(ref -> ref.session.getRecordingPath()).orElse(null);
      if (cpRec != null) {
        try {
          List<Map<String, Object>> cpSummary = ConstantPoolProvider.loadSummary(cpRec);
          if (cpSummary != null && !cpSummary.isEmpty()) {
            activeTab.tableData = cpSummary;
            enterCpBrowserMode(activeTab);
            return;
          }
        } catch (Exception ignore) {
          // Fall through to normal dispatch
        }
      }
    }

    // Event browser mode: intercept bare "events" / "show events" — async scan
    if (isEventsSummaryCommand(command)) {
      Path evtRec = sessions.current().map(ref -> ref.session.getRecordingPath()).orElse(null);
      if (evtRec != null) {
        eventBrowserPending = true;
        asyncLinesBeforeDispatch = 0;
        asyncOutputBuffer = new ArrayList<>();
        asyncMaxLineWidth = 0;
        commandRunning = true;
        commandStartTick = renderTick;
        focus = Focus.RESULTS;

        commandFuture =
            commandExecutor.submit(
                () -> {
                  try {
                    Map<String, long[]> counts = new HashMap<>();
                    try (var p = ParsingContext.create().newUntypedParser(evtRec)) {
                      p.handle(
                          (type, value, ctl) ->
                              counts.computeIfAbsent(type.getName(), k -> new long[1])[0]++);
                      p.run();
                    }
                    List<Map<String, Object>> summary = new ArrayList<>();
                    counts.forEach(
                        (name, c) -> {
                          Map<String, Object> row = new LinkedHashMap<>();
                          row.put("name", name);
                          row.put("count", c[0]);
                          summary.add(row);
                        });
                    summary.sort(Comparator.comparing(r -> String.valueOf(r.get("name"))));
                    asyncTableData = summary;
                  } catch (Exception e) {
                    asyncOutputBuffer.add("  Error: " + e.getMessage());
                    eventBrowserPending = false;
                  } finally {
                    commandRunning = false;
                  }
                });
        return;
      }
    }

    // Echo the command in the results buffer
    activeTab.lines.add("> " + command);

    asyncLinesBeforeDispatch = activeTab.lines.size();
    asyncOutputBuffer = new ArrayList<>();
    asyncMaxLineWidth = 0;
    commandRunning = true;
    commandStartTick = renderTick;
    focus = Focus.RESULTS;

    commandFuture =
        commandExecutor.submit(
            () -> {
              TuiTableRenderer.clearLastData();
              // Redirect stderr to prevent raw output from corrupting the TUI
              PrintStream origErr = System.err;
              ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
              System.setErr(new PrintStream(errBuf));
              try {
                boolean handled = dispatcher.dispatch(command);
                if (!handled) {
                  asyncOutputBuffer.add("  Unknown command. Type 'help' for available commands.");
                }
              } catch (Exception e) {
                asyncOutputBuffer.add("  Error: " + e.getMessage());
              } finally {
                System.setErr(origErr);
                String errOutput = errBuf.toString().trim();
                if (!errOutput.isEmpty()) {
                  for (String errLine : errOutput.split("\n")) {
                    addOutputLine("  " + errLine);
                  }
                }
                // Capture ThreadLocal table data before leaving the executor thread
                asyncTableData = TuiTableRenderer.getLastTableData();
                asyncTableHeaders = TuiTableRenderer.getLastTableHeaders();
                asyncMetadataClasses = TuiTableRenderer.getLastMetadataClasses();
                TuiTableRenderer.clearLastData();
                commandRunning = false;
              }
            });
  }

  private void finishAsyncCommand() {
    // Event browser pending: async summary scan completed → enter browser mode
    if (eventBrowserPending) {
      eventBrowserPending = false;
      ResultTab activeTab = tabs.get(activeTabIndex);
      List<Map<String, Object>> summary = asyncTableData;
      asyncTableData = null;
      asyncTableHeaders = null;
      asyncMetadataClasses = null;
      asyncOutputBuffer = null;
      asyncMaxLineWidth = 0;
      commandFuture = null;

      if (summary != null && !summary.isEmpty()) {
        activeTab.tableData = summary;
        enterEventBrowserMode(activeTab);
      } else {
        activeTab.lines.clear();
        activeTab.lines.add("  No events found.");
        activeTab.maxLineWidth = activeTab.lines.get(0).length();
      }
      return;
    }

    ResultTab activeTab = tabs.get(activeTabIndex);
    // Copy buffered output lines into the tab
    for (String line : asyncOutputBuffer) {
      activeTab.lines.add(line);
    }
    activeTab.maxLineWidth = Math.max(activeTab.maxLineWidth, asyncMaxLineWidth);
    asyncOutputBuffer = null;
    asyncMaxLineWidth = 0;
    commandFuture = null;

    activeTab.tableData = asyncTableData;
    activeTab.tableHeaders = asyncTableHeaders;
    activeTab.metadataClassCache = asyncMetadataClasses;
    asyncTableData = null;
    asyncTableHeaders = null;
    asyncMetadataClasses = null;

    activeTab.sortColumn = -1;
    activeTab.sortAscending = true;
    if (activeTab.tableData != null && !activeTab.tableData.isEmpty()) {
      activeTab.selectedRow = 0;
      activeTab.dataStartLine = asyncLinesBeforeDispatch + 1;
      buildDetailTabs(activeTab);
    } else {
      activeTab.selectedRow = -1;
      activeTab.dataStartLine = -1;
      buildDetailTabs(activeTab);
    }

    // Auto-scroll: when table data is present, show selected row (row 0);
    // otherwise scroll to show the latest output
    if (activeTab.dataStartLine >= 0) {
      activeTab.scrollOffset = 0;
    } else {
      activeTab.scrollOffset = Math.max(0, activeTab.lines.size() - resultsAreaHeight);
    }
  }

  // ---- history ----

  private void loadHistory() {
    if (!Files.exists(HISTORY_PATH)) return;
    try {
      List<String> lines = Files.readAllLines(HISTORY_PATH);
      // Keep only the tail if file is larger than MAX_HISTORY
      int start = Math.max(0, lines.size() - MAX_HISTORY);
      for (int i = start; i < lines.size(); i++) {
        String line = lines.get(i).trim();
        if (line.isEmpty()) continue;
        // Strip JLine timestamp prefix (digits followed by colon)
        int colon = line.indexOf(':');
        if (colon > 0 && line.substring(0, colon).chars().allMatch(Character::isDigit)) {
          line = line.substring(colon + 1);
        }
        if (!line.isEmpty()) {
          commandHistory.add(line);
        }
      }
    } catch (IOException ignore) {
    }
  }

  private void saveHistory() {
    try {
      Files.createDirectories(HISTORY_PATH.getParent());
      // Trim to MAX_HISTORY before saving
      List<String> toSave = commandHistory;
      if (toSave.size() > MAX_HISTORY) {
        toSave = toSave.subList(toSave.size() - MAX_HISTORY, toSave.size());
      }
      // Write in JLine format (timestamp:command) for compatibility
      long ts = System.currentTimeMillis();
      List<String> formatted = new ArrayList<>(toSave.size());
      for (String cmd : toSave) {
        formatted.add(ts++ + ":" + cmd);
      }
      Files.write(HISTORY_PATH, formatted);
    } catch (IOException ignore) {
    }
  }

  private void navigateHistory(int direction) {
    if (commandHistory.isEmpty()) return;

    if (direction < 0) {
      // Up — older command
      if (historyIndex < 0) {
        historyIndex = commandHistory.size() - 1;
      } else if (historyIndex > 0) {
        historyIndex--;
      }
    } else {
      // Down — newer command
      if (historyIndex >= 0) {
        historyIndex++;
        if (historyIndex >= commandHistory.size()) {
          historyIndex = -1;
          inputState.clear();
          return;
        }
      }
    }

    if (historyIndex >= 0 && historyIndex < commandHistory.size()) {
      inputState.setText(commandHistory.get(historyIndex));
    }
  }

  // ---- sorting ----

  private void reverseSortIfActive() {
    ResultTab rt = tabs.get(activeTabIndex);
    if (rt.sortColumn >= 0 && rt.tableData != null) {
      rt.sortAscending = !rt.sortAscending;
      applySortAndRerender(rt);
    }
  }

  private void applySortAndRerender(ResultTab tab) {
    if (tab.tableData == null || tab.tableHeaders == null || tab.sortColumn < 0) return;

    // Paginated CP: sort the full backing list and re-paginate
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
      // Re-render header line
      StringBuilder sb = new StringBuilder("  ");
      for (int c = 0; c < tab.cpColumnHeaders.size(); c++) {
        if (c > 0) sb.append("  ");
        sb.append(String.format("%-" + tab.cpColumnWidths[c] + "s", tab.cpColumnHeaders.get(c)));
      }
      String headerLine = sb.toString();
      tab.lines.add(headerLine);
      tab.maxLineWidth = Math.max(tab.maxLineWidth, headerLine.length());
      int pageSize = Math.min(tab.cpAllEntries.size(), Math.max(resultsAreaHeight + 5, 20));
      renderCpPage(tab, 0, pageSize);
      tab.tableData = new ArrayList<>(tab.cpAllEntries.subList(0, pageSize));
      tab.cpRenderedCount = pageSize;
      tab.dataStartLine = 1;
      tab.selectedRow = Math.min(tab.selectedRow, Math.max(0, pageSize - 1));
      tab.scrollOffset = 0;
      tab.filteredIndices = null;
      tab.searchQuery = "";
      tab.detailSearchQuery = "";
      buildDetailTabs(tab);

      return;
    }

    String sortKey = tab.tableHeaders.get(tab.sortColumn);
    boolean asc = tab.sortAscending;

    // Sort tableData (and parallel metadataClassCache if present)
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

    // Re-render the table lines
    tab.lines.clear();
    tab.maxLineWidth = 0;
    tab.lines.add("> " + lastCommand);
    TuiTableRenderer.clearLastData();
    TuiTableRenderer.render(
        sortedData,
        new CommandDispatcher.IO() {
          @Override
          public void println(String s) {
            String stripped = stripAnsi(s);
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
    TuiTableRenderer.clearLastData();

    // Recalculate dataStartLine (command echo + header = 2 lines)
    tab.dataStartLine = 2;
    tab.selectedRow = Math.min(tab.selectedRow, Math.max(0, tab.tableData.size() - 1));
    tab.scrollOffset = 0;
    tab.filteredIndices = null;
    tab.searchQuery = "";
    tab.detailSearchQuery = "";
    buildDetailTabs(tab);
  }

  /**
   * Inject a sort indicator (▲/▼) before the sorted column name in the header line. Finds the
   * column text by matching the header name and prepends the indicator.
   */
  private static String injectSortIndicator(String headerText, ResultTab tab) {
    String colName = tab.tableHeaders.get(tab.sortColumn);
    String indicator = tab.sortAscending ? "\u25B2" : "\u25BC";
    // Find the column name in the header text and prepend indicator
    int pos = headerText.indexOf(colName);
    if (pos >= 0) {
      return headerText.substring(0, pos) + indicator + headerText.substring(pos);
    }
    return headerText;
  }

  /** Compare two strings with numeric-aware ordering. */
  private static int compareNatural(String a, String b) {
    // Try numeric comparison first
    try {
      double da = Double.parseDouble(a);
      double db = Double.parseDouble(b);
      return Double.compare(da, db);
    } catch (NumberFormatException ignore) {
    }
    return a.compareToIgnoreCase(b);
  }

  // ---- scrolling ----

  private void scrollResults(int delta) {
    ResultTab tab = tabs.get(activeTabIndex);
    tab.scrollOffset = Math.max(0, tab.scrollOffset + delta);
    ensureCpEntriesLoaded(tab, tab.scrollOffset + resultsAreaHeight);
    // Upper bound is enforced in renderResults()
  }

  private static String applyHScroll(String line, int hOffset, int visibleWidth) {
    if (hOffset < line.length()) {
      int end = Math.min(hOffset + visibleWidth, line.length());
      return line.substring(hOffset, end);
    }
    return "";
  }

  private static final Style HIGHLIGHT_STYLE = Style.create().bg(Color.YELLOW).fg(Color.BLACK);

  /**
   * Build a Line with highlighted match substrings. Case-insensitive matching on the plain text
   * (ANSI codes already stripped by addOutputLine).
   */
  private static Line buildHighlightedLine(String text, String query, Style baseStyle) {
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

  private void scrollHorizontal(int delta) {
    ResultTab tab = tabs.get(activeTabIndex);
    tab.hScrollOffset = Math.max(0, tab.hScrollOffset + delta);
    // Upper bound is enforced in renderResults()
  }

  private void moveSelectedRow(ResultTab tab, int delta) {
    int newRow;
    if (tab.filteredIndices != null && tab.dataStartLine >= 0) {
      // Only navigate to rows visible in the filter
      newRow = findFilteredRow(tab, tab.selectedRow, delta);
    } else {
      ensureCpEntriesLoaded(tab, tab.selectedRow + delta);
      newRow = Math.max(0, Math.min(tab.tableData.size() - 1, tab.selectedRow + delta));
    }
    if (newRow == tab.selectedRow) return;
    tab.selectedRow = newRow;
    buildDetailTabs(tab);

    // Auto-scroll results to keep selected row visible
    if (tab.filteredIndices != null) {
      // In filtered mode, find the position of this row's line in filteredIndices
      int lineIdx = tab.dataStartLine + newRow;
      int filteredPos = tab.filteredIndices.indexOf(lineIdx);
      if (filteredPos >= 0) {
        if (filteredPos < tab.scrollOffset) {
          tab.scrollOffset = filteredPos;
        } else if (filteredPos >= tab.scrollOffset + resultsAreaHeight) {
          tab.scrollOffset = filteredPos - resultsAreaHeight + 1;
        }
      }
    } else {
      if (newRow < tab.scrollOffset) {
        tab.scrollOffset = newRow;
      } else if (newRow >= tab.scrollOffset + resultsAreaHeight) {
        tab.scrollOffset = newRow - resultsAreaHeight + 1;
      }
    }
  }

  /** Find the next/previous table row whose line is in the filtered indices. */
  private static int findFilteredRow(ResultTab tab, int currentRow, int delta) {
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
    return currentRow; // no filtered row found in that direction
  }

  private void moveDetailCursor(int delta) {
    if (detailLineTypeRefs == null || detailLineTypeRefs.isEmpty()) return;
    int maxLine = detailLineTypeRefs.size() - 1;
    detailCursorLine = Math.max(0, Math.min(maxLine, detailCursorLine + delta));
    // Auto-scroll detail pane to keep cursor visible
    int scrollOffset = getDetailScrollOffset();
    if (detailCursorLine < scrollOffset) {
      setDetailScrollOffset(detailCursorLine);
    } else if (detailCursorLine >= scrollOffset + detailAreaHeight) {
      setDetailScrollOffset(detailCursorLine - detailAreaHeight + 1);
    }
  }

  private void navigateToType(String typeName) {
    ResultTab tab = tabs.get(activeTabIndex);
    if (tab.tableData == null) return;
    // Clear both filters so the target row is visible
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
        // Scroll to show the selected row
        if (i < tab.scrollOffset) {
          tab.scrollOffset = i;
        } else if (i >= tab.scrollOffset + resultsAreaHeight) {
          tab.scrollOffset = i - resultsAreaHeight + 1;
        }
        buildDetailTabs(tab);

        return;
      }
    }
  }

  // ---- completion popup ----

  /**
   * Gather candidates and open the completion popup, or apply directly for single/prefix matches.
   */
  private void openCompletionPopup() {
    if (completionPopupVisible) {
      acceptCompletion();
      return;
    }

    String text = inputState.text();
    int cursor = inputState.cursorPosition();

    TuiParsedLine parsed = new TuiParsedLine(text, cursor);
    List<Candidate> candidates = new ArrayList<>();
    try {
      completer.complete(null, parsed, candidates);
    } catch (Exception e) {
      return;
    }

    if (candidates.isEmpty()) return;

    // On empty input, filter to standalone commands only (exclude pipeline operators
    // like select()/groupBy() and scripting-only keywords)
    if (text.isBlank()) {
      candidates.removeIf(
          c -> c.value().contains("(") || c.value().contains("/") || isScriptingKeyword(c.value()));
      if (candidates.isEmpty()) return;
    }

    candidates.sort(Comparator.comparing(Candidate::value, String.CASE_INSENSITIVE_ORDER));

    // Compute word start: walk back from cursor to find start of current word
    completionWordStart = cursor;
    while (completionWordStart > 0
        && !Character.isWhitespace(text.charAt(completionWordStart - 1))) {
      completionWordStart--;
    }
    completionOriginalWord = text.substring(completionWordStart, cursor);

    if (candidates.size() == 1) {
      applyCompletion(candidates.get(0).value());
      return;
    }

    // Multiple candidates — find common prefix
    String prefix = commonPrefix(candidates);
    if (prefix.length() > completionOriginalWord.length()) {
      applyCompletion(prefix);
      return;
    }

    // Open popup with all candidates
    completionAllCandidates = candidates;
    completionFiltered = new ArrayList<>(candidates);
    completionSelectedIndex = 0;
    completionScrollOffset = 0;
    completionPopupVisible = true;
    completionOriginalInput = text;
  }

  private void closeCompletionPopup(boolean restore) {
    completionPopupVisible = false;
    if (restore && completionOriginalInput != null) {
      inputState.setText(completionOriginalInput);
    }
    completionAllCandidates = null;
    completionFiltered = null;
    completionOriginalInput = null;
    completionOriginalWord = null;
  }

  private void acceptCompletion() {
    if (completionFiltered != null
        && completionSelectedIndex >= 0
        && completionSelectedIndex < completionFiltered.size()) {
      applyCompletion(completionFiltered.get(completionSelectedIndex).value());
    }
    closeCompletionPopup(false);
  }

  private void refilterCompletions() {
    String text = inputState.text();
    int cursor = inputState.cursorPosition();
    int wordEnd = Math.min(cursor, text.length());
    if (completionWordStart > wordEnd) {
      closeCompletionPopup(false);
      return;
    }
    String currentWord = text.substring(completionWordStart, wordEnd);
    if (currentWord.isEmpty()) {
      closeCompletionPopup(false);
      return;
    }
    String lower = currentWord.toLowerCase();
    List<Candidate> filtered = new ArrayList<>();
    for (Candidate c : completionAllCandidates) {
      if (c.value().toLowerCase().startsWith(lower)) {
        filtered.add(c);
      }
    }
    if (filtered.isEmpty()) {
      closeCompletionPopup(false);
      return;
    }
    completionFiltered = filtered;
    completionSelectedIndex = Math.min(completionSelectedIndex, filtered.size() - 1);
    int maxVisible = COMPLETION_MAX_HEIGHT;
    completionScrollOffset =
        Math.min(completionScrollOffset, Math.max(0, filtered.size() - maxVisible));
  }

  private void handleCompletionPopupKey(int key) throws IOException {
    switch (key) {
      case 9: // Tab — accept
      case 13: // Enter
      case 10:
        acceptCompletion();
        return;
      case 27: // Esc or escape sequence
        handleCompletionEscape();
        return;
      case 127: // Backspace
      case 8:
        inputState.deleteBackward();
        refilterCompletions();
        return;
      default:
        if (key >= 32 && key < 127) {
          inputState.insert((char) key);
          historyIndex = -1;
          refilterCompletions();
        } else {
          closeCompletionPopup(false);
        }
        return;
    }
  }

  private void handleCompletionEscape() throws IOException {
    int next = backend.read(50);
    if (next == READ_EXPIRED || next == EOF) {
      // Plain Escape — cancel, restore original input
      closeCompletionPopup(true);
      return;
    }
    if (next == '[') {
      int code = backend.read(50);
      if (code == READ_EXPIRED || code == EOF) return;
      if (code == '3') {
        // ESC [ 3 ~ — Delete (Fn+Backspace on macOS)
        int tilde = backend.read(50);
        if (tilde == '~') {
          inputState.deleteForward();
          refilterCompletions();
        }
        return;
      }
      if (code == 'A') { // Up
        if (completionSelectedIndex > 0) {
          completionSelectedIndex--;
          if (completionSelectedIndex < completionScrollOffset) {
            completionScrollOffset = completionSelectedIndex;
          }
        }
        return;
      }
      if (code == 'B') { // Down
        if (completionSelectedIndex < completionFiltered.size() - 1) {
          completionSelectedIndex++;
          int maxVisible = COMPLETION_MAX_HEIGHT;
          if (completionSelectedIndex >= completionScrollOffset + maxVisible) {
            completionScrollOffset = completionSelectedIndex - maxVisible + 1;
          }
        }
        return;
      }
      // Any other escape sequence — close popup
      closeCompletionPopup(false);
      return;
    }
    // ESC + other char — close popup
    closeCompletionPopup(false);
  }

  private void renderCompletionPopup(Frame frame) {
    if (inputAreaRect == null) return;

    int maxCandidateWidth = 0;
    for (Candidate c : completionFiltered) {
      maxCandidateWidth = Math.max(maxCandidateWidth, c.value().length());
    }

    int popupHeight = Math.min(completionFiltered.size(), COMPLETION_MAX_HEIGHT);
    int popupWidth = Math.min(maxCandidateWidth + 2, COMPLETION_MAX_WIDTH);
    popupWidth = Math.min(popupWidth, frame.area().width());

    // Position: anchor near the word being completed, above the input area
    int x = inputAreaRect.x() + completionWordStart + 2;
    int y = inputAreaRect.y() - popupHeight;
    if (x + popupWidth > frame.area().width()) {
      x = Math.max(0, frame.area().width() - popupWidth);
    }
    if (y < 0) y = 0;

    Rect popupRect = new Rect(x, y, popupWidth, popupHeight);

    Style normalStyle = Style.create().bg(Color.DARK_GRAY).fg(Color.WHITE);
    Style highlightStyle = Style.create().bg(Color.CYAN).fg(Color.BLACK).bold();

    int visibleCount = popupRect.height();
    int start = completionScrollOffset;
    int end = Math.min(start + visibleCount, completionFiltered.size());

    List<Line> lines = new ArrayList<>(end - start);
    for (int i = start; i < end; i++) {
      String value = " " + completionFiltered.get(i).value();
      if (value.length() > popupRect.width()) {
        value = value.substring(0, popupRect.width());
      }
      if (value.length() < popupRect.width()) {
        value = value + " ".repeat(popupRect.width() - value.length());
      }
      Style style = (i == completionSelectedIndex) ? highlightStyle : normalStyle;
      lines.add(Line.from(Span.styled(value, style)));
    }

    Paragraph popup = Paragraph.builder().text(new Text(lines, null)).build();
    frame.renderWidget(popup, popupRect);
  }

  private void applyCompletion(String value) {
    String text = inputState.text();
    int cursor = inputState.cursorPosition();
    String before = text.substring(0, completionWordStart);
    String after = cursor <= text.length() ? text.substring(cursor) : "";
    String newText = before + value + after;
    inputState.clear();
    inputState.insert(newText);
    // Position cursor right after the inserted value
    int target = completionWordStart + value.length();
    inputState.moveCursorToEnd();
    int overshoot = inputState.cursorPosition() - target;
    for (int i = 0; i < overshoot; i++) {
      inputState.moveCursorLeft();
    }
  }

  private static String commonPrefix(List<Candidate> candidates) {
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

  private static final Set<String> SCRIPTING_KEYWORDS = Set.of("if", "elif", "else", "endif");

  private static boolean isScriptingKeyword(String value) {
    return SCRIPTING_KEYWORDS.contains(value);
  }

  // ---- session picker popup ----

  private void openSessionPicker() {
    List<SessionManager.SessionRef> all = sessions.list();
    if (all.size() < 2) return;
    sessionPickerEntries = all;
    // Pre-select the current session
    int currentId = sessions.current().map(r -> r.id).orElse(-1);
    sessionPickerSelectedIndex = 0;
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).id == currentId) {
        sessionPickerSelectedIndex = i;
        break;
      }
    }
    sessionPickerVisible = true;
  }

  private void closeSessionPicker() {
    sessionPickerVisible = false;
    sessionPickerEntries = null;
  }

  private void handleSessionPickerKey(int key) throws IOException {
    switch (key) {
      case 13: // Enter
      case 10:
        if (sessionPickerEntries != null
            && sessionPickerSelectedIndex >= 0
            && sessionPickerSelectedIndex < sessionPickerEntries.size()) {
          SessionManager.SessionRef ref = sessionPickerEntries.get(sessionPickerSelectedIndex);
          sessions.use(String.valueOf(ref.id));
          // Ensure TUI output format for the switched session
          sessions.current().ifPresent(cur -> cur.outputFormat = "tui");
        }
        closeSessionPicker();
        return;
      case 27: // Esc or escape sequence
        {
          int next = backend.read(50);
          if (next == READ_EXPIRED || next == EOF) {
            closeSessionPicker();
            return;
          }
          if (next == '[') {
            int code = backend.read(50);
            if (code == 'A' && sessionPickerSelectedIndex > 0) { // Up
              sessionPickerSelectedIndex--;
              return;
            }
            if (code == 'B'
                && sessionPickerEntries != null
                && sessionPickerSelectedIndex < sessionPickerEntries.size() - 1) { // Down
              sessionPickerSelectedIndex++;
              return;
            }
          }
          closeSessionPicker();
          return;
        }
      default:
        closeSessionPicker();
        return;
    }
  }

  private void renderSessionPicker(Frame frame) {
    if (sessionPickerEntries == null || sessionPickerEntries.isEmpty()) return;

    int currentId = sessions.current().map(r -> r.id).orElse(-1);
    int maxWidth = 0;
    List<String> labels = new ArrayList<>(sessionPickerEntries.size());
    for (SessionManager.SessionRef ref : sessionPickerEntries) {
      String marker = ref.id == currentId ? "* " : "  ";
      String name =
          ref.alias != null ? ref.alias : ref.session.getRecordingPath().getFileName().toString();
      String label = marker + "#" + ref.id + " " + name;
      labels.add(label);
      maxWidth = Math.max(maxWidth, label.length());
    }
    int popupWidth = Math.min(maxWidth + 2, COMPLETION_MAX_WIDTH);
    popupWidth = Math.min(popupWidth, frame.area().width());
    int popupHeight = Math.min(sessionPickerEntries.size(), COMPLETION_MAX_HEIGHT);

    // Center the popup horizontally, place near the top
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
          Line.from(Span.styled(label, i == sessionPickerSelectedIndex ? highlight : normal)));
    }

    Paragraph popup = Paragraph.builder().text(new Text(lines, null)).build();
    frame.renderWidget(popup, popupRect);
  }

  // ---- export popup ----

  private void handleExportPopupKey(int key) throws IOException {
    switch (key) {
      case 13: // Enter — confirm export
      case 10:
        String path = exportPathState.text().trim();
        exportPopupVisible = false;
        if (!path.isEmpty()) {
          performExport(path);
        }
        return;
      case 27: // Esc — cancel
        {
          int next = backend.read(50);
          if (next == READ_EXPIRED || next == EOF) {
            exportPopupVisible = false;
            return;
          }
          if (next == '[') {
            int code = backend.read(50);
            exportPathPristine = false;
            if (code == 'C') { // Right
              exportPathState.moveCursorRight();
              return;
            }
            if (code == 'D') { // Left
              exportPathState.moveCursorLeft();
              return;
            }
            if (code == 'H') { // Home
              exportPathState.moveCursorToStart();
              return;
            }
            if (code == 'F') { // End
              exportPathState.moveCursorToEnd();
              return;
            }
            if (code == '3') {
              int tilde = backend.read(50);
              if (tilde == '~') {
                exportPathState.deleteForward();
              }
              return;
            }
          }
          exportPopupVisible = false;
          return;
        }
      case 127: // Backspace
      case 8:
        exportPathPristine = false;
        exportPathState.deleteBackward();
        return;
      case 1: // Ctrl+A — move to start
        exportPathPristine = false;
        exportPathState.moveCursorToStart();
        return;
      case 5: // Ctrl+E — move to end (re-used within popup)
        exportPathPristine = false;
        exportPathState.moveCursorToEnd();
        return;
      case 21: // Ctrl+U — clear entire line
        exportPathPristine = false;
        exportPathState.clear();
        return;
      default:
        if (key >= 32 && key < 127) {
          if (exportPathPristine) {
            exportPathState.clear();
            exportPathPristine = false;
          }
          exportPathState.insert((char) key);
        }
        return;
    }
  }

  private void renderExportPopup(Frame frame) {
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
    exportInput.renderWithCursor(popupRect, frame.buffer(), exportPathState, frame);
  }

  // ---- cell picker popup ----

  private boolean openCellPicker() {
    if (completionPopupVisible || cellPickerVisible) return false;
    ResultTab tab = tabs.get(activeTabIndex);
    cellPickerEntries = new ArrayList<>();

    if (cpBrowserMode) {
      // CP browser: show columns from both type summary and entries panes
      boolean hasTypes =
          cpTypes != null && cpTypeSelectedIndex >= 0 && cpTypeSelectedIndex < cpTypes.size();
      boolean hasEntries =
          tab.tableData != null && tab.selectedRow >= 0 && tab.selectedRow < tab.tableData.size();
      if (!hasTypes && !hasEntries) return false;

      if (hasTypes) {
        Map<String, Object> typeRow = cpTypes.get(cpTypeSelectedIndex);
        cellPickerEntries.add(new String[] {"\u2500 type \u2500", ""});
        for (String key : typeRow.keySet()) {
          if (isComplexValue(typeRow.get(key))) continue;
          cellPickerEntries.add(new String[] {key, TuiTableRenderer.toCell(typeRow.get(key))});
        }
      }
      if (hasEntries) {
        Map<String, Object> entryRow = tab.tableData.get(tab.selectedRow);
        List<String> headers =
            tab.tableHeaders != null ? tab.tableHeaders : new ArrayList<>(entryRow.keySet());
        cellPickerEntries.add(new String[] {"\u2500 entry \u2500", ""});
        for (String header : headers) {
          if (isComplexValue(entryRow.get(header))) continue;
          cellPickerEntries.add(
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
        if (isComplexValue(sourceRow.get(header))) continue;
        cellPickerEntries.add(
            new String[] {header, TuiTableRenderer.toCell(sourceRow.get(header))});
      }
    }

    // Remove trailing separators with no entries after them
    while (!cellPickerEntries.isEmpty()) {
      String[] last = cellPickerEntries.get(cellPickerEntries.size() - 1);
      if (last[1].isEmpty() && last[0].startsWith("\u2500")) {
        cellPickerEntries.remove(cellPickerEntries.size() - 1);
      } else {
        break;
      }
    }
    if (cellPickerEntries.isEmpty()) return false;
    cellPickerSelectedIndex = 0;
    // Skip separator if it's the first entry
    if (cellPickerEntries.get(0)[1].isEmpty() && cellPickerEntries.get(0)[0].startsWith("\u2500")) {
      cellPickerSelectedIndex = Math.min(1, cellPickerEntries.size() - 1);
    }
    cellPickerScrollOffset = 0;
    cellPickerVisible = true;
    return true;
  }

  private boolean isCellPickerSeparator(int index) {
    if (index < 0 || index >= cellPickerEntries.size()) return false;
    String[] entry = cellPickerEntries.get(index);
    return entry[1].isEmpty() && entry[0].startsWith("\u2500");
  }

  private void handleCellPickerKey(int key) throws IOException {
    switch (key) {
      case 13: // Enter
      case 10:
        if (cellPickerEntries != null
            && cellPickerSelectedIndex >= 0
            && cellPickerSelectedIndex < cellPickerEntries.size()
            && !isCellPickerSeparator(cellPickerSelectedIndex)) {
          String value = cellPickerEntries.get(cellPickerSelectedIndex)[1];
          inputState.insert(value);
          copyToClipboard(value);
        }
        closeCellPicker();
        return;
      case 27: // Esc or escape sequence
        {
          int next = backend.read(50);
          if (next == READ_EXPIRED || next == EOF) {
            closeCellPicker();
            return;
          }
          if (next == '[') {
            int code = backend.read(50);
            if (code == 'A') { // Up
              cellPickerMoveTo(cellPickerSelectedIndex - 1, -1);
              return;
            }
            if (code == 'B') { // Down
              cellPickerMoveTo(cellPickerSelectedIndex + 1, 1);
              return;
            }
          }
          closeCellPicker();
          return;
        }
      default:
        closeCellPicker();
        return;
    }
  }

  private void cellPickerMoveTo(int target, int direction) {
    int size = cellPickerEntries.size();
    // Skip separators
    while (target >= 0 && target < size && isCellPickerSeparator(target)) {
      target += direction;
    }
    if (target < 0 || target >= size) return;
    cellPickerSelectedIndex = target;
    if (cellPickerSelectedIndex < cellPickerScrollOffset) {
      cellPickerScrollOffset = cellPickerSelectedIndex;
    } else if (cellPickerSelectedIndex >= cellPickerScrollOffset + COMPLETION_MAX_HEIGHT) {
      cellPickerScrollOffset = cellPickerSelectedIndex - COMPLETION_MAX_HEIGHT + 1;
    }
  }

  private void closeCellPicker() {
    cellPickerVisible = false;
    cellPickerEntries = null;
  }

  private static void copyToClipboard(String text) {
    try {
      String[] cmd =
          switch (PLATFORM) {
            case MACOS -> new String[] {"pbcopy"};
            case WINDOWS -> new String[] {"clip"};
            case LINUX -> new String[] {"xclip", "-selection", "clipboard"};
          };
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      p.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
      p.getOutputStream().close();
      p.waitFor(2, TimeUnit.SECONDS);
    } catch (Exception ignore) {
      // Clipboard unavailable — silently ignore
    }
  }

  private void renderCellPicker(Frame frame) {
    if (inputAreaRect == null || cellPickerEntries == null) return;

    int nameWidth = 0;
    int valueWidth = 0;
    for (String[] entry : cellPickerEntries) {
      nameWidth = Math.max(nameWidth, entry[0].length());
      valueWidth = Math.max(valueWidth, entry[1].length());
    }
    nameWidth = Math.min(nameWidth + 1, 25);
    valueWidth = Math.min(valueWidth + 1, 25);
    int popupWidth = Math.min(nameWidth + valueWidth + 3, COMPLETION_MAX_WIDTH);
    popupWidth = Math.min(popupWidth, frame.area().width());
    int popupHeight = Math.min(cellPickerEntries.size(), COMPLETION_MAX_HEIGHT);

    int x = inputAreaRect.x() + inputState.cursorPosition() + 2;
    int y = inputAreaRect.y() - popupHeight;
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

    int start = cellPickerScrollOffset;
    int end = Math.min(start + popupHeight, cellPickerEntries.size());
    List<Line> lines = new ArrayList<>(end - start);
    for (int i = start; i < end; i++) {
      if (isCellPickerSeparator(i)) {
        String label = " " + cellPickerEntries.get(i)[0];
        if (label.length() < popupRect.width()) {
          label = label + " ".repeat(popupRect.width() - label.length());
        } else if (label.length() > popupRect.width()) {
          label = label.substring(0, popupRect.width());
        }
        lines.add(Line.from(Span.styled(label, separatorStyle)));
        continue;
      }
      String name = " " + cellPickerEntries.get(i)[0];
      String value = cellPickerEntries.get(i)[1] + " ";
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
      boolean highlight = (i == cellPickerSelectedIndex);
      lines.add(
          Line.from(
              Span.styled(nameStr, highlight ? nameHighlight : nameNormal),
              Span.styled(sep, highlight ? nameHighlight : nameNormal),
              Span.styled(valueStr, highlight ? valueHighlight : valueNormal)));
    }

    Paragraph popup = Paragraph.builder().text(new Text(lines, null)).build();
    frame.renderWidget(popup, popupRect);
  }

  /** ParsedLine adapter for TamboUI's TextInputState. */
  private static final class TuiParsedLine implements ParsedLine {
    private final String line;
    private final int cursor;
    private final List<String> words;
    private final int wordIndex;

    TuiParsedLine(String text, int cursorPos) {
      this.line = text;
      this.cursor = cursorPos;
      // Build word list from text up to cursor
      String upToCursor = text.substring(0, cursorPos);
      List<String> w = new ArrayList<>();
      if (upToCursor.stripLeading().isEmpty()) {
        w.add("");
      } else {
        String[] parts = upToCursor.stripLeading().split("\\s+", -1);
        Collections.addAll(w, parts);
      }
      if (w.isEmpty()) w.add("");
      this.words = Collections.unmodifiableList(w);
      this.wordIndex = words.size() - 1;
    }

    @Override
    public String word() {
      return words.get(wordIndex);
    }

    @Override
    public int wordCursor() {
      return word().length();
    }

    @Override
    public int wordIndex() {
      return wordIndex;
    }

    @Override
    public List<String> words() {
      return words;
    }

    @Override
    public String line() {
      return line;
    }

    @Override
    public int cursor() {
      return cursor;
    }
  }

  // ---- CSV export ----

  private static final DateTimeFormatter EXPORT_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private void exportActiveTab() {
    ResultTab tab = tabs.get(activeTabIndex);
    if (tab.tableData == null || tab.tableData.isEmpty()) {
      showHintMessage("Nothing to export");
      return;
    }
    Path exportDir = Path.of(System.getProperty("user.home"), ".jfr-shell", "exports");
    String fileName = "export-" + LocalDateTime.now().format(EXPORT_TS) + ".csv";
    Path defaultPath = exportDir.resolve(fileName);
    exportPathState.clear();
    exportPathState.insert(defaultPath.toString());
    exportPathPristine = true;
    exportPopupVisible = true;
  }

  private void performExport(String pathStr) {
    ResultTab tab = tabs.get(activeTabIndex);
    if (tab.tableData == null || tab.tableData.isEmpty()) {
      showHintMessage("Nothing to export");
      return;
    }

    // Use full dataset (cpAllEntries) when available, otherwise fall back to tableData
    List<Map<String, Object>> exportData =
        (tab.cpAllEntries != null && !tab.cpAllEntries.isEmpty())
            ? tab.cpAllEntries
            : tab.tableData;
    List<String> headers =
        tab.tableHeaders != null ? tab.tableHeaders : new ArrayList<>(exportData.get(0).keySet());
    // Expand leading ~ to user home directory
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
      showHintMessage("Export failed: " + e.getMessage());
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
      showHintMessage("Export failed: " + e.getMessage());
      return;
    }

    showHintMessage("Exported " + exportData.size() + " rows to " + exportPath);
  }

  private static String csvLine(List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(escapeCsv(values.get(i)));
    }
    return sb.toString();
  }

  private static String csvCell(Object value) {
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

  private static String escapeCsv(String s) {
    if (s == null) return "";
    if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }

  @Override
  public void close() throws Exception {
    saveHistory();
    try {
      dispatcher.getGlobalStore().clear();
    } catch (Exception ignore) {
    }
    try {
      sessions.closeAll();
    } catch (Exception ignore) {
    }
    commandExecutor.shutdownNow();
    jlineTerminal.close();
  }
}

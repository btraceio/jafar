package io.jafar.shell.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.widgets.input.TextInputState;
import io.jafar.shell.core.SessionManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;

/**
 * Shared mutable state for the TUI shell. All extracted TUI components receive a single reference
 * to this context rather than dozens of individual fields.
 *
 * <p>This class holds fields and convenience accessors for common patterns. Static initializers
 * load constants such as tips from resources.
 */
public final class TuiContext {
  // ---- constants ----
  static final int READ_EXPIRED = -2;
  static final int EOF = -1;
  static final Pattern ANSI_ESCAPE = Pattern.compile("\033\\[[0-9;]*[A-Za-z]");
  static final Path HISTORY_PATH =
      Path.of(System.getProperty("user.home"), ".jfr-shell", "history");
  static final int MAX_HISTORY = 5000;
  static final int COMPLETION_MAX_WIDTH = 50;
  static final int COMPLETION_MAX_HEIGHT = 12;
  static final int TIP_ROTATE_TICKS = 300; // ~30s at 100ms per tick
  static final String[] TIPS = loadTips();
  static final String[] SPINNER = {
    "\u280B", "\u2819", "\u2839", "\u2838", "\u283C", "\u2834", "\u2826", "\u2827", "\u2807",
    "\u280F"
  };
  static final int MAX_TAB_TITLE_WIDTH = 25;
  static final int PAUSE_START_TICKS = 50;
  static final int PAUSE_END_TICKS = 20;
  static final int SCROLL_SPEED = 2;
  static final int HINT_MESSAGE_TICKS = 50; // ~5s at 100ms per tick
  static final long BROWSER_NAV_DEBOUNCE_NS = 100_000_000L; // 100ms

  // ---- enums ----

  enum Platform {
    MACOS,
    LINUX,
    WINDOWS
  }

  enum Focus {
    INPUT,
    RESULTS,
    DETAIL,
    SEARCH,
    HISTORY_SEARCH
  }

  static Platform detectPlatform() {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac") || os.contains("darwin")) return Platform.MACOS;
    if (os.contains("win")) return Platform.WINDOWS;
    return Platform.LINUX;
  }

  static final Platform PLATFORM = detectPlatform();

  // ---- ResultTab ----

  public static final class ResultTab {
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
    int sidebarIndex = -1; // sidebar selection index for browser tabs (-1 = not a browser tab)
    List<Map<String, Object>> browserTypes; // sidebar type list for this browser tab
    boolean isEventBrowserTab; // true if this tab was opened in event browser mode
    boolean isMetadataBrowserTab; // true if this tab was opened in metadata browser mode

    // Paginated rendering — render only a page of rows at a time
    List<Map<String, Object>> cpAllEntries; // full entries from provider (null for non-CP)
    List<String> cpColumnHeaders; // column names for manual row formatting
    int[] cpColumnWidths; // column widths for manual row formatting
    int cpRenderedCount; // entries rendered into lines/tableData so far

    ResultTab(String name) {
      this.name = name;
    }
  }

  // ---- TuiParsedLine ----

  static final class TuiParsedLine implements ParsedLine {
    private final String line;
    private final int cursor;
    private final List<String> words;
    private final int wordIndex;

    TuiParsedLine(String text, int cursorPos) {
      this.line = text;
      this.cursor = cursorPos;
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

  // ---- mutable fields ----

  final List<ResultTab> tabs = new ArrayList<>();
  int activeTabIndex;

  final List<String> commandHistory = new ArrayList<>();
  final TextInputState inputState = new TextInputState();
  final TextInputState searchInputState = new TextInputState();

  int historyIndex = -1;
  boolean running = true;
  int resultsAreaHeight = 10;
  int detailAreaHeight = 10;
  Focus focus = Focus.INPUT;
  String lastCommand = "";
  int activeDetailTabIndex;
  List<String> detailTabNames = List.of();
  List<Object> detailTabValues = List.of();
  final List<Integer> detailTabScrollOffsets = new ArrayList<>();
  int detailCursorLine = -1;
  int detailHScrollOffset;
  List<String> detailLineTypeRefs;

  // Marquee animation
  long renderTick;
  long detailMarqueeTick0;

  // Search origin tracking
  Focus searchOriginFocus = Focus.RESULTS;
  boolean searchOriginSidebar;

  // Browser types sidebar filter
  String sidebarSearchQuery = "";
  List<Integer> sidebarFilteredIndices;

  // Temporary hint message
  String hintMessage;
  long hintMessageTick;

  // History search state
  String historySearchQuery = "";
  int historySearchIndex = -1;
  String historySearchSavedInput = "";
  boolean historySearchFailing;

  // Completion state
  int completionWordStart;
  String completionOriginalWord;
  List<Candidate> completionAllCandidates;
  List<Candidate> completionFiltered;
  int completionSelectedIndex;
  int completionScrollOffset;
  boolean completionPopupVisible;
  String completionOriginalInput;
  Rect inputAreaRect;

  // Cell picker state
  boolean cellPickerVisible;
  List<String[]> cellPickerEntries;
  int cellPickerSelectedIndex;
  int cellPickerScrollOffset;

  // Session picker state
  boolean sessionPickerVisible;
  List<SessionManager.SessionRef> sessionPickerEntries;
  int sessionPickerSelectedIndex;

  // Export popup state
  boolean exportPopupVisible;
  boolean exportPathPristine;
  final TextInputState exportPathState = new TextInputState();

  // Browser mode
  boolean browserMode;
  boolean eventBrowserMode;
  boolean metadataBrowserMode;
  List<Map<String, Object>> sidebarTypes;
  int sidebarSelectedIndex;
  int sidebarScrollOffset;
  int sidebarAreaHeight;
  boolean sidebarFocused;
  long browserNavTime;
  String browserNavPending;
  boolean browserNavKeepFocus;

  // Metadata browser state
  List<String> metadataBrowserLineRefs;
  Map<String, Map<String, Object>> metadataByName;

  // Async command execution state
  volatile boolean commandRunning;
  long commandStartTick;
  Future<?> commandFuture;
  List<String> asyncOutputBuffer;
  int asyncMaxLineWidth;
  int asyncLinesBeforeDispatch;
  volatile List<Map<String, Object>> asyncTableData;
  volatile List<String> asyncTableHeaders;
  volatile List<Map<String, Object>> asyncMetadataClasses;
  volatile boolean eventBrowserPending;

  // ---- convenience accessors ----

  ResultTab activeTab() {
    return tabs.get(activeTabIndex);
  }

  static int effectiveLineCount(ResultTab tab) {
    return tab.filteredIndices != null ? tab.filteredIndices.size() : tab.lines.size();
  }

  static String effectiveLine(ResultTab tab, int i) {
    if (tab.filteredIndices != null) {
      return tab.lines.get(tab.filteredIndices.get(i));
    }
    return tab.lines.get(i);
  }

  static int effectiveMaxLineWidth(ResultTab tab) {
    return tab.filteredIndices != null ? tab.filteredMaxLineWidth : tab.maxLineWidth;
  }

  void showHintMessage(String message) {
    hintMessage = message;
    hintMessageTick = renderTick;
  }

  static String stripAnsi(String s) {
    return ANSI_ESCAPE.matcher(s).replaceAll("");
  }

  String getSelectedSidebarName() {
    if (sidebarTypes != null
        && sidebarSelectedIndex >= 0
        && sidebarSelectedIndex < sidebarTypes.size()) {
      return String.valueOf(sidebarTypes.get(sidebarSelectedIndex).getOrDefault("name", ""));
    }
    return "";
  }

  boolean isCellPickerSeparator(int index) {
    if (index < 0 || index >= cellPickerEntries.size()) return false;
    String[] entry = cellPickerEntries.get(index);
    return entry[1].isEmpty() && entry[0].startsWith("\u2500");
  }

  // ---- tips loader ----

  private static String[] loadTips() {
    try (var in = TuiContext.class.getResourceAsStream("/tips.txt")) {
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
}

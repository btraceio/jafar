package io.jafar.shell.tui;

import io.jafar.shell.core.SessionManager;
import io.jafar.shell.tui.TuiContext.Focus;
import io.jafar.shell.tui.TuiContext.ResultTab;
import java.io.IOException;

/**
 * Handles all key input for the TUI shell. Dispatches to focus-specific handlers and manages escape
 * sequences, modifier keys, and popup interactions.
 */
public final class TuiKeyHandler {

  /** Functional interface for reading a key with timeout from the terminal backend. */
  @FunctionalInterface
  interface KeyReader {
    int read(int timeoutMs) throws IOException;
  }

  // macOS Opt+R/D/C/S Unicode code points (US keyboard layout)
  private static final int MAC_OPT_R = 0x00AE; // ®
  private static final int MAC_OPT_D = 0x2202; // ∂
  private static final int MAC_OPT_C = 0x00E7; // ç
  private static final int MAC_OPT_S = 0x00DF; // ß
  private static final int MAC_OPT_T = 0x2020; // †

  // Modifier codes in CSI sequences: ESC [ 1 ; <mod> <dir>
  private static final int MOD_NONE = 0;
  private static final int MOD_SHIFT = '2';
  private static final int MOD_ALT = '3';
  private static final int MOD_CTRL = '5';

  // macOS Opt+digit Unicode code points (US keyboard layout)
  private static final int[] MAC_OPT_DIGITS = {
    0x00A1, 0x2122, 0x00A3, 0x00A2, 0x221E, 0x00A7, 0x00B6, 0x2022, 0x00AA,
  };

  private final TuiContext ctx;
  private final KeyReader backend;
  private final TuiBrowserController browser;
  private final TuiCommandExecutor executor;
  private final TuiDetailBuilder detailBuilder;
  private final SessionManager sessions;

  TuiKeyHandler(
      TuiContext ctx,
      KeyReader backend,
      TuiBrowserController browser,
      TuiCommandExecutor executor,
      TuiDetailBuilder detailBuilder,
      SessionManager sessions) {
    this.ctx = ctx;
    this.backend = backend;
    this.browser = browser;
    this.executor = executor;
    this.detailBuilder = detailBuilder;
    this.sessions = sessions;
  }

  void handleKey(int key) throws IOException {
    // Popup intercepts
    if (ctx.sessionPickerVisible) {
      handleSessionPickerKey(key);
      return;
    }
    if (ctx.completionPopupVisible) {
      handleCompletionPopupKey(key);
      return;
    }
    if (ctx.cellPickerVisible) {
      handleCellPickerKey(key);
      return;
    }
    if (ctx.exportPopupVisible) {
      handleExportPopupKey(key);
      return;
    }

    // Global keys
    switch (key) {
      case 3: // Ctrl+C
        if (ctx.focus == Focus.HISTORY_SEARCH) {
          ctx.inputState.setText(ctx.historySearchSavedInput);
          ctx.focus = Focus.INPUT;
        } else if (ctx.focus == Focus.SEARCH) {
          executor.cancelSearch();
        } else if (ctx.focus == Focus.DETAIL) {
          ctx.focus = Focus.RESULTS;
        } else if (ctx.focus == Focus.RESULTS) {
          ctx.focus = Focus.INPUT;
        } else if (ctx.inputState.text().isEmpty()) {
          ctx.running = false;
        } else {
          ctx.inputState.clear();
        }
        return;
      case 4: // Ctrl+D
        if (ctx.focus == Focus.INPUT && ctx.inputState.text().isEmpty()) {
          ctx.running = false;
        }
        return;
      case 5: // Ctrl+E
        if (ctx.focus == Focus.RESULTS || ctx.focus == Focus.DETAIL) {
          executor.exportActiveTab();
        } else if (ctx.focus == Focus.INPUT) {
          ctx.inputState.moveCursorToEnd();
        }
        return;
      case 16: // Ctrl+P
        executor.togglePin();
        return;
      case 9: // Tab
        if (ctx.focus != Focus.INPUT) {
          ctx.focus = Focus.INPUT;
        }
        executor.openCompletionPopup();
        return;
      case 27: // ESC
        handleEscapeSequence();
        return;
      case 18: // Ctrl+R
        if (ctx.focus == Focus.HISTORY_SEARCH) {
          if (!ctx.historySearchQuery.isEmpty()) {
            String current =
                ctx.historySearchIndex >= 0 && ctx.historySearchIndex < ctx.commandHistory.size()
                    ? ctx.commandHistory.get(ctx.historySearchIndex)
                    : "";
            int match =
                executor.findDistinctHistoryMatch(
                    ctx.historySearchQuery, ctx.historySearchIndex - 1, current);
            if (match < 0) {
              match =
                  executor.findDistinctHistoryMatch(
                      ctx.historySearchQuery, ctx.commandHistory.size() - 1, current);
            }
            if (match >= 0) {
              ctx.historySearchIndex = match;
              ctx.historySearchFailing = false;
              ctx.inputState.setText(ctx.commandHistory.get(match));
            }
          }
        } else {
          if (ctx.focus != Focus.INPUT) {
            ctx.focus = Focus.INPUT;
          }
          ctx.historySearchSavedInput = ctx.inputState.text();
          ctx.historySearchQuery = "";
          ctx.historySearchIndex = ctx.commandHistory.size();
          ctx.historySearchFailing = false;
          ctx.focus = Focus.HISTORY_SEARCH;
        }
        return;
      case 6: // Ctrl+F
      case 31: // Ctrl+/
        if (ctx.focus == Focus.RESULTS || ctx.focus == Focus.DETAIL) {
          executor.enterSearchMode();
        }
        return;
      default:
        break;
    }

    // Opt/Alt+1-9: switch detail tab
    int detailIdx = macOptDigit(key);
    if (detailIdx >= 0 && detailIdx < ctx.detailTabNames.size()) {
      ctx.activeDetailTabIndex = detailIdx;
      return;
    }

    // macOS Opt+R/D/C/S
    if (key == MAC_OPT_R) {
      if (ctx.focus == Focus.RESULTS) {
        executor.reverseSortIfActive();
      } else {
        ctx.focus = Focus.RESULTS;
      }
      return;
    }
    if (key == MAC_OPT_D && !ctx.detailTabNames.isEmpty()) {
      ctx.focus = Focus.DETAIL;
      return;
    }
    if (key == MAC_OPT_C) {
      ctx.focus = Focus.INPUT;
      return;
    }
    if (key == MAC_OPT_S) {
      executor.openSessionPicker();
      return;
    }
    if (key == MAC_OPT_T) {
      handleAltT();
      return;
    }

    // {}: switch result tabs
    if (ctx.focus != Focus.INPUT
        && ctx.focus != Focus.SEARCH
        && ctx.focus != Focus.HISTORY_SEARCH) {
      if (key == '{' && ctx.tabs.size() > 1) {
        executor.switchTab((ctx.activeTabIndex - 1 + ctx.tabs.size()) % ctx.tabs.size());
        return;
      }
      if (key == '}' && ctx.tabs.size() > 1) {
        executor.switchTab((ctx.activeTabIndex + 1) % ctx.tabs.size());
        return;
      }
    }

    // Focus-specific keys
    if (ctx.focus == Focus.HISTORY_SEARCH) {
      handleHistorySearchKey(key);
    } else if (ctx.focus == Focus.SEARCH) {
      handleSearchKey(key);
    } else if (ctx.focus == Focus.DETAIL) {
      handleDetailKey(key);
    } else if (ctx.focus == Focus.RESULTS) {
      handleResultsKey(key);
    } else {
      handleInputKey(key);
    }
  }

  private void handleResultsKey(int key) {
    switch (key) {
      case 13:
      case 10:
        if (ctx.browserMode && ctx.sidebarFocused) {
          ctx.browserNavPending = null;
          String name = browser.getSelectedSidebarName();
          if (!name.isEmpty()) browser.loadBrowserEntries(name, false);
        } else if (ctx.metadataBrowserMode
            && ctx.metadataBrowserLineRefs != null
            && ctx.activeTab().selectedRow >= 0
            && ctx.activeTab().selectedRow < ctx.metadataBrowserLineRefs.size()) {
          String targetType = ctx.metadataBrowserLineRefs.get(ctx.activeTab().selectedRow);
          if (targetType != null) {
            browser.navigateToSidebarType(targetType);
          }
        } else {
          if (!ctx.detailTabNames.isEmpty()) {
            ctx.detailHScrollOffset = 0;
          }
          ctx.focus = ctx.detailTabNames.isEmpty() ? Focus.INPUT : Focus.DETAIL;
        }
        break;
      case '/':
        executor.enterSearchMode();
        break;
      case '<':
      case '>':
        {
          ResultTab rt = ctx.activeTab();
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
            executor.applySortAndRerender(rt);
          }
        }
        break;
      case '[':
        if (!ctx.detailTabNames.isEmpty()) {
          ctx.activeDetailTabIndex =
              (ctx.activeDetailTabIndex - 1 + ctx.detailTabNames.size())
                  % ctx.detailTabNames.size();
        }
        break;
      case ']':
        if (!ctx.detailTabNames.isEmpty()) {
          ctx.activeDetailTabIndex = (ctx.activeDetailTabIndex + 1) % ctx.detailTabNames.size();
        }
        break;
      default:
        if (key == '@') {
          ctx.focus = Focus.INPUT;
          if (!executor.openCellPicker()) {
            ctx.inputState.insert('@');
          }
          break;
        }
        if (key >= 32 && key < 127) {
          ctx.focus = Focus.INPUT;
          ctx.inputState.insert((char) key);
          ctx.historyIndex = -1;
        }
        break;
    }
  }

  private void handleAltT() {
    if (ctx.focus == Focus.DETAIL
        && ctx.detailLineTypeRefs != null
        && ctx.detailCursorLine >= 0
        && ctx.detailCursorLine < ctx.detailLineTypeRefs.size()) {
      String ref = ctx.detailLineTypeRefs.get(ctx.detailCursorLine);
      if (ref != null && ref.startsWith("thread:")) {
        executor.filterByThread(ref.substring("thread:".length()));
      }
    } else {
      executor.clearThreadFilter();
    }
  }

  private void handleDetailKey(int key) {
    switch (key) {
      case '/':
        executor.enterSearchMode();
        break;
      case 13:
      case 10:
        if (ctx.detailLineTypeRefs != null
            && ctx.detailCursorLine >= 0
            && ctx.detailCursorLine < ctx.detailLineTypeRefs.size()) {
          String targetType = ctx.detailLineTypeRefs.get(ctx.detailCursorLine);
          if (targetType != null) {
            browser.navigateToType(targetType);
            break;
          }
        }
        ctx.focus = Focus.INPUT;
        break;
      case '[':
        if (!ctx.detailTabNames.isEmpty()) {
          ctx.activeDetailTabIndex =
              (ctx.activeDetailTabIndex - 1 + ctx.detailTabNames.size())
                  % ctx.detailTabNames.size();
        }
        break;
      case ']':
        if (!ctx.detailTabNames.isEmpty()) {
          ctx.activeDetailTabIndex = (ctx.activeDetailTabIndex + 1) % ctx.detailTabNames.size();
        }
        break;
      default:
        if (key == '@') {
          ctx.focus = Focus.INPUT;
          if (!executor.openCellPicker()) {
            ctx.inputState.insert('@');
          }
          break;
        }
        if (key >= 32 && key < 127) {
          ctx.focus = Focus.INPUT;
          ctx.inputState.insert((char) key);
          ctx.historyIndex = -1;
        }
        break;
    }
  }

  private void handleInputKey(int key) {
    switch (key) {
      case 13:
      case 10:
        executor.submitCommand();
        break;
      case 127:
      case 8:
        ctx.inputState.deleteBackward();
        break;
      default:
        if (key == '@' && executor.openCellPicker()) {
          break;
        }
        if (key >= 32 && key < 127) {
          ctx.inputState.insert((char) key);
          ctx.historyIndex = -1;
        }
        break;
    }
  }

  private void handleSearchKey(int key) {
    ResultTab tab = ctx.activeTab();
    switch (key) {
      case 12: // Ctrl+L
        {
          String query = ctx.searchInputState.text();
          executor.applySearchFilter(tab, query);
          tab.detailSearchQuery = query;
          if (ctx.browserMode) {
            executor.applySidebarFilter(query);
          }
          ctx.focus = ctx.searchOriginFocus;
        }
        break;
      case 13:
      case 10:
        if (ctx.searchOriginFocus == Focus.DETAIL) {
          tab.detailSearchQuery = ctx.searchInputState.text();
        } else if (ctx.searchOriginSidebar) {
          executor.applySidebarFilter(ctx.searchInputState.text());
        } else {
          executor.applySearchFilter(tab, ctx.searchInputState.text());
        }
        ctx.focus = ctx.searchOriginFocus;
        break;
      case 127:
      case 8:
        if (ctx.searchInputState.text().isEmpty()) {
          executor.cancelSearch();
        } else {
          ctx.searchInputState.deleteBackward();
          executor.applyLiveSearchFilter(tab, ctx.searchInputState.text());
        }
        break;
      default:
        if (key >= 32 && key < 127) {
          ctx.searchInputState.insert((char) key);
          executor.applyLiveSearchFilter(tab, ctx.searchInputState.text());
        }
        break;
    }
  }

  private void handleHistorySearchKey(int key) {
    switch (key) {
      case 13:
      case 10:
        ctx.focus = Focus.INPUT;
        break;
      case 127:
      case 8:
        if (ctx.historySearchQuery.isEmpty()) {
          ctx.inputState.setText(ctx.historySearchSavedInput);
          ctx.focus = Focus.INPUT;
        } else {
          ctx.historySearchQuery =
              ctx.historySearchQuery.substring(0, ctx.historySearchQuery.length() - 1);
          int match =
              executor.findHistoryMatch(ctx.historySearchQuery, ctx.commandHistory.size() - 1);
          if (match >= 0) {
            ctx.historySearchIndex = match;
            ctx.historySearchFailing = false;
            ctx.inputState.setText(ctx.commandHistory.get(match));
          } else if (ctx.historySearchQuery.isEmpty()) {
            ctx.historySearchIndex = ctx.commandHistory.size();
            ctx.historySearchFailing = false;
            ctx.inputState.setText(ctx.historySearchSavedInput);
          } else {
            ctx.historySearchIndex = -1;
            ctx.historySearchFailing = true;
          }
        }
        break;
      default:
        if (key >= 32 && key < 127) {
          ctx.historySearchQuery += (char) key;
          int match = executor.findHistoryMatch(ctx.historySearchQuery, ctx.historySearchIndex);
          if (match < 0) {
            match =
                executor.findHistoryMatch(ctx.historySearchQuery, ctx.commandHistory.size() - 1);
          }
          if (match >= 0) {
            ctx.historySearchIndex = match;
            ctx.historySearchFailing = false;
            ctx.inputState.setText(ctx.commandHistory.get(match));
          } else {
            ctx.historySearchFailing = true;
          }
        }
        break;
    }
  }

  // ---- escape sequences ----

  private void handleEscapeSequence() throws IOException {
    int next = backend.read(50);
    if (next == TuiContext.READ_EXPIRED || next == TuiContext.EOF) {
      // Plain Escape
      if (ctx.focus == Focus.HISTORY_SEARCH) {
        ctx.inputState.setText(ctx.historySearchSavedInput);
        ctx.focus = Focus.INPUT;
      } else if (ctx.focus == Focus.SEARCH) {
        executor.cancelSearch();
      } else if (ctx.focus == Focus.DETAIL) {
        ResultTab dtab = ctx.activeTab();
        if (dtab.detailSearchQuery != null && !dtab.detailSearchQuery.isEmpty()) {
          dtab.detailSearchQuery = "";
        } else {
          ctx.focus = Focus.RESULTS;
        }
      } else if (ctx.focus == Focus.RESULTS && ctx.browserMode) {
        if (!ctx.sidebarFocused) {
          ResultTab escTab = ctx.activeTab();
          if (escTab.filteredIndices != null) {
            escTab.searchQuery = "";
            escTab.filteredIndices = null;
            escTab.filteredMaxLineWidth = 0;
            escTab.scrollOffset = 0;
          } else {
            ctx.sidebarFocused = true;
          }
        } else {
          if (ctx.sidebarFilteredIndices != null) {
            ctx.sidebarSearchQuery = "";
            ctx.sidebarFilteredIndices = null;
          } else {
            if (ctx.metadataBrowserMode) {
              browser.exitMetadataBrowserMode();
            } else if (ctx.eventBrowserMode) {
              browser.exitEventBrowserMode();
            } else {
              browser.exitCpBrowserMode();
            }
            ctx.focus = Focus.INPUT;
          }
        }
      } else if (ctx.focus == Focus.RESULTS) {
        ResultTab escTab = ctx.activeTab();
        if (escTab.filteredIndices != null) {
          escTab.searchQuery = "";
          escTab.filteredIndices = null;
          escTab.filteredMaxLineWidth = 0;
          escTab.scrollOffset = 0;
        } else {
          ctx.focus = Focus.INPUT;
        }
      } else {
        ctx.inputState.clear();
      }
      return;
    }

    // ESC 1-9 — switch detail tab
    if (next >= '1' && next <= '9') {
      int tabIdx = next - '1';
      if (tabIdx < ctx.detailTabNames.size()) {
        ctx.activeDetailTabIndex = tabIdx;
      }
      return;
    }
    // ESC b / ESC f — Option+Left/Right
    if (next == 'b' && ctx.tabs.size() > 1) {
      executor.switchTab((ctx.activeTabIndex - 1 + ctx.tabs.size()) % ctx.tabs.size());
      return;
    }
    if (next == 'f' && ctx.tabs.size() > 1) {
      executor.switchTab((ctx.activeTabIndex + 1) % ctx.tabs.size());
      return;
    }
    // ESC r/d/c/s — Alt+R/D/C/S on Linux
    if (next == 'r') {
      if (ctx.focus == Focus.RESULTS) {
        executor.reverseSortIfActive();
      } else {
        ctx.focus = Focus.RESULTS;
      }
      return;
    }
    if (next == 'd' && !ctx.detailTabNames.isEmpty()) {
      ctx.focus = Focus.DETAIL;
      return;
    }
    if (next == 'c') {
      ctx.focus = Focus.INPUT;
      return;
    }
    if (next == 's') {
      executor.openSessionPicker();
      return;
    }
    if (next == 't') {
      handleAltT();
      return;
    }
    if (next != '[') return;

    int code = backend.read(50);
    if (code == TuiContext.READ_EXPIRED || code == TuiContext.EOF) return;

    // ESC [ Z — Shift+Tab
    if (code == 'Z') {
      if (ctx.focus == Focus.INPUT) {
        ctx.focus = Focus.RESULTS;
      } else if (ctx.focus == Focus.RESULTS) {
        if (!ctx.detailTabNames.isEmpty()) {
          ctx.focus = Focus.DETAIL;
        } else {
          ctx.focus = Focus.INPUT;
        }
      } else {
        ctx.focus = Focus.INPUT;
      }
      return;
    }

    // Simple sequences: ESC [ <letter>
    if (code >= 'A' && code <= 'Z') {
      dispatchArrow(code, MOD_NONE);
      return;
    }

    // Parameterized: ESC [ <digit> ...
    if (code >= '0' && code <= '9') {
      int second = backend.read(50);
      if (second == TuiContext.READ_EXPIRED || second == TuiContext.EOF) return;

      if (second == '~') {
        if (code == '3') {
          if (ctx.focus == Focus.SEARCH) ctx.searchInputState.deleteForward();
          else ctx.inputState.deleteForward();
        } else if (code == '5') executor.scrollResults(-ctx.resultsAreaHeight);
        else if (code == '6') executor.scrollResults(ctx.resultsAreaHeight);
      } else if (second == ';') {
        int mod = backend.read(50);
        if (mod == TuiContext.READ_EXPIRED || mod == TuiContext.EOF) return;
        int dir = backend.read(50);
        if (dir == TuiContext.READ_EXPIRED || dir == TuiContext.EOF) return;
        dispatchArrow(dir, mod);
      }
    }
  }

  private void dispatchArrow(int direction, int modifier) {
    // Shift+Up/Down: history
    if (modifier == MOD_SHIFT && (direction == 'A' || direction == 'B')) {
      ctx.focus = Focus.INPUT;
      executor.navigateHistory(direction == 'A' ? -1 : 1);
      return;
    }

    // Ctrl+Up/Down: fast scroll
    if (modifier == MOD_CTRL && (direction == 'A' || direction == 'B')) {
      if (ctx.focus == Focus.DETAIL) {
        int delta = direction == 'A' ? -ctx.detailAreaHeight : ctx.detailAreaHeight;
        detailBuilder.setDetailScrollOffset(
            Math.max(0, detailBuilder.getDetailScrollOffset() + delta));
      } else {
        executor.scrollResults(direction == 'A' ? -ctx.resultsAreaHeight : ctx.resultsAreaHeight);
      }
      return;
    }

    // Ctrl/Alt+Left/Right: tab switch
    if ((modifier == MOD_CTRL || modifier == MOD_ALT)
        && (direction == 'C' || direction == 'D')
        && ctx.tabs.size() > 1) {
      if (direction == 'D') {
        executor.switchTab((ctx.activeTabIndex - 1 + ctx.tabs.size()) % ctx.tabs.size());
      } else {
        executor.switchTab((ctx.activeTabIndex + 1) % ctx.tabs.size());
      }
      return;
    }

    if (ctx.focus == Focus.DETAIL) {
      boolean hasCursor = ctx.detailLineTypeRefs != null && ctx.detailCursorLine >= 0;
      switch (direction) {
        case 'A':
          if (hasCursor) {
            detailBuilder.moveDetailCursor(-1);
          } else {
            detailBuilder.setDetailScrollOffset(
                Math.max(0, detailBuilder.getDetailScrollOffset() - 1));
          }
          break;
        case 'B':
          if (hasCursor) {
            detailBuilder.moveDetailCursor(1);
          } else {
            detailBuilder.setDetailScrollOffset(detailBuilder.getDetailScrollOffset() + 1);
          }
          break;
        case 'C':
          {
            int hStep = (modifier == MOD_SHIFT) ? 20 : 4;
            ctx.detailHScrollOffset += hStep;
          }
          break;
        case 'D':
          {
            int hStep = (modifier == MOD_SHIFT) ? 20 : 4;
            ctx.detailHScrollOffset = Math.max(0, ctx.detailHScrollOffset - hStep);
          }
          break;
        default:
          break;
      }
    } else if (ctx.focus == Focus.RESULTS) {
      if (ctx.browserMode && ctx.sidebarFocused) {
        browser.dispatchSidebarArrow(direction);
        return;
      }
      ResultTab rt = ctx.activeTab();
      boolean hasTable = rt.tableData != null && rt.selectedRow >= 0;
      int hStep = (modifier == MOD_SHIFT) ? 20 : 4;
      switch (direction) {
        case 'A':
          if (hasTable) {
            executor.moveSelectedRow(rt, -1);
          } else if (ctx.metadataBrowserMode && rt.selectedRow >= 0) {
            detailBuilder.moveMetadataCursor(rt, -1);
          } else {
            executor.scrollResults(-1);
          }
          break;
        case 'B':
          if (hasTable) {
            executor.moveSelectedRow(rt, 1);
          } else if (ctx.metadataBrowserMode && rt.selectedRow >= 0) {
            detailBuilder.moveMetadataCursor(rt, 1);
          } else {
            executor.scrollResults(1);
          }
          break;
        case 'C':
          executor.scrollHorizontal(hStep);
          break;
        case 'D':
          if (ctx.browserMode && !ctx.sidebarFocused && rt.hScrollOffset == 0) {
            ctx.sidebarFocused = true;
          } else {
            executor.scrollHorizontal(-hStep);
          }
          break;
        case 'H':
          ctx.activeTab().scrollOffset = 0;
          break;
        case 'F':
          ctx.activeTab().scrollOffset = Integer.MAX_VALUE;
          break;
        default:
          break;
      }
    } else {
      // Input pane
      if (modifier == MOD_SHIFT) {
        switch (direction) {
          case 'C':
            executor.scrollHorizontal(10);
            break;
          case 'D':
            executor.scrollHorizontal(-10);
            break;
          default:
            break;
        }
      } else {
        switch (direction) {
          case 'C':
            ctx.inputState.moveCursorRight();
            break;
          case 'D':
            ctx.inputState.moveCursorLeft();
            break;
          case 'H':
            ctx.inputState.moveCursorToStart();
            break;
          case 'F':
            ctx.inputState.moveCursorToEnd();
            break;
          default:
            break;
        }
      }
    }
  }

  // ---- popup key handlers ----

  private void handleCompletionPopupKey(int key) throws IOException {
    switch (key) {
      case 9:
      case 13:
      case 10:
        executor.acceptCompletion();
        return;
      case 27:
        handleCompletionEscape();
        return;
      case 127:
      case 8:
        ctx.inputState.deleteBackward();
        executor.refilterCompletions();
        return;
      default:
        if (key >= 32 && key < 127) {
          ctx.inputState.insert((char) key);
          ctx.historyIndex = -1;
          executor.refilterCompletions();
        } else {
          executor.closeCompletionPopup(false);
        }
        return;
    }
  }

  private void handleCompletionEscape() throws IOException {
    int next = backend.read(50);
    if (next == TuiContext.READ_EXPIRED || next == TuiContext.EOF) {
      executor.closeCompletionPopup(true);
      return;
    }
    if (next == '[') {
      int code = backend.read(50);
      if (code == TuiContext.READ_EXPIRED || code == TuiContext.EOF) return;
      if (code == '3') {
        int tilde = backend.read(50);
        if (tilde == '~') {
          ctx.inputState.deleteForward();
          executor.refilterCompletions();
        }
        return;
      }
      if (code == 'A') {
        if (ctx.completionSelectedIndex > 0) {
          ctx.completionSelectedIndex--;
          if (ctx.completionSelectedIndex < ctx.completionScrollOffset) {
            ctx.completionScrollOffset = ctx.completionSelectedIndex;
          }
        }
        return;
      }
      if (code == 'B') {
        if (ctx.completionSelectedIndex < ctx.completionFiltered.size() - 1) {
          ctx.completionSelectedIndex++;
          int maxVisible = TuiContext.COMPLETION_MAX_HEIGHT;
          if (ctx.completionSelectedIndex >= ctx.completionScrollOffset + maxVisible) {
            ctx.completionScrollOffset = ctx.completionSelectedIndex - maxVisible + 1;
          }
        }
        return;
      }
      executor.closeCompletionPopup(false);
      return;
    }
    executor.closeCompletionPopup(false);
  }

  private void handleCellPickerKey(int key) throws IOException {
    switch (key) {
      case 13:
      case 10:
        if (ctx.cellPickerEntries != null
            && ctx.cellPickerSelectedIndex >= 0
            && ctx.cellPickerSelectedIndex < ctx.cellPickerEntries.size()
            && !executor.isCellPickerSeparator(ctx.cellPickerSelectedIndex)) {
          String value = ctx.cellPickerEntries.get(ctx.cellPickerSelectedIndex)[1];
          ctx.inputState.insert(value);
          TuiCommandExecutor.copyToClipboard(value);
        }
        executor.closeCellPicker();
        return;
      case 27:
        {
          int next = backend.read(50);
          if (next == TuiContext.READ_EXPIRED || next == TuiContext.EOF) {
            executor.closeCellPicker();
            return;
          }
          if (next == '[') {
            int code = backend.read(50);
            if (code == 'A') {
              executor.cellPickerMoveTo(ctx.cellPickerSelectedIndex - 1, -1);
              return;
            }
            if (code == 'B') {
              executor.cellPickerMoveTo(ctx.cellPickerSelectedIndex + 1, 1);
              return;
            }
          }
          executor.closeCellPicker();
          return;
        }
      default:
        executor.closeCellPicker();
        return;
    }
  }

  private void handleSessionPickerKey(int key) throws IOException {
    switch (key) {
      case 13:
      case 10:
        if (ctx.sessionPickerEntries != null
            && ctx.sessionPickerSelectedIndex >= 0
            && ctx.sessionPickerSelectedIndex < ctx.sessionPickerEntries.size()) {
          var ref = ctx.sessionPickerEntries.get(ctx.sessionPickerSelectedIndex);
          sessions.use(String.valueOf(ref.id));
          sessions.current().ifPresent(cur -> cur.outputFormat = "tui");
        }
        executor.closeSessionPicker();
        return;
      case 27:
        {
          int next = backend.read(50);
          if (next == TuiContext.READ_EXPIRED || next == TuiContext.EOF) {
            executor.closeSessionPicker();
            return;
          }
          if (next == '[') {
            int code = backend.read(50);
            if (code == 'A' && ctx.sessionPickerSelectedIndex > 0) {
              ctx.sessionPickerSelectedIndex--;
              return;
            }
            if (code == 'B'
                && ctx.sessionPickerEntries != null
                && ctx.sessionPickerSelectedIndex < ctx.sessionPickerEntries.size() - 1) {
              ctx.sessionPickerSelectedIndex++;
              return;
            }
          }
          executor.closeSessionPicker();
          return;
        }
      default:
        executor.closeSessionPicker();
        return;
    }
  }

  private void handleExportPopupKey(int key) throws IOException {
    switch (key) {
      case 13:
      case 10:
        String path = ctx.exportPathState.text().trim();
        ctx.exportPopupVisible = false;
        if (!path.isEmpty()) {
          executor.performExport(path);
        }
        return;
      case 27:
        {
          int next = backend.read(50);
          if (next == TuiContext.READ_EXPIRED || next == TuiContext.EOF) {
            ctx.exportPopupVisible = false;
            return;
          }
          if (next == '[') {
            int code = backend.read(50);
            ctx.exportPathPristine = false;
            if (code == 'C') {
              ctx.exportPathState.moveCursorRight();
              return;
            }
            if (code == 'D') {
              ctx.exportPathState.moveCursorLeft();
              return;
            }
            if (code == 'H') {
              ctx.exportPathState.moveCursorToStart();
              return;
            }
            if (code == 'F') {
              ctx.exportPathState.moveCursorToEnd();
              return;
            }
            if (code == '3') {
              int tilde = backend.read(50);
              if (tilde == '~') {
                ctx.exportPathState.deleteForward();
              }
              return;
            }
          }
          ctx.exportPopupVisible = false;
          return;
        }
      case 127:
      case 8:
        ctx.exportPathPristine = false;
        ctx.exportPathState.deleteBackward();
        return;
      case 1: // Ctrl+A
        ctx.exportPathPristine = false;
        ctx.exportPathState.moveCursorToStart();
        return;
      case 5: // Ctrl+E
        ctx.exportPathPristine = false;
        ctx.exportPathState.moveCursorToEnd();
        return;
      case 21: // Ctrl+U
        ctx.exportPathPristine = false;
        ctx.exportPathState.clear();
        return;
      default:
        if (key >= 32 && key < 127) {
          if (ctx.exportPathPristine) {
            ctx.exportPathState.clear();
            ctx.exportPathPristine = false;
          }
          ctx.exportPathState.insert((char) key);
        }
        return;
    }
  }

  private static int macOptDigit(int codePoint) {
    for (int i = 0; i < MAC_OPT_DIGITS.length; i++) {
      if (codePoint == MAC_OPT_DIGITS[i]) return i;
    }
    return -1;
  }
}

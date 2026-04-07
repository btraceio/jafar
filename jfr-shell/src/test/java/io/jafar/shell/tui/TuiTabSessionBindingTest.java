package io.jafar.shell.tui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests that pinned tabs carry a session reference and that switching tabs drives session
 * switching accordingly.
 */
class TuiTabSessionBindingTest {

  private TuiContext ctx;
  private TuiCommandExecutor executor;
  private SessionManager<JFRSession> sessions;

  @BeforeEach
  void setUp() throws Exception {
    ParsingContext parsingCtx = ParsingContext.create();
    SessionManager.SessionFactory<JFRSession> factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          when(s.getRecordingPath()).thenReturn(path);
          return s;
        };
    sessions = new SessionManager<>(factory, parsingCtx);

    ctx = new TuiContext();
    ctx.tabs.add(new TuiContext.ResultTab("jfr>"));
    ctx.activeTabIndex = 0;

    executor = new TuiCommandExecutor(ctx, Executors.newSingleThreadExecutor());
    executor.setSessions(sessions);
    TuiDetailBuilder detailBuilder = new TuiDetailBuilder(ctx);
    executor.setDetailBuilder(detailBuilder);
    executor.setBrowser(new TuiBrowserController(ctx, sessions, detailBuilder));
  }

  @Test
  void pinCapturesCurrentSessionId() throws Exception {
    SessionManager.SessionRef<JFRSession> s1 = sessions.open(Path.of("/a.jfr"), null);

    ctx.inputState.setText("pin");
    executor.submitCommand();

    TuiContext.ResultTab tab = ctx.tabs.get(0);
    assertTrue(tab.pinned);
    assertEquals(s1.id, tab.pinnedSessionId);
  }

  @Test
  void switchToPinnedTabActivatesItsSession() throws Exception {
    SessionManager.SessionRef<JFRSession> s1 = sessions.open(Path.of("/a.jfr"), null);
    SessionManager.SessionRef<JFRSession> s2 = sessions.open(Path.of("/b.jfr"), null);
    // s2 is current after open

    // scratch tab = index 0; add a pinned tab bound to s1
    TuiContext.ResultTab pinned = new TuiContext.ResultTab("results");
    pinned.pinned = true;
    pinned.pinnedSessionId = s1.id;
    ctx.tabs.add(pinned);

    executor.switchTab(1);

    assertEquals(s1.id, sessions.current().map(r -> r.id).orElse(-1));
  }

  @Test
  void switchToScratchTabRestoresItsLastSession() throws Exception {
    SessionManager.SessionRef<JFRSession> s1 = sessions.open(Path.of("/a.jfr"), null);
    SessionManager.SessionRef<JFRSession> s2 = sessions.open(Path.of("/b.jfr"), null);

    // scratch tab remembers s1 as its last session
    TuiContext.ResultTab scratch = ctx.tabs.get(0);
    scratch.pinnedSessionId = s1.id;

    // add a pinned tab bound to s2 and switch to it first
    TuiContext.ResultTab pinned = new TuiContext.ResultTab("results");
    pinned.pinned = true;
    pinned.pinnedSessionId = s2.id;
    ctx.tabs.add(pinned);
    executor.switchTab(1); // s2 is now active

    // switch back to scratch — should restore s1
    executor.switchTab(0);

    assertEquals(s1.id, sessions.current().map(r -> r.id).orElse(-1));
  }

  @Test
  void togglePinCapturesCurrentSessionId() throws Exception {
    SessionManager.SessionRef<JFRSession> s1 = sessions.open(Path.of("/a.jfr"), null);

    executor.togglePin();

    TuiContext.ResultTab tab = ctx.tabs.get(0);
    assertTrue(tab.pinned);
    assertEquals(s1.id, tab.pinnedSessionId);
  }

  @Test
  void submitCommandStampsScratchTabWithCurrentSession() throws Exception {
    SessionManager.SessionRef<JFRSession> s1 = sessions.open(Path.of("/a.jfr"), null);

    ctx.inputState.setText("help");
    executor.submitCommand();

    TuiContext.ResultTab scratch = ctx.tabs.get(ctx.activeTabIndex);
    assertFalse(scratch.pinned);
    assertEquals(s1.id, scratch.pinnedSessionId);
  }

  @Test
  void commandFromPinnedTabRunsInPinnedSessionAndTargetsScratchTab() throws Exception {
    SessionManager.SessionRef<JFRSession> s1 = sessions.open(Path.of("/a.jfr"), null);
    SessionManager.SessionRef<JFRSession> s2 = sessions.open(Path.of("/b.jfr"), null);

    // Make tab 0 a pinned tab bound to s1; s2 is the session for scratch
    TuiContext.ResultTab pinned = ctx.tabs.get(0);
    pinned.pinned = true;
    pinned.pinnedSessionId = s1.id;

    // Add a scratch tab
    ctx.tabs.add(new TuiContext.ResultTab("jfr>"));
    int scratchIndex = 1;

    // Active tab is the pinned one (index 0)
    ctx.activeTabIndex = 0;

    ctx.inputState.setText("help");
    executor.submitCommand();

    // Active tab should have switched to scratch
    assertEquals(scratchIndex, ctx.activeTabIndex);
    // Session used should be s1 (from the pinned tab)
    assertEquals(s1.id, sessions.current().map(r -> r.id).orElse(-1));
    // Scratch tab should now be associated with s1
    assertEquals(s1.id, ctx.tabs.get(scratchIndex).pinnedSessionId);
  }
}

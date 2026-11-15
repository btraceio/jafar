package io.jafar.shell.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.shell.core.scripting.ConditionalState;
import org.junit.jupiter.api.Test;

/**
 * The TUI has no prompt string, so the command input's title is the only place an open if-block can
 * show; without it, commands typed in a skipped branch just produce no output.
 */
class TuiRendererInputTitleTest {

  @Test
  void outsideAnyBlock() {
    assertEquals("Command >", TuiRenderer.inputTitle(new ConditionalState()));
    assertEquals("Command >", TuiRenderer.inputTitle(null));
  }

  @Test
  void insideActiveBranch() {
    ConditionalState state = new ConditionalState();
    state.enterIf(true);
    assertEquals("Command ...(1) >", TuiRenderer.inputTitle(state));
  }

  @Test
  void afterFailedConditionShowsSkipping() {
    ConditionalState state = new ConditionalState();
    state.enterIf(true);
    state.enterIfWithError();
    assertEquals("Command ...(2, skipping until endif) >", TuiRenderer.inputTitle(state));
  }
}

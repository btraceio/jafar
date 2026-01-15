package io.jafar.shell.cli;

/**
 * Tracks whether shell is in conversational chat mode. Similar to ConditionalState but simpler (no
 * nesting).
 */
public class ChatState {
  private boolean inChatMode = false;

  public boolean isChatMode() {
    return inChatMode;
  }

  public void enterChat() {
    inChatMode = true;
  }

  public void exitChat() {
    inChatMode = false;
  }
}

package io.jafar.shell.core.llm;

import java.util.List;
import java.util.Objects;

/**
 * One completion request: a cacheable system prefix, then the turns.
 *
 * <p>The split matters for cost. {@code systemPrefix} carries the query-language reference, which
 * is large (the JfrPath reference alone is over a thousand lines) and byte-identical across calls,
 * so it is marked for prompt caching and is nearly free after the first request of a session.
 * Anything that varies per question — the session inventory, the question itself — belongs in
 * {@code messages}, after the cache breakpoint.
 *
 * @param systemPrefix stable system content; must not vary between requests of the same kind
 * @param messages the conversation turns, oldest first
 * @param maxTokens output ceiling
 * @param purpose what this request is for, used in diagnostics and dry-run output
 */
public record LlmRequest(String systemPrefix, List<Turn> messages, int maxTokens, String purpose) {

  public LlmRequest {
    Objects.requireNonNull(systemPrefix, "systemPrefix");
    messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    if (messages.isEmpty()) {
      throw new IllegalArgumentException("at least one message is required");
    }
    if (maxTokens <= 0) {
      throw new IllegalArgumentException("maxTokens must be positive");
    }
  }

  /** A single conversation turn. */
  public record Turn(Role role, String text) {
    public Turn {
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(text, "text");
    }

    public static Turn user(String text) {
      return new Turn(Role.USER, text);
    }

    public static Turn assistant(String text) {
      return new Turn(Role.ASSISTANT, text);
    }
  }

  public enum Role {
    USER,
    ASSISTANT
  }

  /** Total characters that would be sent. Used by {@code llm dry-run} and for rough sizing. */
  public int characterCount() {
    int total = systemPrefix.length();
    for (Turn turn : messages) {
      total += turn.text().length();
    }
    return total;
  }
}

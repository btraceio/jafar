package io.jafar.shell.core.llm;

import java.util.Objects;
import java.util.Optional;

/**
 * A completion result plus what it cost.
 *
 * <p>Usage is carried on every response so the shell can print it after each command and keep a
 * session running total. An LLM feature that hides its cost is one users stop trusting.
 *
 * @param text the model's reply
 * @param usage token accounting, absent when a backend cannot report it
 * @param model the model that actually served the request
 * @param stopReason why generation ended, when the backend reports it
 */
public record LlmResponse(String text, Optional<Usage> usage, String model, String stopReason) {

  public LlmResponse {
    Objects.requireNonNull(text, "text");
    usage = usage == null ? Optional.empty() : usage;
  }

  /**
   * Token accounting for one request.
   *
   * @param inputTokens tokens sent, excluding cache reads
   * @param outputTokens tokens generated
   * @param cacheReadTokens tokens served from the prompt cache; a zero here across repeated calls
   *     means the cacheable prefix is being invalidated
   * @param cacheWriteTokens tokens written to the prompt cache
   */
  public record Usage(
      long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

    public long totalTokens() {
      return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
    }

    public Usage plus(Usage other) {
      return new Usage(
          inputTokens + other.inputTokens,
          outputTokens + other.outputTokens,
          cacheReadTokens + other.cacheReadTokens,
          cacheWriteTokens + other.cacheWriteTokens);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(inputTokens).append(" in, ").append(outputTokens).append(" out");
      if (cacheReadTokens > 0) {
        sb.append(", ").append(cacheReadTokens).append(" cached");
      }
      if (cacheWriteTokens > 0) {
        sb.append(", ").append(cacheWriteTokens).append(" cache-write");
      }
      return sb.toString();
    }
  }
}

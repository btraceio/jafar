package io.jafar.shell.core.llm;

/**
 * A failure from an LLM backend, carrying a remedy where one is known.
 *
 * <p>The remedy exists because the most common failures here are configuration rather than code —
 * an expired OAuth profile, a shadowing API key, no credentials at all — and each has a specific
 * fix the shell can state instead of printing a stack trace.
 */
public class LlmException extends Exception {

  private static final long serialVersionUID = 1L;

  private final String remedy;

  public LlmException(String message) {
    this(message, null, null);
  }

  public LlmException(String message, String remedy) {
    this(message, remedy, null);
  }

  public LlmException(String message, String remedy, Throwable cause) {
    super(message, cause);
    this.remedy = remedy;
  }

  /** A suggested fix, or {@code null} when none is known. */
  public String remedy() {
    return remedy;
  }
}

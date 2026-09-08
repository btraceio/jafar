package io.jafar.shell.llm.openai;

import java.util.List;

/**
 * The OpenAI API, and any hosted gateway that reimplements it.
 *
 * <p>Point {@code llm.base-url} elsewhere to use Groq, Together, OpenRouter, Azure OpenAI or a
 * self-hosted vLLM behind the same protocol; only the URL and the key change.
 */
public final class OpenAiBackend extends OpenAiCompatibleBackend {

  public OpenAiBackend() {
    super(
        new Profile(
            "openai",
            "OpenAI-compatible API",
            "https://api.openai.com/v1",
            "gpt-4o-mini",
            List.of("OPENAI_API_KEY"),
            true,
            "Set OPENAI_API_KEY, or `set llm.api-key = ...`. For a different provider that speaks "
                + "the same protocol, also set llm.base-url."));
  }
}

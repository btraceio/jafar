package io.jafar.shell.llm.openai;

import java.util.List;

/**
 * Ollama, local by default and cloud by changing one setting.
 *
 * <p>Ollama serves an OpenAI-compatible API alongside its native one, so it needs no separate
 * protocol implementation — only different defaults: a loopback endpoint and no key.
 *
 * <p>This is the backend that changes the privacy story rather than the cost one. With a local
 * model nothing leaves the machine at all, which makes {@code ask} usable in the air-gapped and
 * regulated environments that otherwise have to turn the feature off. The trade is quality: a small
 * local model writes invalid queries far more often, which is why the shell validates every
 * generated query against its own parser and asks for a correction before running anything.
 *
 * <p>For Ollama Cloud, set {@code llm.base-url} to the cloud endpoint and provide {@code
 * OLLAMA_API_KEY}. Check the current cloud endpoint in Ollama's documentation — it is not hardcoded
 * here precisely because it is the part most likely to change.
 */
public final class OllamaBackend extends OpenAiCompatibleBackend {

  public OllamaBackend() {
    super(
        new Profile(
            "ollama",
            "Ollama (local or cloud)",
            "http://localhost:11434/v1",
            "qwen2.5-coder:7b",
            List.of("OLLAMA_API_KEY"),
            false,
            "Local Ollama needs no key: run `ollama serve` and `ollama pull <model>`. For Ollama "
                + "Cloud set OLLAMA_API_KEY and point llm.base-url at the cloud endpoint."));
  }
}

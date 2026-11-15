package io.jafar.shell.core.llm;

/**
 * Provider that loads fine but throws from {@link #readiness(LlmConfig)} — e.g. an SDK whose
 * credential probe blows up. Used to verify discovery and selection survive a broken health check.
 *
 * <p>The id deliberately sorts before {@link ReadyPluginLlmBackend}'s, so auto-selection probes
 * this one first and the probe path is genuinely exercised.
 */
public final class ThrowingReadinessPluginLlmBackend implements LlmBackend {

  @Override
  public String id() {
    return "aaa-throwing-readiness";
  }

  @Override
  public String displayName() {
    return "Throwing readiness (fixture)";
  }

  @Override
  public Readiness readiness(LlmConfig config) {
    throw new IllegalStateException("readiness boom");
  }

  @Override
  public String defaultModel() {
    return "none";
  }

  @Override
  public LlmResponse complete(LlmRequest request, LlmConfig config) {
    throw new IllegalStateException("never callable");
  }
}

package io.jafar.shell.core.llm;

/** Healthy fixture backend that reports ready, for auto-selection tests. */
public final class ReadyPluginLlmBackend implements LlmBackend {

  @Override
  public String id() {
    return "ready-plugin-backend";
  }

  @Override
  public String displayName() {
    return "Ready (fixture)";
  }

  @Override
  public Readiness readiness(LlmConfig config) {
    return Readiness.ready("ready");
  }

  @Override
  public String defaultModel() {
    return "fixture-model";
  }

  @Override
  public LlmResponse complete(LlmRequest request, LlmConfig config) {
    return new LlmResponse("ok", null, "fixture-model", "stop");
  }
}

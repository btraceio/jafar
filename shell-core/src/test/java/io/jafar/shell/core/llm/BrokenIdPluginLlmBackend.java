package io.jafar.shell.core.llm;

/** Provider whose {@code id()} throws — discovery must skip it and record the failure. */
public final class BrokenIdPluginLlmBackend implements LlmBackend {

  @Override
  public String id() {
    throw new IllegalStateException("id boom");
  }

  @Override
  public String displayName() {
    return "Broken id (fixture)";
  }

  @Override
  public Readiness readiness(LlmConfig config) {
    return Readiness.ready("ready");
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

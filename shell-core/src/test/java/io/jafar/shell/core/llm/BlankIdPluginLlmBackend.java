package io.jafar.shell.core.llm;

/** Provider whose {@code id()} is blank — discovery must skip it and record the failure. */
public final class BlankIdPluginLlmBackend implements LlmBackend {

  @Override
  public String id() {
    return "   ";
  }

  @Override
  public String displayName() {
    return "Blank id (fixture)";
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

package io.jafar.shell.core.llm;

/**
 * Provider whose static initializer throws — the shape of a provider jar whose dependencies are
 * missing or whose class initialization fails. Discovered only through a fixture jar whose service
 * file lists this class; never registered on the normal test classpath.
 */
public final class BrokenPluginLlmBackend implements LlmBackend {

  static {
    if (Boolean.parseBoolean("true")) {
      throw new IllegalStateException("broken fixture: static initializer failed");
    }
  }

  public BrokenPluginLlmBackend() {}

  @Override
  public String id() {
    return "broken-plugin-backend";
  }

  @Override
  public String displayName() {
    return "Broken (fixture)";
  }

  @Override
  public Readiness readiness(LlmConfig config) {
    return Readiness.ready("never reached");
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

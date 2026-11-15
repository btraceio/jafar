package io.jafar.shell.cli;

import io.jafar.shell.core.llm.LlmBackend;
import io.jafar.shell.core.llm.LlmConfig;
import io.jafar.shell.core.llm.LlmRequest;
import io.jafar.shell.core.llm.LlmResponse;

/**
 * Provider whose static initializer throws — the shape of an installed provider jar whose
 * dependencies are missing or whose class initialization fails. Discovered only through a fixture
 * jar whose service file lists this class; never registered on the normal test classpath.
 */
final class BrokenPluginLlmBackend implements LlmBackend {

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

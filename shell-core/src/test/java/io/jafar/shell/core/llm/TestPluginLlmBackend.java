package io.jafar.shell.core.llm;

/**
 * A package-visible provider for {@link LlmBackendPluginDiscoveryTest}: the provider class stays on
 * the test classpath while the discovery test loads it through a plugin classloader; ServiceLoader
 * requires a public no-arg constructor, the class stays package-private otherwise.
 */
public final class TestPluginLlmBackend implements LlmBackend {

  public static final String ID = "test-plugin-backend";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String displayName() {
    return "Test plugin backend";
  }

  @Override
  public Readiness readiness(LlmConfig config) {
    return Readiness.notReady("test backend, never usable", "no remedy");
  }

  @Override
  public String defaultModel() {
    return "test-model";
  }

  @Override
  public LlmResponse complete(LlmRequest request, LlmConfig config) throws LlmException {
    throw new LlmException("not a real backend");
  }
}

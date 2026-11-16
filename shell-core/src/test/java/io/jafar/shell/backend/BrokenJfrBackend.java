package io.jafar.shell.backend;

import java.util.Set;

/** Fixture whose static initializer throws — the "broken installed provider jar" shape. */
public final class BrokenJfrBackend implements JfrBackend {

  static {
    if (Boolean.parseBoolean("true")) {
      throw new IllegalStateException("broken fixture: static initializer failed");
    }
  }

  public BrokenJfrBackend() {}

  @Override
  public String getId() {
    return "broken-jfr-backend";
  }

  @Override
  public String getName() {
    return "Broken (fixture)";
  }

  @Override
  public String getVersion() {
    return "0.0.0-test";
  }

  @Override
  public int getPriority() {
    return 10;
  }

  @Override
  public Set<BackendCapability> getCapabilities() {
    return Set.of();
  }

  @Override
  public BackendContext createContext() {
    return null;
  }

  @Override
  public EventSource createEventSource(BackendContext context) {
    return null;
  }

  @Override
  public MetadataSource createMetadataSource() throws UnsupportedCapabilityException {
    return null;
  }

  @Override
  public ChunkSource createChunkSource() throws UnsupportedCapabilityException {
    return null;
  }

  @Override
  public ConstantPoolSource createConstantPoolSource() throws UnsupportedCapabilityException {
    return null;
  }
}

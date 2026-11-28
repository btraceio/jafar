package io.jafar.shell.backend;

import java.util.Set;

/** Healthy fixture backend for discovery tests. */
public final class ReadyJfrBackend implements JfrBackend {

  @Override
  public String getId() {
    return "ready-jfr-backend";
  }

  @Override
  public String getName() {
    return "Ready (fixture)";
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

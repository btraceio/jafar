package io.jafar.shell.backend;

import java.util.Set;

/** Fixture whose {@code getId()} is blank — discovery must skip it and record the failure. */
public final class BlankIdJfrBackend implements JfrBackend {

  @Override
  public String getId() {
    return "";
  }

  @Override
  public String getName() {
    return "Blank id (fixture)";
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

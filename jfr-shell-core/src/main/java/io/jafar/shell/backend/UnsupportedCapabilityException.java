package io.jafar.shell.backend;

/** Thrown when a command requires a capability the current backend does not support. */
public final class UnsupportedCapabilityException extends Exception {
  private final BackendCapability capability;
  private final String backendId;

  public UnsupportedCapabilityException(BackendCapability capability, String backendId) {
    super(
        String.format(
            "Backend '%s' does not support capability: %s", backendId, capability.name()));
    this.capability = capability;
    this.backendId = backendId;
  }

  public BackendCapability getCapability() {
    return capability;
  }

  public String getBackendId() {
    return backendId;
  }
}

package io.jafar.shell.backend;

import java.util.Set;

/**
 * Service Provider Interface for JFR parsing backends. Implementations are discovered via
 * ServiceLoader.
 *
 * <p>To implement a custom backend:
 *
 * <ol>
 *   <li>Implement this interface
 *   <li>Register it in META-INF/services/io.jafar.shell.backend.JfrBackend
 * </ol>
 */
public interface JfrBackend {

  /**
   * Returns the unique identifier for this backend.
   *
   * @return backend ID (e.g., "jafar", "jdk", "jmc")
   */
  String getId();

  /**
   * Returns a human-readable name for display.
   *
   * @return display name (e.g., "Jafar Parser", "JDK JFR API")
   */
  String getName();

  /**
   * Returns the backend version for diagnostics.
   *
   * @return version string
   */
  String getVersion();

  /**
   * Returns the priority for auto-selection. Higher values are preferred when multiple backends are
   * available.
   *
   * @return priority value (e.g., 100 for default backend, 50 for alternatives)
   */
  int getPriority();

  /**
   * Returns the set of capabilities this backend supports.
   *
   * @return immutable set of supported capabilities
   */
  Set<BackendCapability> getCapabilities();

  /**
   * Check if a specific capability is supported.
   *
   * @param capability the capability to check
   * @return true if supported
   */
  default boolean supports(BackendCapability capability) {
    return getCapabilities().contains(capability);
  }

  /**
   * Create a new backend context for resource sharing across sessions.
   *
   * @return new context instance
   */
  BackendContext createContext();

  /**
   * Create an EventSource for streaming events from recordings.
   *
   * @param context the backend context
   * @return event source instance
   */
  EventSource createEventSource(BackendContext context);

  /**
   * Create a MetadataSource for metadata queries.
   *
   * @return metadata source instance
   * @throws UnsupportedCapabilityException if METADATA_CLASSES not supported
   */
  MetadataSource createMetadataSource() throws UnsupportedCapabilityException;

  /**
   * Create a ChunkSource for chunk information queries.
   *
   * @return chunk source instance
   * @throws UnsupportedCapabilityException if CHUNK_INFO not supported
   */
  ChunkSource createChunkSource() throws UnsupportedCapabilityException;

  /**
   * Create a ConstantPoolSource for constant pool queries.
   *
   * @return constant pool source instance
   * @throws UnsupportedCapabilityException if CONSTANT_POOLS not supported
   */
  ConstantPoolSource createConstantPoolSource() throws UnsupportedCapabilityException;
}

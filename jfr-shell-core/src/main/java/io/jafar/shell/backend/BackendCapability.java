package io.jafar.shell.backend;

/**
 * Capabilities that a JFR backend may or may not support. Used for graceful degradation when
 * features are unavailable.
 */
public enum BackendCapability {
  /** Stream events from recordings. */
  EVENT_STREAMING,

  /** Access metadata class information (event types, fields, annotations). */
  METADATA_CLASSES,

  /** Access chunk-level information (headers, offsets, sizes). */
  CHUNK_INFO,

  /** Access constant pool contents. */
  CONSTANT_POOLS,

  /** Support for streaming large files without full memory load. */
  STREAMING_PARSE,

  /** Support for typed event handlers with compile-time interfaces. */
  TYPED_HANDLERS,

  /** Support for untyped Map-based event access. */
  UNTYPED_HANDLERS,

  /** Support for reusable parsing context across sessions. */
  CONTEXT_REUSE
}

package io.jafar.parser.api;

import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * Factory interface for build-time generated handler implementations.
 *
 * <p>Build-time generated handlers use factories to provide thread-local cached instances, reducing
 * allocations during event parsing. Each factory is associated with a specific {@link JfrType}
 * annotated interface.
 *
 * <p>The annotation processor generates implementations of this interface for each {@code @JfrType}
 * annotated interface. The generated factory:
 *
 * <ul>
 *   <li>Maintains a thread-local cache of handler instances
 *   <li>Binds type IDs when recording metadata becomes available
 *   <li>Returns cached handler instances populated with event data
 * </ul>
 *
 * @param <T> the handler interface type
 */
public interface HandlerFactory<T> {

  /**
   * Binds type IDs from the recording metadata.
   *
   * <p>Must be called before using {@link #get} with a new recording. This method injects
   * recording-specific type IDs into the handler's static fields for constant pool resolution.
   *
   * @param metadata the metadata lookup for the recording
   */
  void bind(MetadataLookup metadata);

  /**
   * Gets a handler instance populated with data from the stream.
   *
   * <p>Returns a thread-local cached instance to reduce allocations. The returned handler is valid
   * until the next call to {@code get()} on the same thread.
   *
   * @param stream the recording stream positioned at event data
   * @param metadata the metadata class for this event type
   * @param constantPools the constant pools for resolving references
   * @return the populated handler instance
   */
  T get(RecordingStream stream, MetadataClass metadata, ConstantPools constantPools);

  /**
   * Gets the JFR type name this factory handles.
   *
   * @return the JFR type name (e.g., "jdk.ExecutionSample")
   */
  String getJfrTypeName();

  /**
   * Gets the interface class this factory implements.
   *
   * @return the interface class
   */
  Class<T> getInterfaceClass();
}

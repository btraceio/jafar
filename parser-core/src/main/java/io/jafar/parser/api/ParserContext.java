package io.jafar.parser.api;

import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.utils.CachedStringParser;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-session parsing context used internally and exposed via events.
 *
 * <p>Maintains per-parser reusable buffers and a key-value bag for extensions.
 *
 * <p>Not intended for direct instantiation by users.
 */
public abstract class ParserContext {
  /** Concurrent map for storing values by class name or custom string keys. */
  private final ConcurrentMap<String, Object> storage = new ConcurrentHashMap<>();

  /** Metadata lookup instance for resolving metadata during parsing. */
  protected final MutableMetadataLookup metadataLookup;

  /** Constant pools instance for managing constant pool data during parsing. */
  protected final MutableConstantPools constantPools;

  private long stringTypeId = -1;

  /** Index of the current chunk being parsed. */
  private final int chunkIndex;

  /** Reusable byte buffer for temporary data storage during parsing. */
  public final byte[] byteBuffer = new byte[4096];

  /** Reusable character buffer for temporary data storage during parsing. */
  public final char[] charBuffer = new char[4096];

  /** UTF-8 parser instance for parsing byte arrays. */
  public final CachedStringParser.ByteArrayParser utf8Parser = CachedStringParser.byteParser();

  /** Character parser instance for parsing character arrays. */
  public final CachedStringParser.CharArrayParser charParser = CachedStringParser.charParser();

  /**
   * Constructs a new ParserContext with the specified chunk index.
   *
   * @param chunkIndex the index of the chunk being parsed
   */
  public ParserContext(int chunkIndex) {
    this.chunkIndex = chunkIndex;
    this.metadataLookup = new MutableMetadataLookup();
    this.constantPools = new MutableConstantPools();
  }

  /**
   * Constructs a new ParserContext with the specified chunk index and existing instances.
   *
   * @param chunkIndex the index of the chunk being parsed
   * @param metadataLookup the metadata lookup instance to use
   * @param constantPools the constant pools instance to use
   */
  protected ParserContext(
      int chunkIndex, MutableMetadataLookup metadataLookup, MutableConstantPools constantPools) {
    this.chunkIndex = chunkIndex;
    this.metadataLookup = metadataLookup;
    this.constantPools = constantPools;
  }

  /**
   * Removes a value stored under the class name key.
   *
   * @param <T> the type of the value to remove
   * @param clz the class whose name is used as the key
   * @return the removed value, or {@code null} if it was reclaimed or not found
   */
  public final <T> T remove(Class<T> clz) {
    return clz.cast(storage.remove(clz.getName()));
  }

  /**
   * Removes a value stored under a custom key. Note: if there is no mapping for the key, this may
   * throw {@link NullPointerException}.
   *
   * @param <T> the type of the value to remove
   * @param key custom key
   * @param clz expected type
   * @return the removed value, or {@code null} if it was reclaimed
   */
  public final <T> T remove(String key, Class<T> clz) {
    return clz.cast(storage.remove(key));
  }

  /**
   * Stores a value under the class name key.
   *
   * @param <T> the type of the value to store
   * @param clz the class whose name is used as the key
   * @param value the value to store
   */
  public final <T> void put(Class<T> clz, T value) {
    storage.put(clz.getName(), value);
  }

  /**
   * Retrieves a value stored under the class name key.
   *
   * @param <T> the type of the value to retrieve
   * @param clz the class whose name is used as the key
   * @return the value, or {@code null} if it was reclaimed or not found
   */
  public final <T> T get(Class<T> clz) {
    return clz.cast(storage.get(clz.getName()));
  }

  /**
   * Stores a value under a custom key
   *
   * @param <T> the type of the value to store
   * @param key the custom key to use
   * @param clz the class type for type safety
   * @param value the value to store
   */
  public final <T> void put(String key, Class<T> clz, T value) {
    storage.put(key, value);
  }

  /**
   * Retrieves a value stored under a custom key. Note: if there is no mapping for the key, this may
   * throw {@link NullPointerException}. If the mapping exists but the value was reclaimed, returns
   * {@code null}.
   *
   * @param <T> the type of the value to retrieve
   * @param key custom key
   * @param clz expected type
   * @return the value or {@code null}
   */
  public final <T> T get(String key, Class<T> clz) {
    return clz.cast(storage.get(key));
  }

  /** Clears all stored values from the context. */
  public void clear() {
    storage.clear();
  }

  /**
   * Gets the index of the current chunk being parsed.
   *
   * @return the chunk index
   */
  public int getChunkIndex() {
    return chunkIndex;
  }

  public long getStringTypeId() {
    return stringTypeId;
  }

  /** Called when metadata is fully available for the current chunk. */
  public void onMetadataReady() {
    stringTypeId = metadataLookup.getClass("java.lang.String").getId();
  }
  ;

  /** Called when constant pools are fully available for the current chunk. */
  public abstract void onConstantPoolsReady();

  /**
   * Access to metadata lookup for advanced use.
   *
   * @return the metadata lookup instance
   */
  public MetadataLookup getMetadataLookup() {
    return metadataLookup;
  }

  /**
   * Access to constant pools for advanced use.
   *
   * @return the constant pools instance
   */
  public ConstantPools getConstantPools() {
    return constantPools;
  }
}

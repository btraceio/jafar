package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.DeserializerCache;
import io.jafar.parser.internal_api.GlobalHandlerCache;
import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.internal_api.ParserContextFactory;
import io.jafar.parser.internal_api.collections.IntObjectArrayMap;
import io.jafar.parser.internal_api.metadata.MetadataFingerprint;
import java.util.Set;

/**
 * Factory for creating typed parser contexts.
 *
 * <p>This class implements ParserContextFactory to create TypedParserContext instances with proper
 * metadata lookup and constant pool management for typed JFR parsing.
 *
 * <p>Uses metadata fingerprinting to enable handler class reuse across parsing sessions when
 * metadata is compatible.
 */
public final class TypedParserContextFactory implements ParserContextFactory {
  /**
   * Public constructor for TypedParserContextFactory.
   *
   * <p>This factory creates typed parser contexts for JFR parsing.
   */
  public TypedParserContextFactory() {}

  /** Map of chunk index to metadata lookup instances. */
  private final IntObjectArrayMap<MutableMetadataLookup> chunkMetadataLookup =
      new IntObjectArrayMap<>();

  /** Map of chunk index to constant pools instances. */
  private final IntObjectArrayMap<MutableConstantPools> chunkConstantPools =
      new IntObjectArrayMap<>();

  /** Map of chunk index to metadata fingerprints. */
  private final IntObjectArrayMap<MetadataFingerprint> chunkFingerprints =
      new IntObjectArrayMap<>();

  /**
   * Creates a new parser context for the specified chunk.
   *
   * <p>The deserializer cache will be resolved after metadata is loaded via {@link
   * #resolveDeserializerCache}.
   *
   * @param parent the parent parser context, or null if this is a root context
   * @param chunkIndex the index of the chunk this context is for
   * @return a new TypedParserContext instance
   */
  @Override
  public ParserContext newContext(ParserContext parent, int chunkIndex) {
    if (parent == null) {
      // Root context - cache will be resolved after metadata load
      return new TypedParserContext(null);
    }
    MutableMetadataLookup metadataLookup =
        chunkMetadataLookup.computeIfAbsent(chunkIndex, k -> new MutableMetadataLookup());
    MutableConstantPools constantPools =
        chunkConstantPools.computeIfAbsent(chunkIndex, k -> new MutableConstantPools());

    assert parent instanceof TypedParserContext;
    TypedParserContext lazyParent = (TypedParserContext) parent;

    // Cache will be resolved after metadata load
    return new TypedParserContext(
        lazyParent.getTypeFilter(), chunkIndex, metadataLookup, constantPools, null, this);
  }

  @Override
  public void onChunkMetadata(ParserContext context, ChunkHeader header) {
    if (context instanceof TypedParserContext) {
      TypedParserContext typedCtx = (TypedParserContext) context;
      if (typedCtx.getDeserializerCache() == null) {
        resolveDeserializerCache(header.order, typedCtx.getMetadataLookup(), typedCtx);
      }
    }
  }

  /**
   * Resolves and sets the deserializer cache for a context based on metadata fingerprint.
   *
   * <p>Computes a fingerprint for the reachable types from the context's target event types, then
   * retrieves or creates a deserializer cache from the global cache.
   *
   * @param chunkIndex the chunk index
   * @param metadata the metadata lookup for this chunk
   * @param context the context to set the cache on
   */
  public void resolveDeserializerCache(
      int chunkIndex, MutableMetadataLookup metadata, TypedParserContext context) {
    // Compute fingerprint for reachable types
    Set<String> eventTypes = context.getTargetEventTypes();
    Set<Long> reachableTypes = MetadataFingerprint.computeReachableTypes(metadata, eventTypes);
    MetadataFingerprint fingerprint = MetadataFingerprint.compute(metadata, reachableTypes);

    // Store fingerprint
    chunkFingerprints.put(chunkIndex, fingerprint);

    // Get or create cache from global registry
    DeserializerCache cache = GlobalHandlerCache.getInstance().getOrCreateCache(fingerprint);
    context.setDeserializerCache(cache);
  }

  /**
   * Gets the metadata fingerprint for the specified chunk.
   *
   * @param chunkIndex the chunk index
   * @return the fingerprint, or null if not yet computed
   */
  public MetadataFingerprint getFingerprint(int chunkIndex) {
    return chunkFingerprints.get(chunkIndex);
  }
}

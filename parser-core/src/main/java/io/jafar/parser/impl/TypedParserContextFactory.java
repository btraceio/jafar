package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.DeserializerCache;
import io.jafar.parser.internal_api.DeserializerFactory;
import io.jafar.parser.internal_api.GlobalHandlerCache;
import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.internal_api.ParserContextFactory;
import io.jafar.parser.internal_api.collections.IntObjectArrayMap;
import io.jafar.parser.internal_api.metadata.MetadataFingerprint;
import java.util.Set;

// NOTE: this factory is shared across concurrent recordings; all per-recording state
// must live in RecordingState (stored on the root context) — NOT in instance fields.

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
  private final DeserializerFactory deserializerFactory;

  /**
   * Constructs a TypedParserContextFactory with the given DeserializerFactory.
   *
   * @param deserializerFactory the factory for creating deserializers, or null if unavailable
   */
  public TypedParserContextFactory(DeserializerFactory deserializerFactory) {
    this.deserializerFactory = deserializerFactory;
  }

  /**
   * Per-recording state: chunk-indexed maps for metadata and constant pools. Stored on the root
   * context so each concurrent recording has its own isolated copy.
   */
  private static final class RecordingState {
    final IntObjectArrayMap<MutableMetadataLookup> chunkMetadataLookup = new IntObjectArrayMap<>();
    final IntObjectArrayMap<MutableConstantPools> chunkConstantPools = new IntObjectArrayMap<>();
  }

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
      // New recording — create a fresh per-recording state and attach it to the root context.
      // Each concurrent recording gets its own RecordingState so chunk-indexed maps never collide.
      TypedParserContext root = new TypedParserContext(null);
      root.put(RecordingState.class, new RecordingState());
      if (deserializerFactory != null) {
        root.put(DeserializerFactory.class, deserializerFactory);
      }
      return root;
    }

    assert parent instanceof TypedParserContext;
    TypedParserContext lazyParent = (TypedParserContext) parent;

    RecordingState state = lazyParent.get(RecordingState.class);
    if (state == null) {
      throw new IllegalStateException(
          "RecordingState missing on parent context — context was not created via this factory");
    }
    MutableMetadataLookup metadataLookup =
        state.chunkMetadataLookup.computeIfAbsent(chunkIndex, k -> new MutableMetadataLookup());
    MutableConstantPools constantPools =
        state.chunkConstantPools.computeIfAbsent(chunkIndex, k -> new MutableConstantPools());

    // Cache will be resolved after metadata load
    TypedParserContext ctx =
        new TypedParserContext(
            lazyParent.getTypeFilter(), chunkIndex, metadataLookup, constantPools, null, this);
    if (deserializerFactory != null) {
      ctx.put(DeserializerFactory.class, deserializerFactory);
    }
    // Propagate the per-recording state so resolveDeserializerCache can reach it
    ctx.put(RecordingState.class, state);
    return ctx;
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
  private void resolveDeserializerCache(
      int chunkIndex, MutableMetadataLookup metadata, TypedParserContext context) {
    // Compute fingerprint for reachable types
    Set<String> eventTypes = context.getTargetEventTypes();
    Set<Long> reachableTypes = MetadataFingerprint.computeReachableTypes(metadata, eventTypes);
    MetadataFingerprint fingerprint = MetadataFingerprint.compute(metadata, reachableTypes);

    // Get or create cache from global registry
    DeserializerCache cache = GlobalHandlerCache.getInstance().getOrCreateCache(fingerprint);
    context.setDeserializerCache(cache);
  }
}

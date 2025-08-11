package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.DeserializerCache;
import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.internal_api.ParserContextFactory;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Factory for creating typed parser contexts.
 * <p>
 * This class implements ParserContextFactory to create TypedParserContext instances
 * with proper metadata lookup and constant pool management for typed JFR parsing.
 * </p>
 */
public final class TypedParserContextFactory implements ParserContextFactory {
    /**
     * Public constructor for TypedParserContextFactory.
     * <p>
     * This factory creates typed parser contexts for JFR parsing.
     * </p>
     */
    public TypedParserContextFactory() {}
    
    /** The deserializer cache shared across all contexts. */
    private final DeserializerCache deserializerCache = new DeserializerCache.Impl();

    /** Map of chunk index to metadata lookup instances. */
    private final Int2ObjectMap<MutableMetadataLookup> chunkMetadataLookup = new Int2ObjectOpenHashMap<>();
    
    /** Map of chunk index to constant pools instances. */
    private final Int2ObjectMap<MutableConstantPools> chunkConstantPools = new Int2ObjectOpenHashMap<>();

    /**
     * Creates a new parser context for the specified chunk.
     * 
     * @param parent the parent parser context, or null if this is a root context
     * @param chunkIndex the index of the chunk this context is for
     * @return a new TypedParserContext instance
     */
    @Override
    public ParserContext newContext(ParserContext parent, int chunkIndex) {
        if (parent == null) {
            return new TypedParserContext(deserializerCache);
        }
        MutableMetadataLookup metadataLookup = chunkMetadataLookup.computeIfAbsent(chunkIndex, k -> new MutableMetadataLookup());
        MutableConstantPools constantPools = chunkConstantPools.computeIfAbsent(chunkIndex, k -> new MutableConstantPools());

        assert parent instanceof TypedParserContext;
        TypedParserContext lazyParent = (TypedParserContext) parent;

        return new TypedParserContext(lazyParent.getTypeFilter(), chunkIndex, metadataLookup, constantPools, ((TypedParserContext) parent).getDeserializerCache());
    }
}

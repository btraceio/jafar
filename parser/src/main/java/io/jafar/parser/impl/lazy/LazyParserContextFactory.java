package io.jafar.parser.impl.lazy;

import io.jafar.parser.internal_api.MutableConstantPools;
import io.jafar.parser.internal_api.MutableMetadataLookup;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.DeserializerCache;
import io.jafar.parser.internal_api.ParserContextFactory;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

public final class LazyParserContextFactory implements ParserContextFactory {
    private final DeserializerCache deserializerCache = new DeserializerCache.Impl();

    private final Int2ObjectMap<MutableMetadataLookup> chunkMetadataLookup = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectMap<MutableConstantPools> chunkConstantPools = new Int2ObjectOpenHashMap<>();

    @Override
    public ParserContext newContext(ParserContext parent, int chunkIndex) {
        if (parent == null) {
            return new LazyParserContext(deserializerCache);
        }
        MutableMetadataLookup metadataLookup = chunkMetadataLookup.computeIfAbsent(chunkIndex, k -> new MutableMetadataLookup());
        MutableConstantPools constantPools = chunkConstantPools.computeIfAbsent(chunkIndex, k -> new MutableConstantPools());

        assert parent instanceof LazyParserContext;
        LazyParserContext lazyParent = (LazyParserContext) parent;

        return new LazyParserContext(lazyParent.getTypeFilter(), chunkIndex, metadataLookup, constantPools, ((LazyParserContext) parent).getDeserializerCache());
    }
}

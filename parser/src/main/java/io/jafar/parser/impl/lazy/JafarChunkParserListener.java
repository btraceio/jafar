package io.jafar.parser.impl.lazy;

import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

interface JafarChunkParserListener extends ChunkParserListener<LazyParserContext> {
    @Override
    default void onRecordingStart(LazyParserContext context) {
        ChunkParserListener.super.onRecordingStart(context);
    }

    @Override
    default boolean onMetadata(LazyParserContext context, MetadataEvent metadata) {
        return ChunkParserListener.super.onMetadata(context, metadata);
    }

    @Override
    default boolean onCheckpoint(LazyParserContext context, CheckpointEvent checkpoint) {
        return ChunkParserListener.super.onCheckpoint(context, checkpoint);
    }

    @Override
    default boolean onChunkEnd(LazyParserContext context, int chunkIndex, boolean skipped) {
        return ChunkParserListener.super.onChunkEnd(context, chunkIndex, skipped);
    }

    @Override
    default void onRecordingEnd(LazyParserContext context) {
        ChunkParserListener.super.onRecordingEnd(context);
    }
}

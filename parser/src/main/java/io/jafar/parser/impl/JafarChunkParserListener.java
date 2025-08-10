package io.jafar.parser.impl;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

interface JafarChunkParserListener extends ChunkParserListener {
    @Override
    default void onRecordingStart(ParserContext context) {
        ChunkParserListener.super.onRecordingStart(context);
    }

    @Override
    default boolean onMetadata(ParserContext context, MetadataEvent metadata) {
        return ChunkParserListener.super.onMetadata(context, metadata);
    }

    @Override
    default boolean onCheckpoint(ParserContext context, CheckpointEvent checkpoint) {
        return ChunkParserListener.super.onCheckpoint(context, checkpoint);
    }

    @Override
    default boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
        return ChunkParserListener.super.onChunkEnd(context, chunkIndex, skipped);
    }

    @Override
    default void onRecordingEnd(ParserContext context) {
        ChunkParserListener.super.onRecordingEnd(context);
    }
}

package io.jafar.parser.internal_api;

import io.jafar.parser.AbstractEvent;
import io.jafar.parser.MutableConstantPool;
import io.jafar.parser.MutableConstantPools;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.internal_api.metadata.MetadataClass;

import java.io.IOException;

public final class CheckpointEvent extends AbstractEvent {
    public final long startTime;
    public final long duration;
    public final int nextOffsetDelta;

    public final boolean isFlush;

    private final RecordingStream stream;

    CheckpointEvent(RecordingStream stream) throws IOException {
        super(stream);
        this.stream = stream;
        int size = (int) stream.readVarint();
        if (size == 0) {
            throw new IOException("Unexpected event size. Should be > 0");
        }
        long typeId = stream.readVarint();
        if (typeId != 1) {
            throw new IOException("Unexpected event type: " + typeId + " (should be 1)");
        }
        this.startTime = stream.readVarint();
        this.duration = stream.readVarint();
        this.nextOffsetDelta = (int)stream.readVarint();
        this.isFlush = stream.read() != 0;
    }

    void readConstantPools() throws IOException {
        RecordingParserContext context = stream.getContext();
        TypeFilter typeFilter = context.getTypeFilter();

        boolean skipAll = context.getConstantPools().isReady();

        long cpCount = stream.readVarint();
        for (long i = 0; i < cpCount; i++) {
            long typeId = 0;
            while ((typeId = stream.readVarint()) == 0) ; // workaround for a bug in JMC JFR writer
            try {
                MetadataClass clz = context.getMetadataLookup().getClass(typeId);
                if (clz == null) {
                    continue;
                }
                int count = (int) stream.readVarint();
                boolean skip = skipAll || (typeFilter != null && !typeFilter.test(clz));
                MutableConstantPool constantPool = skip ? null : ((MutableConstantPools) context.getConstantPools()).addOrGetConstantPool(stream, typeId, count);
                for (int j = 0; j < count; j++) {
                    long id = stream.readVarint();
                    if (!skip && !constantPool.containsKey(id)) {
                        constantPool.addOffset(id, stream.position());
                    }
                    clz.skip(stream);
                }
            } catch (IOException e) {
                throw e;
            }
        }
    }
}

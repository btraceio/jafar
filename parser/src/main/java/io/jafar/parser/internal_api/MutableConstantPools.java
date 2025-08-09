package io.jafar.parser.internal_api;

import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ConstantPools;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.stream.Stream;

public final class MutableConstantPools implements ConstantPools {
    private final Long2ObjectMap<MutableConstantPool> poolMap = new Long2ObjectOpenHashMap<>();

    private boolean ready = false;

    public MutableConstantPools() {
    }

    @Override
    public MutableConstantPool getConstantPool(long typeId) {
        return poolMap.get(typeId);
    }

    public MutableConstantPool addOrGetConstantPool(RecordingStream chunkStream, long typeId, int count) {
        MutableConstantPool p = poolMap.get(typeId);
        if (p == null) {
            p = new MutableConstantPool(chunkStream, typeId, count);
            poolMap.put(typeId, p);
        }
        return p;
    }

    @Override
    public boolean hasConstantPool(long typeId) {
        return poolMap.containsKey(typeId);
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    public void setReady() {
        ready = true;
    }

    @Override
    public Stream<? extends ConstantPool> pools() {
        return poolMap.values().stream();
    }
}

package io.jafar.parser.api.stateful;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Map;

public final class ConstantPools {
    private final Long2ObjectMap<ConstantPool> cpools = new Long2ObjectOpenHashMap<>();

    void add(long typeId, long id, Map<String, Object> value) {
        cpools.computeIfAbsent(typeId, k -> new ConstantPool()).add(id, value);
    }

    public ConstantPool getConstantPool(long typeId) {
        return cpools.get(typeId);
    }

    public Map<String, Object> getValue(long typeId, long id) {
        return cpools.getOrDefault(typeId, ConstantPool.EMPTY).get(id);
    }
}

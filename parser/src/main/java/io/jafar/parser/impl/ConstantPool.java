package io.jafar.parser.impl;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Map;

public final class ConstantPool {
    static final ConstantPool EMPTY = new ConstantPool(true);

    private final boolean immutable;
    private final Long2ObjectMap<Map<String, Object>> cpool = new Long2ObjectOpenHashMap<>();

    ConstantPool(boolean immutable) {
        this.immutable = immutable;
    }

    ConstantPool() {
        this(false);
    }

    void add(long id, Map<String, Object> value) {
        if (immutable) {
            throw new UnsupportedOperationException();
        }
        cpool.put(id, value);
    }

    public Map<String, Object> get(long id) {
        return cpool.get(id);
    }
}

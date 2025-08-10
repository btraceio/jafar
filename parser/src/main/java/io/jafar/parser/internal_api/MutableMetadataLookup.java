package io.jafar.parser.internal_api;

import io.jafar.parser.api.MetadataLookup;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Arrays;

public final class MutableMetadataLookup implements MetadataLookup {
    private String[] strings;
    private final Long2ObjectMap<MetadataClass> classes = new Long2ObjectOpenHashMap<>();

    @Override
    public String getString(int idx) {
        return strings[idx];
    }

    @Override
    public MetadataClass getClass(long id) {
        return classes.get(id);
    }

    public MetadataClass addClass(long id, MetadataClass clazz) {
        MetadataClass rslt = classes.get(id);
        if (rslt == null) {
            rslt = clazz;
            classes.put(id, clazz);
        }
        return rslt;
    }

    public void setStringtable(String[] stringTable) {
        this.strings = Arrays.copyOf(stringTable, stringTable.length);
    }

    public void bindDeserializers() {
        for (MetadataClass clazz : classes.values()) {
            clazz.bindDeserializer();
        }
    }

    public void clear() {
        strings = null;
        classes.clear();
    }
}

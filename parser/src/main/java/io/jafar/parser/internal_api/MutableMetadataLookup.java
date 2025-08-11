package io.jafar.parser.internal_api;

import java.util.Arrays;

import io.jafar.parser.api.MetadataLookup;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Mutable implementation of MetadataLookup that allows dynamic addition of metadata.
 * <p>
 * This class provides a mutable metadata lookup implementation that maintains
 * string tables and metadata classes. It allows adding new metadata classes
 * and binding deserializers dynamically during parsing.
 * </p>
 */
public final class MutableMetadataLookup implements MetadataLookup {
    /**
     * Public constructor for MutableMetadataLookup.
     * <p>
     * This class provides a mutable metadata lookup implementation that maintains
     * string tables and metadata classes.
     * </p>
     */
    public MutableMetadataLookup() {}
    
    /** Array of string constants for the current chunk. */
    private String[] strings;
    
    /** Map of class IDs to their metadata class instances. */
    private final Long2ObjectMap<MetadataClass> classes = new Long2ObjectOpenHashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public String getString(int idx) {
        return strings[idx];
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MetadataClass getClass(long id) {
        return classes.get(id);
    }

    /**
     * Adds a metadata class to the lookup, or returns an existing one if already present.
     * 
     * @param id the ID of the metadata class
     * @param clazz the metadata class to add
     * @return the metadata class instance (either the new one or existing one)
     */
    public MetadataClass addClass(long id, MetadataClass clazz) {
        MetadataClass rslt = classes.get(id);
        if (rslt == null) {
            rslt = clazz;
            classes.put(id, clazz);
        }
        return rslt;
    }

    /**
     * Sets the string table for this metadata lookup.
     * 
     * @param stringTable the array of string constants to use
     */
    public void setStringtable(String[] stringTable) {
        this.strings = Arrays.copyOf(stringTable, stringTable.length);
    }

    /**
     * Binds deserializers for all metadata classes in this lookup.
     * <p>
     * This method iterates through all registered metadata classes and
     * binds their deserializers, preparing them for use.
     * </p>
     */
    public void bindDeserializers() {
        for (MetadataClass clazz : classes.values()) {
            clazz.bindDeserializer();
        }
    }

    /**
     * Clears all metadata from this lookup.
     * <p>
     * This method removes all string tables and metadata classes,
     * effectively resetting the lookup to an empty state.
     * </p>
     */
    public void clear() {
        strings = null;
        classes.clear();
    }
}

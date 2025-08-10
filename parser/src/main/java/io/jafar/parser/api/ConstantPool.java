package io.jafar.parser.api;

import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * Interface for a constant pool that stores objects and their associated ids.
 */
public interface ConstantPool {
    Object get(long id);
    int size();

    boolean isEmpty();
    MetadataClass getType();
}

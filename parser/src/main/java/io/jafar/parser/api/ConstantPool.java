package io.jafar.parser.api;

import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * A constant pool storing objects by their ids for a specific JFR type.
 */
public interface ConstantPool {
    /** Returns the object for the given id, or {@code null} if not found. */
    Object get(long id);
    int size();

    boolean isEmpty();
    /** Returns the metadata type associated with this pool. Advanced use. */
    MetadataClass getType();
}

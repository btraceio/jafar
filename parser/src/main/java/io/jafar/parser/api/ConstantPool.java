package io.jafar.parser.api;

import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * A constant pool storing objects by their ids for a specific JFR type.
 */
public interface ConstantPool {
    /** 
     * Returns the object for the given id, or {@code null} if not found. 
     * 
     * @param id the identifier of the object to retrieve
     * @return the object associated with the given id, or {@code null} if not found
     */
    Object get(long id);
    
    /**
     * Returns the number of objects in this constant pool.
     * 
     * @return the size of the constant pool
     */
    int size();

    /**
     * Checks if this constant pool is empty.
     * 
     * @return true if the constant pool contains no objects, false otherwise
     */
    boolean isEmpty();
    
    /** 
     * Returns the metadata type associated with this pool. Advanced use. 
     * 
     * @return the metadata class representing the type of objects in this pool
     */
    MetadataClass getType();
}

package io.jafar.parser.internal_api;

import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * Processor for constant pool values in JFR recordings.
 * <p>
 * This interface extends ValueProcessor to provide specific handling for constant pool values.
 * It allows custom processing of constant pool entries during JFR parsing.
 * </p>
 */
public interface ConstantPoolValueProcessor extends ValueProcessor {
    /**
     * A no-operation implementation that does nothing for all callbacks.
     */
    ConstantPoolValueProcessor NOOP = new ConstantPoolValueProcessor() {};

    /**
     * Called when processing of a constant pool value begins.
     * 
     * @param type the metadata class type of the constant pool value
     * @param id the identifier of the constant pool value
     */
    default void onConstantPoolValueStart(MetadataClass type, long id) {};
    
    /**
     * Called when processing of a constant pool value ends.
     * 
     * @param type the metadata class type of the constant pool value
     * @param id the identifier of the constant pool value
     */
    default void onConstantPoolValueEnd(MetadataClass type, long id) {};
}

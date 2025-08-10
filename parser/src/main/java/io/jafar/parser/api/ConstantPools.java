package io.jafar.parser.api;

import java.util.stream.Stream;

/**
 * Access to constant pools keyed by type id.
 */
public interface ConstantPools {
    ConstantPool getConstantPool(long typeId);
    boolean hasConstantPool(long typeId);
    /** True once all constant pools for the current chunk are available. */
    boolean isReady();
    void setReady();

    Stream<? extends ConstantPool> pools();
}

package io.jafar.parser.api;

import java.util.stream.Stream;

/**
 * Interface for managing constant pools of objects associated with their corresponding type ids.
 */
public interface ConstantPools {
    ConstantPool getConstantPool(long typeId);
    boolean hasConstantPool(long typeId);
    boolean isReady();
    void setReady();

    Stream<? extends ConstantPool> pools();
}

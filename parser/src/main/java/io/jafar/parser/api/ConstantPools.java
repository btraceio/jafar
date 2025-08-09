package io.jafar.parser.api;

import java.util.stream.Stream;

public interface ConstantPools {
    ConstantPool getConstantPool(long typeId);
    boolean hasConstantPool(long typeId);
    boolean isReady();
    void setReady();

    Stream<? extends ConstantPool> pools();
}

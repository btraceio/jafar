package io.jafar.parser.internal_api;

import io.jafar.parser.internal_api.metadata.MetadataClass;

public interface ConstantPoolValueProcessor extends ValueProcessor {
    ConstantPoolValueProcessor NOOP = new ConstantPoolValueProcessor() {};

    default void onConstantPoolValueStart(MetadataClass type, long id) {};
    default void onConstantPoolValueEnd(MetadataClass type, long id) {};
}

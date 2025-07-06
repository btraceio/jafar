package io.jafar.parser.internal_api;

import io.jafar.parser.internal_api.metadata.MetadataClass;

public interface ValueProcessor {
    default void onShortValue(MetadataClass type, String fld, short value) {}
    default void onCharValue(MetadataClass type, String fld, char value) {}
    default void onIntValue(MetadataClass owner, String fld, long value) {}
    default void onLongValue(MetadataClass type, String fld, long value) {}
    default void onByteValue(MetadataClass type, String fld, byte value) {}
    default void onBooleanValue(MetadataClass owner, String fld, boolean value) {}
    default void onDoubleValue(MetadataClass owner, String fld, double value) {}
    default void onFloatValue(MetadataClass owner, String fld, float value) {}
    default void onStringValue(MetadataClass owner, String fld, String value) {}
    default void onConstantPoolIndex(MetadataClass owner, String fld, MetadataClass type, long pointer) {}
    default void onArrayStart(MetadataClass owner, String fld, MetadataClass type, int len) {}
    default void onArrayEnd(MetadataClass owner, String fld, MetadataClass type) {}
    default void onComplexValueStart(MetadataClass owner, String fld, MetadataClass type) {}
    default void onComplexValueEnd(MetadataClass owner, String fld, MetadataClass type) {}
}

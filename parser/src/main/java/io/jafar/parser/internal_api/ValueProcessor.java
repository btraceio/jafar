package io.jafar.parser.internal_api;

import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * Interface for processing JFR data values during parsing.
 * <p>
 * This interface provides callback methods for handling different types of values
 * encountered during JFR parsing. All methods have default implementations that
 * do nothing, allowing implementors to override only the methods they need.
 * </p>
 */
public interface ValueProcessor {
    /**
     * Called when a short value is encountered.
     * 
     * @param type the metadata class type
     * @param fld the field name
     * @param value the short value
     */
    default void onShortValue(MetadataClass type, String fld, short value) {}
    
    /**
     * Called when a char value is encountered.
     * 
     * @param type the metadata class type
     * @param fld the field name
     * @param value the char value
     */
    default void onCharValue(MetadataClass type, String fld, char value) {}
    
    /**
     * Called when an int value is encountered.
     * 
     * @param owner the owner metadata class
     * @param fld the field name
     * @param value the int value
     */
    default void onIntValue(MetadataClass owner, String fld, long value) {}
    
    /**
     * Called when a long value is encountered.
     * 
     * @param type the metadata class type
     * @param fld the field name
     * @param value the long value
     */
    default void onLongValue(MetadataClass type, String fld, long value) {}
    
    /**
     * Called when a byte value is encountered.
     * 
     * @param type the metadata class type
     * @param fld the field name
     * @param value the byte value
     */
    default void onByteValue(MetadataClass type, String fld, byte value) {}
    
    /**
     * Called when a boolean value is encountered.
     * 
     * @param owner the owner metadata class
     * @param fld the field name
     * @param value the boolean value
     */
    default void onBooleanValue(MetadataClass owner, String fld, boolean value) {}
    
    /**
     * Called when a double value is encountered.
     * 
     * @param owner the owner metadata class
     * @param fld the field name
     * @param value the double value
     */
    default void onDoubleValue(MetadataClass owner, String fld, double value) {}
    
    /**
     * Called when a float value is encountered.
     * 
     * @param owner the owner metadata class
     * @param fld the field name
     * @param value the float value
     */
    default void onFloatValue(MetadataClass owner, String fld, float value) {}
    
    /**
     * Called when a string value is encountered.
     * 
     * @param owner the owner metadata class
     * @param fld the field name
     * @param value the string value
     */
    default void onStringValue(MetadataClass owner, String fld, String value) {}
    
    /**
     * Called when a constant pool index is encountered.
     * 
     * @param owner the owner metadata class
     * @param fld the field name
     * @param type the metadata class type
     * @param pointer the constant pool pointer
     */
    default void onConstantPoolIndex(MetadataClass owner, String fld, MetadataClass type, long pointer) {}
    
    /**
     * Called when an array starts.
     * 
     * @param owner the owner metadata class
     * @param fld the field name
     * @param type the metadata class type
     * @param len the array length
     */
    default void onArrayStart(MetadataClass owner, String fld, MetadataClass type, int len) {}
    
    /**
     * Called when an array ends.
     * 
     * @param owner the owner metadata class
     * @param fld the field name
     * @param type the metadata class type
     */
    default void onArrayEnd(MetadataClass owner, String fld, MetadataClass type) {}
    
    /**
     * Called when a complex value starts.
     * 
     * @param owner the owner metadata class
     * @param fld the field name
     * @param type the metadata class type
     */
    default void onComplexValueStart(MetadataClass owner, String fld, MetadataClass type) {}
    
    /**
     * Called when a complex value ends.
     * 
     * @param owner the owner metadata class
     * @param fld the field name
     * @param type the metadata class type
     */
    default void onComplexValueEnd(MetadataClass owner, String fld, MetadataClass type) {}
}

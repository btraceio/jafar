package io.jafar.parser.api;

/**
 * Represents an array value in JFR data.
 * <p>
 * This interface provides access to array data parsed from JFR recordings.
 * </p>
 */
public interface ArrayType {
    /**
     * Gets the component type of this array.
     * 
     * @return the Class object representing the component type of the array
     */
    Class<?> getType();
    
    /**
     * Gets the actual array data.
     * 
     * @return the array object containing the parsed data
     */
    Object getArray();
}

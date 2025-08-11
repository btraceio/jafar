package io.jafar.parser.api;

import java.util.Map;

/**
 * Represents a complex value in JFR data.
 * <p>
 * This interface provides access to complex structured data parsed from JFR recordings.
 * Complex values are typically represented as maps of field names to their values.
 * </p>
 */
public interface ComplexType {
    /**
     * Gets the value of this complex type as a map.
     * <p>
     * The map contains field names as keys and their corresponding values as values.
     * Values can be primitives, strings, or other complex types.
     * </p>
     * 
     * @return a map representing the complex value structure
     */
    Map<String, Object> getValue();
}

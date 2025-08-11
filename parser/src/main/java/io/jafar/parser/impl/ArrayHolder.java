package io.jafar.parser.impl;

import java.lang.reflect.Array;
import java.util.Arrays;

import io.jafar.parser.api.ArrayType;

/**
 * Implementation of ArrayType that holds array data from JFR recordings.
 * <p>
 * This class provides a concrete implementation for storing and managing
 * array values parsed from JFR data, supporting all primitive types and objects.
 * </p>
 */
public final class ArrayHolder implements ArrayType {
    /** The type name of the array elements. */
    private final String type;
    
    /** The actual array object. */
    private final Object array;
    
    /** The current index for adding elements. */
    private int index = 0;

    /**
     * Constructs a new ArrayHolder with the specified type and length.
     * 
     * @param type the type name of the array elements
     * @param len the length of the array to create
     */
    ArrayHolder(String type, int len) {
        this.type = type;
        switch (type) {
            case "short":
                array = new short[len];
                break;
            case "char":
                array = new char[len];
                break;
            case "int":
                array = new int[len];
                break;
            case "long":
                array = new long[len];
                break;
            case "byte":
                array = new byte[len];
                break;
            case "boolean":
                array = new boolean[len];
                break;
            case "double":
                array = new double[len];
                break;
            case "float":
                array = new float[len];
                break;
            default:
                array = new Object[len];
        }
    }

    /**
     * Adds a value to the array at the current index.
     * 
     * @param value the value to add
     */
    void add(Object value) {
        Array.set(array, index++, value);
    }

    @Override
    public Object getArray() {
        return array;
    }

    @Override
    public Class<?> getType() {
        return array.getClass();
    }

    @Override
    public String toString() {
        switch (type) {
            case "short":
                return Arrays.toString((short[]) array);
            case "char":
                return Arrays.toString((char[]) array);
            case "int":
                return Arrays.toString((int[]) array);
            case "long":
                return Arrays.toString((long[]) array);
            case "byte":
                return Arrays.toString((byte[]) array);
            case "boolean":
                return Arrays.toString((boolean[]) array);
            case "double":
                return Arrays.toString((double[]) array);
            case "float":
                return Arrays.toString((float[]) array);
            default:
                return Arrays.toString((Object[]) array);
        }
    }
}

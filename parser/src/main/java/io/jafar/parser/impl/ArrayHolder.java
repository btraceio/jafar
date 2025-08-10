package io.jafar.parser.impl;

import io.jafar.parser.api.ArrayType;

import java.lang.reflect.Array;
import java.util.Arrays;

public final class ArrayHolder implements ArrayType {
    private final String type;
    private final Object array;
    private int index = 0;

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
        return switch (type) {
            case "short" -> Arrays.toString((short[]) array);
            case "char" -> Arrays.toString((char[]) array);
            case "int" -> Arrays.toString((int[]) array);
            case "long" -> Arrays.toString((long[]) array);
            case "byte" -> Arrays.toString((byte[]) array);
            case "boolean" -> Arrays.toString((boolean[]) array);
            case "double" -> Arrays.toString((double[]) array);
            case "float" -> Arrays.toString((float[]) array);
            default -> Arrays.toString((Object[]) array);
        };
    }
}

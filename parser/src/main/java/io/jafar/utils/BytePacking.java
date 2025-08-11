package io.jafar.utils;

import java.nio.ByteOrder;

/**
 * Utility class for packing bytes into integers.
 * <p>
 * This class provides methods for efficiently packing multiple byte values into a single integer,
 * with support for different byte orders.
 * </p>
 */
public class BytePacking {
    /**
     * Public constructor for BytePacking.
     * <p>
     * This class provides utility methods for packing bytes and does not maintain state.
     * </p>
     */
    public BytePacking() {}
    
    /**
     * Packs four characters into a single integer value.
     * <p>
     * The method packs four 8-bit ASCII characters into a 32-bit integer, with support
     * for both big-endian and little-endian byte orders.
     * </p>
     * 
     * @param order the byte order to use for packing
     * @param a the first character (least significant byte)
     * @param b the second character
     * @param c the third character
     * @param d the fourth character (most significant byte)
     * @return the packed integer value
     * @throws AssertionError if any character is not ASCII (8-bit)
     */
    public static int pack(ByteOrder order, char a, char b, char c, char d) {
        assert ((a | b | c | d) & 0xFF00) == 0 : "not ASCII";
        int packed = (d << 24) | (c << 16) | (b << 8) | a;
        return order == ByteOrder.BIG_ENDIAN ? Integer.reverseBytes(packed) : packed;
    }
}

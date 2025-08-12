package io.jafar.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class BytePackingTest {

    public static Stream<Arguments> byteOrders() {
        return Stream.of(Arguments.of(BIG_ENDIAN), Arguments.of(LITTLE_ENDIAN));
    }

    @ParameterizedTest
    @MethodSource("byteOrders")
    public void testPackedMagic(ByteOrder order) {
        int packed = BytePacking.pack(order, 'F', 'L', 'R', '\0');
        ByteBuffer buffer = ByteBuffer.allocate(4).order(order);
        buffer.putInt(0, packed);
        // no matter what endianness we read/write, we should get the same magic
        byte[] expected = new byte[] {'F', 'L', 'R', '\0'};
        byte[] actual = new byte[4];
        buffer.get(actual);
        assertArrayEquals(expected, actual);
    }
}

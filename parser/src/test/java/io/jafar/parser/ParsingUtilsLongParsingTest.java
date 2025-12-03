package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for ParsingUtils.parseLongSWAR() method.
 *
 * <p>Tests cover:
 * - Negative timezone offsets (the reported bug case)
 * - 8-digit blocks (where the bugs were)
 * - Edge cases (Long.MIN_VALUE, Long.MAX_VALUE)
 * - Overflow detection
 */
public class ParsingUtilsLongParsingTest {

  @Test
  void parseLongSWAR_negativeTimezoneOffsets() {
    // GMT-5 (EST) in milliseconds - the reported bug case
    assertEquals(-18000000L, ParsingUtils.parseLongSWAR("-18000000"));

    // GMT-8 (PST) in milliseconds
    assertEquals(-28800000L, ParsingUtils.parseLongSWAR("-28800000"));

    // GMT+12 (Fiji) in milliseconds
    assertEquals(43200000L, ParsingUtils.parseLongSWAR("43200000"));

    // Large negative
    assertEquals(-123456789L, ParsingUtils.parseLongSWAR("-123456789"));
  }

  @Test
  void parseLongSWAR_eightDigitBlocks() {
    // Exactly 8 digits - triggers parse8() code path
    assertEquals(12345678L, ParsingUtils.parseLongSWAR("12345678"));
    assertEquals(-12345678L, ParsingUtils.parseLongSWAR("-12345678"));

    // Test that last 2 digits are parsed correctly (Bug #2)
    assertEquals(10000001L, ParsingUtils.parseLongSWAR("10000001"));
    assertEquals(10000099L, ParsingUtils.parseLongSWAR("10000099"));
    assertEquals(-10000001L, ParsingUtils.parseLongSWAR("-10000001"));
    assertEquals(-10000099L, ParsingUtils.parseLongSWAR("-10000099"));

    // 16 digits (two 8-digit blocks)
    assertEquals(1234567890123456L, ParsingUtils.parseLongSWAR("1234567890123456"));
    assertEquals(-1234567890123456L, ParsingUtils.parseLongSWAR("-1234567890123456"));
  }

  @Test
  void parseLongSWAR_basicCases() {
    // Zero
    assertEquals(0L, ParsingUtils.parseLongSWAR("0"));
    assertEquals(0L, ParsingUtils.parseLongSWAR("-0"));

    // Single digit
    assertEquals(5L, ParsingUtils.parseLongSWAR("5"));
    assertEquals(-5L, ParsingUtils.parseLongSWAR("-5"));

    // Multiple digits (< 8)
    assertEquals(123L, ParsingUtils.parseLongSWAR("123"));
    assertEquals(-123L, ParsingUtils.parseLongSWAR("-123"));
    assertEquals(1234567L, ParsingUtils.parseLongSWAR("1234567"));
    assertEquals(-1234567L, ParsingUtils.parseLongSWAR("-1234567"));

    // Leading zeros
    assertEquals(123L, ParsingUtils.parseLongSWAR("0000123"));
    assertEquals(-123L, ParsingUtils.parseLongSWAR("-0000123"));
  }

  @Test
  void parseLongSWAR_edgeCases() {
    // Long.MIN_VALUE: -9223372036854775808
    assertEquals(Long.MIN_VALUE, ParsingUtils.parseLongSWAR("-9223372036854775808"));

    // Long.MAX_VALUE: 9223372036854775807
    assertEquals(Long.MAX_VALUE, ParsingUtils.parseLongSWAR("9223372036854775807"));

    // Near boundaries
    assertEquals(Long.MIN_VALUE + 1, ParsingUtils.parseLongSWAR("-9223372036854775807"));
    assertEquals(Long.MAX_VALUE - 1, ParsingUtils.parseLongSWAR("9223372036854775806"));

    // Large valid negatives
    assertEquals(-1000000000000000000L, ParsingUtils.parseLongSWAR("-1000000000000000000"));
    assertEquals(-9000000000000000000L, ParsingUtils.parseLongSWAR("-9000000000000000000"));
  }

  @Test
  void parseLongSWAR_overflow() {
    // Should throw for values exceeding Long range
    assertThrows(
        NumberFormatException.class, () -> ParsingUtils.parseLongSWAR("-9223372036854775809"));
    assertThrows(
        NumberFormatException.class, () -> ParsingUtils.parseLongSWAR("9223372036854775808"));

    // Way over the limit
    assertThrows(
        NumberFormatException.class, () -> ParsingUtils.parseLongSWAR("-99999999999999999999"));
    assertThrows(
        NumberFormatException.class, () -> ParsingUtils.parseLongSWAR("99999999999999999999"));
  }

  @Test
  void parseLongSWAR_invalidInput() {
    // Invalid characters
    assertThrows(NumberFormatException.class, () -> ParsingUtils.parseLongSWAR("abc"));
    assertThrows(NumberFormatException.class, () -> ParsingUtils.parseLongSWAR("12a34"));
    assertThrows(NumberFormatException.class, () -> ParsingUtils.parseLongSWAR(""));
    assertThrows(NumberFormatException.class, () -> ParsingUtils.parseLongSWAR("-"));
  }

  @Test
  void parseLongSWAR_multipleEightDigitBlocks() {
    // Test numbers with multiple 8-digit blocks to ensure accumulation works
    assertEquals(123456789012345L, ParsingUtils.parseLongSWAR("123456789012345"));
    assertEquals(-123456789012345L, ParsingUtils.parseLongSWAR("-123456789012345"));

    // 19 digits (max for long)
    assertEquals(1234567890123456789L, ParsingUtils.parseLongSWAR("1234567890123456789"));
    assertEquals(-1234567890123456789L, ParsingUtils.parseLongSWAR("-1234567890123456789"));
  }
}

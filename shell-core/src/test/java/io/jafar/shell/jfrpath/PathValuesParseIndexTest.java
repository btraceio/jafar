package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link PathValues#parseIndex} must read exactly what {@link Integer#parseInt} reads: it replaced
 * a parseInt call whose {@link NumberFormatException} told a field name from an index.
 */
class PathValuesParseIndexTest {

  private static Integer reference(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @Test
  void agreesWithParseInt() {
    List<String> inputs =
        List.of(
            "0",
            "7",
            "42",
            "-1",
            "+3",
            "007",
            "2147483647",
            "-2147483648", // numbers
            "2147483648",
            "-2147483649",
            "99999999999", // overflow
            "",
            "-",
            "+",
            "--1",
            "+-1",
            "1-",
            "1a",
            "a1",
            "abc",
            "sampledThread", // not numbers
            " 1",
            "1 ",
            "1_000",
            "0x10",
            "1.5", // not numbers either
            "٣",
            "١٢"); // Arabic-Indic digits: parseInt accepts them
    for (String s : inputs) {
      assertEquals(reference(s), PathValues.parseIndex(s), () -> "input '" + s + "'");
    }
  }

  @Test
  void tokensAreUnchanged() {
    assertEquals(
        List.of("stackTrace", "frames", 0, "method", "name"),
        PathValues.buildPathTokens(List.of("stackTrace", "frames[0]", "method", "name")));
    assertEquals(List.of("a", 3, "b"), PathValues.buildPathTokens(List.of("a", "3", "b")));
    assertEquals(List.of("a"), PathValues.buildPathTokens(List.of("a[x]")));
  }
}

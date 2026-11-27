package io.jafar.hdump.shell.hdumppath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.hdump.shell.hdumppath.HdumpPath.CheckLeaksOp;
import io.jafar.hdump.shell.hdumppath.HdumpPath.HeadOp;
import io.jafar.hdump.shell.hdumppath.HdumpPath.RetainedBreakdownOp;
import io.jafar.hdump.shell.hdumppath.HdumpPath.TailOp;
import io.jafar.hdump.shell.hdumppath.HdumpPath.TopOp;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #312: count arguments were read with {@code intValue()}: {@code head(-1)} reached {@code
 * limit(-1)}, {@code head(2.5)} became 2, and {@code head(3000000000)} wrapped to a negative count.
 */
class CountArgumentTest {

  /** Every count argument, with {@code %s} for the value. */
  private static final List<String> COUNT_ARGUMENTS =
      List.of(
          "objects | head(%s)",
          "objects | tail(%s)",
          "objects | top(%s, shallow)",
          "duplicates(depth=%s)",
          "objects | retainedBreakdown(depth=%s)",
          "objects | checkLeaks(detector=duplicates, threshold=%s)");

  @Test
  void aNegativeFractionalOrOverIntCountIsAParseError() {
    List<String> accepted = new ArrayList<>();
    for (String template : COUNT_ARGUMENTS) {
      for (String value : List.of("-1", "2.5", "3000000000", "3GB")) {
        String query = String.format(template, value);
        try {
          HdumpPathParser.parse(query);
          accepted.add(query);
        } catch (HdumpPathParseException expected) {
          assertTrue(
              expected.getMessage().contains("whole number of 0 or more"),
              query + ": " + expected.getMessage());
        }
      }
    }
    assertEquals(List.of(), accepted);
  }

  @Test
  void aSessionNumberIsAWholeInt() {
    // A session named by number; a name ("-1", "before") is an identifier, not a count
    for (String value : List.of("2.5", "3000000000")) {
      HdumpPathParseException e =
          assertThrows(
              HdumpPathParseException.class,
              () -> HdumpPathParser.parse("classes | join(session=" + value + ")"),
              value);
      assertTrue(e.getMessage().contains("whole number of 0 or more"), e.getMessage());
    }
    assertEquals(
        "2", ((HdumpPath.JoinOp) op("classes | join(session=2)")).sessionRef(), "session=2");
  }

  @Test
  void aSizeIsNotNegativeAndAFractionOfAByteRoundsDown() {
    CheckLeaksOp leaks =
        (CheckLeaksOp) op("objects | checkLeaks(detector=duplicates, minsize=1.3MB)");
    assertEquals(1363148, leaks.minSize()); // 1.3 * 1048576 = 1363148.8
    assertEquals(
        1536L,
        ((HdumpPath.DominatorsOp) op("objects | dominators(minRetained=1.5KB)")).minRetained());
    for (String query :
        List.of(
            "objects | checkLeaks(detector=duplicates, minsize=-1)",
            "objects | checkLeaks(detector=duplicates, minsize=3GB)", // the field is an int
            "objects | dominators(minRetained=-5MB)")) {
      HdumpPathParseException e =
          assertThrows(HdumpPathParseException.class, () -> HdumpPathParser.parse(query), query);
      assertTrue(e.getMessage().contains("size of 0 or more"), query + ": " + e.getMessage());
    }
  }

  @Test
  void aMalformedNumberIsNamedSo() {
    HdumpPathParseException e =
        assertThrows(
            HdumpPathParseException.class,
            () -> HdumpPathParser.parse("objects[shallowSize > 1e]"));
    assertTrue(e.getMessage().startsWith("Invalid number: 1e"), e.getMessage());
  }

  @Test
  void validCountsParseAsBefore() {
    assertEquals(new HeadOp(0), op("objects | head(0)"));
    assertEquals(new HeadOp(1024), op("objects | head(1k)"));
    assertEquals(new HeadOp(2), op("objects | head(2.0)"));
    assertEquals(new TailOp(7), op("objects | tail(7)"));
    assertEquals(5, ((TopOp) op("objects | top(5, shallow)")).n());
    assertEquals(new RetainedBreakdownOp(3), op("objects | retainedBreakdown(depth=3)"));
    CheckLeaksOp leaks =
        (CheckLeaksOp) op("objects | checkLeaks(detector=duplicates, threshold=10, minsize=1MB)");
    assertEquals(10, leaks.threshold());
    assertEquals(1024 * 1024, leaks.minSize());
    assertEquals(2, HdumpPathParser.parse("duplicates(depth=2)").rootParam());
  }

  @Test
  void aSizeTooLargeForALongIsAParseError() {
    // 9223372036854775807 * 1024 overflowed silently
    HdumpPathParseException e =
        assertThrows(
            HdumpPathParseException.class,
            () -> HdumpPathParser.parse("objects[shallowSize > 9223372036854775807K]"));
    assertTrue(e.getMessage().contains("out of range"), e.getMessage());
  }

  private static HdumpPath.PipelineOp op(String query) {
    List<HdumpPath.PipelineOp> pipeline = HdumpPathParser.parse(query).pipeline();
    return pipeline.get(pipeline.size() - 1);
  }
}

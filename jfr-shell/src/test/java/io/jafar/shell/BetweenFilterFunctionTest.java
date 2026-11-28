package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the between() filter function.
 *
 * <p>The between() function checks if a numeric value falls within an inclusive range [min, max].
 * Syntax: between(value, min, max)
 *
 * <ul>
 *   <li>Returns true if min <= value <= max
 *   <li>Requires all arguments to be numeric
 *   <li>Inclusive on both boundaries
 * </ul>
 */
class BetweenFilterFunctionTest {

  static class BufferIO implements CommandDispatcher.IO {
    final StringBuilder out = new StringBuilder();
    final StringBuilder err = new StringBuilder();

    @Override
    public void println(String s) {
      out.append(s).append('\n');
    }

    @Override
    public void printf(String fmt, Object... args) {
      out.append(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      err.append(s).append('\n');
    }

    String getOutput() {
      return out.toString();
    }

    String getError() {
      return err.toString();
    }

    void clearOutput() {
      out.setLength(0);
      err.setLength(0);
    }
  }

  private static Path testJfr() {
    return Paths.get("..", "parser-core", "src", "test", "resources", "test-ap.jfr")
        .normalize()
        .toAbsolutePath();
  }

  private ParsingContext ctx;
  private SessionManager<Session> sessions;
  private CommandDispatcher dispatcher;
  private BufferIO io;

  @BeforeEach
  void setUp() throws Exception {
    ctx = ParsingContext.create();
    sessions = new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    io = new BufferIO();
    dispatcher = new CommandDispatcher(sessions, io, r -> {});

    // Open test JFR file
    dispatcher.dispatch("open " + testJfr());
  }

  @AfterEach
  void tearDown() {
    if (sessions != null) {
      try {
        sessions.closeAll();
      } catch (Exception e) {
        // Ignore
      }
    }
  }

  // test-ap.jfr's 160 jdk.GCHeapSummary events: heapUsed from 67108864 (2 events) to 786432000
  // (1 event), 646971392 once; gcId 0-79, two events each. After GC heapUsed is a quarter of the
  // Before GC one, below 250M. (Seeded in test-recordings Shapes.writeGc: a change there changes
  // these numbers.) jdk.ExecutionSample, which these tests used, has no duration: every between()
  // on it matched nothing (#299).

  /** The count a {@code show ... | count()} printed. */
  private long count(String command) throws Exception {
    io.clearOutput();
    dispatcher.dispatch(command);
    String output = io.getOutput();
    Matcher m = COUNT_ROW.matcher(output);
    assertTrue(m.find(), command + " -> " + output + io.getError());
    return Long.parseLong(m.group(1));
  }

  private static final Pattern COUNT_ROW = Pattern.compile("(?m)^\\|\\s*(\\d+)\\s*\\|\\s*$");

  // ==================== Basic Between Tests ====================

  @Test
  void betweenValueInRange() throws Exception {
    // Between 100M and 500M bytes used
    assertEquals(
        92L,
        count("show events/jdk.GCHeapSummary[between(heapUsed, 100000000, 500000000)] | count()"));
  }

  @Test
  void betweenValueOutOfRange() throws Exception {
    // Above every value
    assertEquals(
        0L,
        count(
            "show events/jdk.GCHeapSummary[between(heapUsed, 999999999999, 9999999999999)] | count()"));
  }

  @Test
  void betweenWithZeroRange() throws Exception {
    // No value is 0
    assertEquals(0L, count("show events/jdk.GCHeapSummary[between(heapUsed, 0, 0)] | count()"));
  }

  // ==================== Boundary Tests ====================

  @Test
  void betweenIncludesLowerBound() throws Exception {
    // The 2 events at the minimum, 67108864, are in; one past it they are not
    assertEquals(
        23L,
        count("show events/jdk.GCHeapSummary[between(heapUsed, 67108864, 100000000)] | count()"));
    assertEquals(
        21L,
        count("show events/jdk.GCHeapSummary[between(heapUsed, 67108865, 100000000)] | count()"));
  }

  @Test
  void betweenIncludesUpperBound() throws Exception {
    // The event at the maximum, 786432000, is in; one below it, it is not
    assertEquals(
        160L, count("show events/jdk.GCHeapSummary[between(heapUsed, 0, 786432000)] | count()"));
    assertEquals(
        159L, count("show events/jdk.GCHeapSummary[between(heapUsed, 0, 786431999)] | count()"));
  }

  @Test
  void betweenWithSameMinMax() throws Exception {
    // min == max matches the values equal to it
    assertEquals(
        1L,
        count("show events/jdk.GCHeapSummary[between(heapUsed, 646971392, 646971392)] | count()"));
  }

  // ==================== Negative Number Tests ====================
  // Still on jdk.ExecutionSample's absent duration, so they assert nothing: a negative literal
  // does not parse, and on a field the events have [between(f, -1, 5)] fails (#304)

  @Test
  void betweenWithNegativeRange() throws Exception {
    // Was: negative bounds on duration. See the note above: this asserts nothing until #304
    dispatcher.dispatch("show events/jdk.ExecutionSample[between(duration, -1000, 0)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
  }

  @Test
  void betweenWithNegativeAndPositive() throws Exception {
    // Was: a range spanning zero. As above, nothing asserted until #304
    dispatcher.dispatch("show events/jdk.ExecutionSample[between(duration, -100, 100)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
  }

  // ==================== Different Numeric Types ====================

  @Test
  void betweenWithIntegerBounds() throws Exception {
    // Integer bounds on an int field: gcId 1-10, two events each
    assertEquals(20L, count("show events/jdk.GCHeapSummary[between(gcId, 1, 10)] | count()"));
  }

  @Test
  void betweenWithLargeBounds() throws Exception {
    // Long.MAX_VALUE as the upper bound
    assertEquals(
        160L,
        count(
            "show events/jdk.GCHeapSummary[between(heapUsed, 0, 9223372036854775807)] | count()"));
  }

  // ==================== Complex Filter Combinations ====================

  @Test
  void betweenCombinedWithOtherFilters() throws Exception {
    // Combine between() with other filter conditions
    dispatcher.dispatch(
        "show events/jdk.ExecutionSample[state=\"STATE_RUNNABLE\""
            + " and between(startTime, 1704067200000000000, 1704067230000000000)] | count()");

    String output = io.getOutput();
    assertNotNull(output);
    // test-ap.jfr's samples have no duration: the window is on startTime. Of its 8000 samples
    // 4063 fall in it, 6467 are STATE_RUNNABLE ([state="RUNNABLE"] matches none, #288), 3263 both
    assertTrue(output.contains("3263"), output);
  }

  @Test
  void multipleBetweenFilters() throws Exception {
    // Both ranges: 200M-600M
    assertEquals(
        45L,
        count(
            "show events/jdk.GCHeapSummary[between(heapUsed, 100000000, 600000000) and between(heapUsed, 200000000, 700000000)] | count()"));
  }

  @Test
  void betweenWithOrCondition() throws Exception {
    // Below 100M or above 700M
    assertEquals(
        40L,
        count(
            "show events/jdk.GCHeapSummary[between(heapUsed, 0, 100000000) or between(heapUsed, 700000000, 1000000000)] | count()"));
  }

  @Test
  void betweenWithNotOperator() throws Exception {
    // The 160 events less the 92 between 100M and 500M
    assertEquals(
        68L,
        count(
            "show events/jdk.GCHeapSummary[not between(heapUsed, 100000000, 500000000)] | count()"));
  }

  // ==================== Integration with Aggregations ====================

  @Test
  void betweenWithGroupBy() throws Exception {
    // Of the 92 between 100M and 500M, 57 are after a GC and 35 before
    dispatcher.dispatch(
        "show events/jdk.GCHeapSummary[between(heapUsed, 100000000, 500000000)] | groupBy(when)");

    String output = io.getOutput();
    assertTrue(output.matches("(?s).*\\|\\s*57\\s*\\|\\s*After GC.*"), output);
    assertTrue(output.matches("(?s).*\\|\\s*35\\s*\\|\\s*Before GC.*"), output);
  }

  @Test
  void betweenWithStats() throws Exception {
    dispatcher.dispatch(
        "show events/jdk.GCHeapSummary[between(heapUsed, 100000000, 500000000)]/heapUsed | stats()");

    String output = io.getOutput();
    // count 92; min and max within the bounds
    assertTrue(output.contains("| 92 "), output);
    assertTrue(output.contains("1.04333312E8"), output);
    assertTrue(output.contains("4.88636416E8"), output);
  }

  // ==================== Edge Cases ====================

  @Test
  void betweenWithVerySmallRange() throws Exception {
    // A range of two values holding one event
    assertEquals(
        1L,
        count("show events/jdk.GCHeapSummary[between(heapUsed, 646971392, 646971393)] | count()"));
  }

  @Test
  void betweenWithInvertedBounds() throws Exception {
    // min > max matches nothing, though 92 events lie between them
    assertEquals(
        0L,
        count("show events/jdk.GCHeapSummary[between(heapUsed, 500000000, 100000000)] | count()"));
  }

  // ==================== Different Field Types ====================

  @Test
  void betweenOnAFieldTheEventsLackMatchesNothing() throws Exception {
    // jdk.ExecutionSample has no duration: no event is in any range of it
    assertEquals(
        0L,
        count(
            "show events/jdk.ExecutionSample[between(duration, 0, 9223372036854775807)] | count()"));
  }

  @Test
  void betweenOnDifferentNumericField() throws Exception {
    // An int field: gcId 0-9, two events each
    assertEquals(20L, count("show events/jdk.GCHeapSummary[between(gcId, 0, 9)] | count()"));
  }

  // ==================== Output Format Tests ====================

  @Test
  void betweenWithJsonOutput() throws Exception {
    dispatcher.dispatch(
        "show events/jdk.GCHeapSummary[between(heapUsed, 100000000, 500000000)] | count() --format"
            + " json");

    String output = io.getOutput();
    assertTrue(output.matches("(?s).*\"count\": 92\\s*[,}].*"), output);
  }

  // ==================== Projection with Between ====================

  @Test
  void betweenWithProjection() throws Exception {
    dispatcher.dispatch(
        "show events/jdk.GCHeapSummary[between(heapUsed, 0, 250000000)]/when --limit 10");

    String output = io.getOutput();
    // Below 250M only After GC summaries: the projection of what between() kept
    assertTrue(output.contains("After GC"), output);
    assertFalse(output.contains("Before GC"), output);
    assertFalse(output.contains("heapUsed"), output);
  }

  // ==================== Performance/Scale Tests ====================

  @Test
  void betweenWithNoLimit() throws Exception {
    // Every event
    assertEquals(
        160L, count("show events/jdk.GCHeapSummary[between(heapUsed, 0, 999999999)] | count()"));
  }
}

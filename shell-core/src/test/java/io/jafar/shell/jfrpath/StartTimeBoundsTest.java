package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.TimeRange;
import org.junit.jupiter.api.Test;

/**
 * Which start-time range each filter shape yields. The range feeds chunk skipping, so the rule
 * under test is "never narrower than the filter": every shape that is not understood must give
 * {@link TimeRange#all()}.
 */
class StartTimeBoundsTest {

  private static TimeRange bounds(String filter) {
    return StartTimeBounds.of(JfrPathParser.parse("events/jdk.Any[" + filter + "]").predicates);
  }

  private static void assertUnbounded(String filter) {
    assertTrue(bounds(filter).isUnbounded(), filter + " gave " + bounds(filter));
  }

  @Test
  void comparisonsWithANumber() {
    assertEquals(TimeRange.since(100), bounds("startTime>=100"));
    assertEquals(TimeRange.since(100), bounds("startTime>100"), "strict is widened to inclusive");
    assertEquals(TimeRange.until(200), bounds("startTime<=200"));
    assertEquals(TimeRange.until(200), bounds("startTime<200"));
    assertEquals(TimeRange.between(150, 150), bounds("startTime=150"));
  }

  @Test
  void aFractionalNumberIsRoundedOutwards() {
    assertEquals(TimeRange.since(100), bounds("startTime>=100.5"));
    assertEquals(TimeRange.until(201), bounds("startTime<=200.5"));
  }

  /** The filter the analyses behind {@code jfr_use} and {@code jfr_tsa} build for a time window. */
  @Test
  void theWindowFilterOfTheAnalyses() {
    assertEquals(
        TimeRange.between(1794735974000000000L, 1794735975000000000L),
        bounds("startTime>=1794735974000000000 and startTime<=1794735975000000000"));
  }

  @Test
  void timeFunctions() {
    assertEquals(TimeRange.since(100), bounds("after(startTime, 100)"));
    assertEquals(TimeRange.until(200), bounds("before(startTime, 200)"));
    assertEquals(TimeRange.between(100, 200), bounds("between(startTime, 100, 200)"));

    long t = TimeFormats.parseEpochNanos("2024-08-13T16:24:00Z");
    assertEquals(TimeRange.since(t), bounds("after(startTime, \"2024-08-13T16:24:00Z\")"));
    assertEquals(TimeRange.until(t), bounds("before(startTime, \"2024-08-13T16:24:00Z\")"));
    assertEquals(
        TimeRange.between(t, t + 60_000_000_000L),
        bounds("between(startTime, \"2024-08-13T16:24:00Z\", \"2024-08-13T16:25:00Z\")"));

    long[] day = TimeFormats.dayBounds("2024-08-13");
    assertEquals(TimeRange.between(day[0], day[1]), bounds("on(startTime, \"2024-08-13\")"));
  }

  @Test
  void andNarrowsOrWidens() {
    assertEquals(TimeRange.between(100, 200), bounds("startTime>=100 and startTime<=200"));
    assertEquals(TimeRange.since(100), bounds("startTime>=100 and duration>5"));
    assertEquals(
        TimeRange.between(100, 400),
        bounds("(startTime>=100 and startTime<=200) or (startTime>=300 and startTime<=400)"));
    assertUnbounded("startTime>=100 or startTime<=50");
    assertUnbounded("startTime>=100 or duration>5");
  }

  @Test
  void whatIsNotUnderstoodGivesNoBound() {
    assertUnbounded("duration>5");
    assertUnbounded("after(duration, 100)");
    assertUnbounded("after(startTime, duration)");
    assertUnbounded("eventThread/startTime>=100");
    assertUnbounded("startTime!=100");
    assertUnbounded("contains(startTime, \"17\")");
  }

  /** The filter complains about such an argument when it meets an event; this must not. */
  @Test
  void anUnreadableTimeGivesNoBoundAndDoesNotThrow() {
    assertUnbounded("after(startTime, \"not a time\")");
    assertUnbounded("on(startTime, \"13/08/2024\")");
    assertEquals(TimeRange.since(100), bounds("startTime>=100 and after(startTime, \"nope\")"));
  }
}

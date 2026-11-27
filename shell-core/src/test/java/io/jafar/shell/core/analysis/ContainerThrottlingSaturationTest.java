package io.jafar.shell.core.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #263: {@code jdk.ContainerCPUThrottling} carries the cgroup's running {@code cpu.stat}
 * totals. The throttled share is the change between the readings that bracket the window, summed
 * over intervals whose counters did not decrease. The readings below are test-dd.jfr's (21668/7925
 * and 22268/8431 at about +0 s and +60 s), plus synthetic edge cases.
 */
class ContainerThrottlingSaturationTest {

  private static final long S = 1_000_000_000L;
  private static final long T0 = 1_765_998_909_886_743_716L; // test-dd.jfr's start

  @Test
  void theShareIsTheChangeBetweenReadingsNotTheirSum() {
    Map<String, Object> sat =
        saturation(
            null,
            null,
            read(10_000_000, 21668, 7925, 741_051_000_000L),
            read(60 * S, 22268, 8431, 810_455_115_000L));

    assertEquals(84.3, sat.get("value"));
    assertEquals("%", sat.get("unit"));
    assertEquals(506L, sat.get("throttledSlices"));
    assertEquals(600L, sat.get("elapsedSlices"));
    assertEquals(69_404_115_000L, sat.get("throttledTimeNs"));
    assertTrue(((String) sat.get("detail")).contains("over 60.0 s"), sat.toString());
  }

  @Test
  void readingsAreOrderedByTimeWhateverOrderTheyComeIn() {
    Map<String, Object> sat =
        saturation(null, null, read(60 * S, 22268, 8431, -1), read(10_000_000, 21668, 7925, -1));

    assertEquals(84.3, sat.get("value"));
    assertFalse(sat.containsKey("throttledTimeNs"), "time unknown: " + sat);
  }

  @Test
  void aWindowIsMeasuredBetweenTheReadingsThatBracketIt() {
    // test-dd.jfr's readings at +0.01 s and +60 s; before the fix 30-120 s and 1-121 s were N/A
    for (long[] window : new long[][] {{30, 120}, {1, 121}, {0, 30}}) {
      Map<String, Object> sat =
          saturation(
              T0 + window[0] * S,
              T0 + window[1] * S,
              read(10_000_000, 21668, 7925, 0),
              read(60 * S, 22268, 8431, 0));
      assertEquals(84.3, sat.get("value"), "window " + window[0] + "-" + window[1] + " s: " + sat);
    }
  }

  @Test
  void aWindowBetweenLaterReadingsDoesNotIncludeEarlierIntervals() {
    Map<String, Object> sat =
        saturation(
            T0 + 100 * S,
            T0 + 200 * S,
            read(0, 1000, 0, 0),
            read(100 * S, 2000, 0, 0),
            read(200 * S, 3000, 500, 0));

    assertEquals(50.0, sat.get("value"), sat.toString());
    assertEquals(1000L, sat.get("elapsedSlices"));
    assertEquals(T0 + 100 * S, sat.get("measuredFromEpochNanos"));
    assertEquals(T0 + 200 * S, sat.get("measuredToEpochNanos"));
  }

  @Test
  void intervalsWithDecreasingCountersAreSkipped() {
    // Second container run: the counters restart, then grow again
    Map<String, Object> sat =
        saturation(
            null,
            null,
            read(0, 21668, 7925, 0),
            read(60 * S, 22268, 8431, 0),
            read(120 * S, 30, 1, 0),
            read(180 * S, 630, 301, 0));

    assertEquals(1, sat.get("intervalsSkipped"), sat.toString());
    assertEquals(806L, sat.get("throttledSlices")); // 506 + 300
    assertEquals(1200L, sat.get("elapsedSlices")); // 600 + 600
    assertEquals(67.2, sat.get("value"));
  }

  @Test
  void neverMoreThanAllPeriodsNorNegative() {
    Map<String, Object> reset =
        saturation(null, null, read(0, 21668, 7925, 0), read(60 * S, 100, 50, 0));
    assertEquals("N/A", reset.get("value"), reset.toString());
    assertFalse(reset.containsKey("throttledSlices"), reset.toString());
    assertTrue(((String) reset.get("detail")).startsWith("Every interval"), reset.toString());

    Map<String, Object> inconsistent =
        saturation(null, null, read(0, 1000, 500, 0), read(60 * S, 1100, 700, 0));
    assertEquals("N/A", inconsistent.get("value"), inconsistent.toString());
    assertTrue(
        ((String) inconsistent.get("detail")).contains("inconsistent"), inconsistent.toString());
  }

  @Test
  void unreadableCountersAndSingleReadingsGiveNoShare() {
    Map<String, Object> one = saturation(null, null, read(0, 21668, 7925, 0));
    assertEquals("N/A", one.get("value"), "one reading");
    assertTrue(((String) one.get("detail")).startsWith("Fewer than two"), one.toString());
    assertEquals(
        "N/A",
        saturation(null, null, read(0, -1, -1, -1), read(60 * S, -2, -2, -2)).get("value"),
        "-1 not available, -2 not supported");
    Map<String, Object> middleUnreadable =
        saturation(
            null,
            null,
            read(0, 21668, 7925, 0),
            read(30 * S, -1, 8000, 0),
            read(60 * S, 22268, 8431, 0));
    assertEquals(84.3, middleUnreadable.get("value"), middleUnreadable.toString());
  }

  @Test
  void noThrottlingIsZeroPercentAndNoEventsIsNotAShare() {
    assertEquals(
        0.0, saturation(null, null, read(0, 1000, 0, 0), read(60 * S, 2000, 0, 0)).get("value"));
    Map<String, Object> none = JfrAnalyses.containerThrottlingSaturation(List.of(), null, null);
    assertFalse(none.containsKey("unit"), none.toString());
  }

  @Test
  void aWindowEndingAtAMiddleReadingStopsThere() {
    Map<String, Object> sat =
        saturation(
            T0,
            T0 + 100 * S,
            read(0, 1000, 0, 0),
            read(100 * S, 2000, 500, 0),
            read(200 * S, 3000, 500, 0));

    assertEquals(50.0, sat.get("value"), sat.toString()); // 25.0 if it ran to the last reading
    assertEquals(T0, sat.get("measuredFromEpochNanos"));
    assertEquals(T0 + 100 * S, sat.get("measuredToEpochNanos"));
  }

  @Test
  void aWindowOutsideTheReadingsIsNotMeasured() {
    List<Map<String, Object>> outside =
        List.of(
            saturation(T0 - 20 * S, T0 - 10 * S, read(0, 1000, 0, 0), read(60 * S, 2000, 500, 0)),
            saturation(T0 + 70 * S, T0 + 90 * S, read(0, 1000, 0, 0), read(60 * S, 2000, 500, 0)));
    for (Map<String, Object> sat : outside) {
      assertEquals("N/A", sat.get("value"), sat.toString());
      assertTrue(((String) sat.get("detail")).contains("covers the window"), sat.toString());
      assertFalse(sat.containsKey("intervalsSkipped"), sat.toString());
    }
  }

  @Test
  void aDecreaseInThrottledPeriodsAloneIsSkippedNotEmittedNegative() {
    Map<String, Object> sat =
        saturation(null, null, read(0, 1000, 500, 0), read(60 * S, 2000, 400, 0));

    assertEquals("N/A", sat.get("value"), sat.toString());
    assertEquals(1, sat.get("intervalsSkipped"));
    assertFalse(sat.containsKey("throttledSlices"), sat.toString());
    assertTrue(((String) sat.get("detail")).contains("inconsistent"), sat.toString());
  }

  @Test
  void aDecreaseInThrottledTimeAloneDropsTheTimeNotTheShare() {
    Map<String, Object> sat =
        saturation(null, null, read(0, 1000, 0, 800), read(60 * S, 2000, 500, 700));

    assertEquals(50.0, sat.get("value"), sat.toString());
    assertFalse(sat.containsKey("throttledTimeNs"), sat.toString());
  }

  @Test
  void equalTimestampsGiveTheSameResultInEitherOrder() {
    Map<String, Object> a = read(0, 1000, 100, 0);
    Map<String, Object> b = read(0, 1100, 150, 0);
    Map<String, Object> c = read(60 * S, 2000, 600, 0);

    assertEquals(
        JfrAnalyses.containerThrottlingSaturation(List.of(a, b, c), T0, null),
        JfrAnalyses.containerThrottlingSaturation(List.of(b, a, c), T0, null));
  }

  @Test
  void countersTooLargeToAddUpAreNotAShare() {
    Map<String, Object> sat =
        saturation(
            null,
            null,
            read(0, 0, 0, 0),
            read(1 * S, Long.MAX_VALUE, Long.MAX_VALUE / 2, 0),
            read(2 * S, 0, 0, 0),
            read(3 * S, Long.MAX_VALUE, Long.MAX_VALUE / 2, 0));

    assertEquals("N/A", sat.get("value"), sat.toString());
  }

  @Test
  void anInvertedWindowIsNotMeasuredEvenInsideOneInterval() {
    Map<String, Object> sat =
        saturation(T0 + 40 * S, T0 + 30 * S, read(0, 1000, 0, 0), read(60 * S, 2000, 500, 0));

    assertEquals("N/A", sat.get("value"), sat.toString());
    assertEquals("The window ends before it starts", sat.get("detail"));
  }

  @Test
  void theMeasuredSpanAndLengthLeaveOutSkippedIntervals() {
    Map<String, Object> sat =
        saturation(
            null,
            null,
            read(0, 21668, 7925, 0),
            read(60 * S, 30, 1, 0),
            read(120 * S, 630, 301, 0));

    assertEquals(50.0, sat.get("value"), sat.toString());
    assertEquals(T0 + 60 * S, sat.get("measuredFromEpochNanos"));
    assertEquals(T0 + 120 * S, sat.get("measuredToEpochNanos"));
    assertTrue(((String) sat.get("detail")).contains("over 60.0 s"), sat.toString());
  }

  @Test
  void equalTimestampsAndCountersWithDifferentTimesGiveOneResult() {
    Map<String, Object> a = read(0, 1000, 100, 11);
    Map<String, Object> b = read(0, 1000, 100, 15);
    Map<String, Object> c = read(60 * S, 2000, 600, 20);

    assertEquals(
        JfrAnalyses.containerThrottlingSaturation(List.of(a, b, c), null, null),
        JfrAnalyses.containerThrottlingSaturation(List.of(b, a, c), null, null));
  }

  @Test
  void anOverflowInThrottledTimeAloneKeepsTheShare() {
    // Two counted intervals each add Long.MAX_VALUE ns, around a skipped reset
    Map<String, Object> sat =
        saturation(
            null,
            null,
            read(0, 0, 0, 0),
            read(1 * S, 10, 5, Long.MAX_VALUE),
            read(2 * S, 5, 0, 0),
            read(3 * S, 15, 5, Long.MAX_VALUE));

    assertEquals(50.0, sat.get("value"), sat.toString());
    assertEquals(1, sat.get("intervalsSkipped"));
    assertFalse(sat.containsKey("throttledTimeNs"), sat.toString());
  }

  @Test
  void periodsBetweenTwoReadingsAtTheSameInstantAreNotAnInterval() {
    // Two containers report at t0; sorting them must not invent 4000 periods in no time
    Map<String, Object> sat =
        saturation(
            null, null, read(0, 5000, 100, 0), read(0, 1000, 0, 0), read(60 * S, 2000, 500, 0));

    // Which reading the later one continues is unknowable: no share, rather than a made-up 2.5%
    assertEquals("N/A", sat.get("value"), sat.toString());
    assertEquals(2, sat.get("intervalsSkipped"), sat.toString());
  }

  @Test
  void anInvertedWindowIsNotMeasuredEvenWithoutReadings() {
    Map<String, Object> sat =
        JfrAnalyses.containerThrottlingSaturation(List.of(), T0 + 40 * S, T0 + 30 * S);

    assertEquals("N/A", sat.get("value"), sat.toString());
  }

  private static Map<String, Object> saturation(Long from, Long to, Map<String, Object>... reads) {
    return JfrAnalyses.containerThrottlingSaturation(List.of(reads), from, to);
  }

  private static Map<String, Object> read(long offset, long elapsed, long throttled, long timeNs) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("startTime", T0 + offset);
    event.put("cpuElapsedSlices", elapsed);
    event.put("cpuThrottledSlices", throttled);
    event.put("cpuThrottledTime", timeNs);
    return event;
  }
}

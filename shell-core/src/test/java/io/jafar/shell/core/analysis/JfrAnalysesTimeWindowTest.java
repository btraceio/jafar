package io.jafar.shell.core.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code startTime}/{@code endTime} arguments of the analyses are nanoseconds from the
 * recording's start. They used to be compared with event start times, which are nanoseconds since
 * the epoch, without converting: an offset as {@code endTime} excluded every event.
 */
class JfrAnalysesTimeWindowTest {
  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  private static long number(Object value) {
    return ((Number) value).longValue();
  }

  private static Map<String, Object> tsa(JFRSession session, Long start, Long end)
      throws Exception {
    Map<String, Object> args = new HashMap<>();
    if (start != null) args.put("startTime", start);
    if (end != null) args.put("endTime", end);
    args.put("includeInsights", false);
    return new JfrAnalyses().tsa(AnalysisTarget.of(1, session), args, Progress.NONE);
  }

  @Test
  void anOffsetWindowSelectsTheEventsOfThatPartOfTheRecording() throws Exception {
    try (JFRSession session = new JFRSession(TCK_RECORDING, ParsingContext.create())) {
      JfrPathEvaluator evaluator = new JfrPathEvaluator();
      long recordingStart = Long.MAX_VALUE;
      long recordingEnd = Long.MIN_VALUE;
      for (Map<String, Object> chunk : evaluator.evaluate(session, JfrPathParser.parse("chunks"))) {
        long start = number(chunk.get("startNanos"));
        recordingStart = Math.min(recordingStart, start);
        recordingEnd = Math.max(recordingEnd, start + number(chunk.get("duration")));
      }
      long half = (recordingEnd - recordingStart) / 2;

      Map<String, Long> counts = evaluator.countAllEventTypes(session);
      String samples =
          counts.containsKey("jdk.ExecutionSample")
              ? "jdk.ExecutionSample"
              : "datadog.ExecutionSample";
      long total = counts.get(samples);
      long firstHalf =
          number(
              evaluator
                  .evaluate(
                      session,
                      JfrPathParser.parse(
                          "events/"
                              + samples
                              + "[startTime>="
                              + recordingStart
                              + " and startTime<="
                              + (recordingStart + half)
                              + "] | count()"))
                  .get(0)
                  .get("count"));
      // the window must cut the recording, or this test could not tell a filter from none
      assertTrue(firstHalf > 0 && firstHalf < total, firstHalf + " of " + total + " samples");

      assertEquals(total, number(tsa(session, null, null).get("totalSamples")), "no window");

      Map<String, Object> byOffset = tsa(session, 0L, half);
      assertEquals(firstHalf, number(byOffset.get("totalSamples")), "offsets 0.." + half);
      @SuppressWarnings("unchecked")
      Map<String, Object> window = (Map<String, Object>) byOffset.get("timeWindow");
      assertEquals(0L, number(window.get("startTime")), "the argument as given");
      assertEquals(half, number(window.get("endTime")), "the argument as given");
      assertEquals(recordingStart, number(window.get("recordingStartEpochNanos")));
      assertEquals(recordingStart, number(window.get("startTimeEpochNanos")));
      assertEquals(recordingStart + half, number(window.get("endTimeEpochNanos")));

      // epoch nanoseconds, as copied from an event's startTime, mean the same window
      Map<String, Object> byEpoch = tsa(session, recordingStart, recordingStart + half);
      assertEquals(firstHalf, number(byEpoch.get("totalSamples")), "epoch bounds");

      // one bound only
      assertEquals(firstHalf, number(tsa(session, null, half).get("totalSamples")), "end only");
      long secondHalf = number(tsa(session, half + 1, null).get("totalSamples"));
      assertEquals(total - firstHalf, secondHalf, "start only");
    }
  }

  @Test
  void argumentsBelowTheRecordingStartAreOffsets() {
    long recordingStart = 1_765_998_909_886_743_716L;
    assertNull(JfrAnalyses.TimeWindow.toEpochNanos(null, recordingStart));
    assertEquals(recordingStart, JfrAnalyses.TimeWindow.toEpochNanos(0L, recordingStart));
    assertEquals(
        recordingStart + 1_000_000_000L,
        JfrAnalyses.TimeWindow.toEpochNanos(1_000_000_000L, recordingStart));
    // a whole day into the recording is still an offset
    assertEquals(
        recordingStart + 86_400_000_000_000L,
        JfrAnalyses.TimeWindow.toEpochNanos(86_400_000_000_000L, recordingStart));
    // at or after the recording's start: already an epoch timestamp
    assertEquals(
        recordingStart, JfrAnalyses.TimeWindow.toEpochNanos(recordingStart, recordingStart));
    assertEquals(
        recordingStart + 5,
        JfrAnalyses.TimeWindow.toEpochNanos(recordingStart + 5, recordingStart));
  }

  @Test
  void theFilterComparesWithEpochNanoseconds() {
    JfrAnalyses analyses = new JfrAnalyses();
    assertEquals("", analyses.buildTimeFilter(null));
    assertEquals(
        "[startTime>=1000 and startTime<=1500]",
        analyses.buildTimeFilter(new JfrAnalyses.TimeWindow(0L, 500L, 1000L, 1000L, 1500L)));
    assertEquals(
        "[startTime<=1500]",
        analyses.buildTimeFilter(new JfrAnalyses.TimeWindow(null, 500L, 1000L, null, 1500L)));
  }

  /** Kept in step with the schema text of {@code jfr_use} and {@code jfr_tsa}. */
  @Test
  void theResultSaysHowTheWindowWasRead() {
    Map<String, Object> map = new JfrAnalyses.TimeWindow(0L, 500L, 1000L, 1000L, 1500L).toMap();
    assertEquals(
        List.of(
            "startTime",
            "endTime",
            "recordingStartEpochNanos",
            "startTimeEpochNanos",
            "endTimeEpochNanos"),
        List.copyOf(map.keySet()));
  }
}

package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JfrPathDateTimeFunctionsTest {

  // Three fixed instants: two on 2024-08-13, one on 2024-08-14
  private static final long T1 =
      Instant.parse("2024-08-13T16:00:00Z").getEpochSecond() * 1_000_000_000L;
  private static final long T2 =
      Instant.parse("2024-08-13T17:00:00Z").getEpochSecond() * 1_000_000_000L;
  private static final long T3 =
      Instant.parse("2024-08-14T08:00:00Z").getEpochSecond() * 1_000_000_000L;

  private JFRSession session;
  private JfrPathEvaluator.EventSource twoEvents;

  @BeforeEach
  void setUp() {
    session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));
    when(session.getAvailableTypes())
        .thenReturn(java.util.Set.of("jdk.ExecutionSample", "jdk.JavaMonitorEnter"));

    twoEvents =
        (rec, consumer) -> {
          consumer.accept(
              new JfrPathEvaluator.Event(
                  "jdk.ExecutionSample", Map.of("startTime", T1, "duration", 500_000L)));
          consumer.accept(
              new JfrPathEvaluator.Event(
                  "jdk.ExecutionSample", Map.of("startTime", T2, "duration", 1_500_000_000L)));
        };
  }

  // =========================================================================
  // Filter predicates
  // =========================================================================

  @Nested
  class BeforeFilter {
    @Test
    void matchesEventsBeforeThreshold() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample[before(startTime, \"2024-08-13T16:30:00Z\")]");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(1, out.size());
      assertEquals(T1, out.get(0).get("startTime"));
    }

    @Test
    void rejectsEventsAfterThreshold() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample[before(startTime, \"2024-08-13T15:00:00Z\")]");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertTrue(out.isEmpty());
    }
  }

  @Nested
  class AfterFilter {
    @Test
    void matchesEventsAfterThreshold() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample[after(startTime, \"2024-08-13T16:30:00Z\")]");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(1, out.size());
      assertEquals(T2, out.get(0).get("startTime"));
    }

    @Test
    void rejectsEventsBeforeThreshold() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample[after(startTime, \"2024-08-14T00:00:00Z\")]");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertTrue(out.isEmpty());
    }
  }

  @Nested
  class BetweenWithDatetimeStrings {
    @Test
    void matchesEventsInRange() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample"
                  + "[between(startTime, \"2024-08-13T15:00:00Z\", \"2024-08-13T16:30:00Z\")]");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(1, out.size());
      assertEquals(T1, out.get(0).get("startTime"));
    }

    @Test
    void matchesBothEventsInWiderRange() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample"
                  + "[between(startTime, \"2024-08-13T15:00:00Z\", \"2024-08-13T18:00:00Z\")]");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());
    }

    @Test
    void numericBoundsContinueToWork() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      // between with numeric bounds: T1 <= x <= T2 (exact)
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample[between(startTime, " + T1 + ", " + T2 + ")]");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());
    }
  }

  @Nested
  class OnFilter {
    @Test
    void matchesEventsOnTheGivenDate() throws Exception {
      // T1 and T2 are both on 2024-08-13 UTC; they may be on the same local date too
      // Compute start-of-day in local zone to know what to expect
      java.time.LocalDate date = java.time.LocalDate.parse("2024-08-13");
      long startOfDay =
          date.atStartOfDay(ZoneId.systemDefault()).toInstant().getEpochSecond() * 1_000_000_000L;
      long endOfDay =
          date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().getEpochSecond()
              * 1_000_000_000L;

      // Only count events that actually fall on this local date
      long expectedCount =
          (T1 >= startOfDay && T1 < endOfDay ? 1 : 0) + (T2 >= startOfDay && T2 < endOfDay ? 1 : 0);

      var eval = new JfrPathEvaluator(twoEvents);
      var q = JfrPathParser.parse("events/jdk.ExecutionSample[on(startTime, \"2024-08-13\")]");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(expectedCount, out.size());
    }

    @Test
    void rejectsEventsOnOtherDate() throws Exception {
      // T3 is on 2024-08-14; T1 and T2 are on 2024-08-13
      var src =
          (JfrPathEvaluator.EventSource)
              (rec, consumer) ->
                  consumer.accept(
                      new JfrPathEvaluator.Event(
                          "jdk.ExecutionSample", Map.of("startTime", T3, "duration", 0L)));

      var eval = new JfrPathEvaluator(src);
      var q = JfrPathParser.parse("events/jdk.ExecutionSample[on(startTime, \"2024-08-13\")]");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertTrue(out.isEmpty());
    }
  }

  // =========================================================================
  // select() expression functions
  // =========================================================================

  @Nested
  class AsDateTimeInSelect {
    @Test
    void formatsEpochNanosAsDatetimeString() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse("events/jdk.ExecutionSample | select(asDateTime(startTime) as t)");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());
      String expected =
          DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
              Instant.ofEpochSecond(0, T1).atZone(ZoneId.systemDefault()));
      assertEquals(expected, out.get(0).get("t"));
    }

    @Test
    void respectsCustomFormat() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample | select(asDateTime(startTime, \"yyyy-MM-dd\") as d)");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());
      // All T1/T2 events are on 2024-08-13 UTC; local date may differ at extreme offsets
      // Just verify the value is a non-empty string in yyyy-MM-dd format
      String d = (String) out.get(0).get("d");
      assertNotNull(d);
      assertTrue(d.matches("\\d{4}-\\d{2}-\\d{2}"), "Expected date format, got: " + d);
    }
  }

  @Nested
  class TruncateInSelect {
    @Test
    void truncatesToMinuteBoundary() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample | select(truncate(startTime, \"minute\") as bucket)");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());

      ZonedDateTime zdt = Instant.ofEpochSecond(0, T1).atZone(ZoneId.systemDefault());
      Instant truncated = zdt.truncatedTo(ChronoUnit.MINUTES).toInstant();
      long expected = truncated.getEpochSecond() * 1_000_000_000L + truncated.getNano();
      assertEquals(expected, out.get(0).get("bucket"));
    }

    @Test
    void truncatesToHourBoundary() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample | select(truncate(startTime, \"hour\") as bucket)");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());

      ZonedDateTime zdt = Instant.ofEpochSecond(0, T1).atZone(ZoneId.systemDefault());
      Instant truncated = zdt.truncatedTo(ChronoUnit.HOURS).toInstant();
      long expected = truncated.getEpochSecond() * 1_000_000_000L + truncated.getNano();
      assertEquals(expected, out.get(0).get("bucket"));
    }

    @Test
    void chainsWithAsDateTime() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample"
                  + " | select(asDateTime(truncate(startTime, \"minute\"), \"HH:mm\") as bucket)");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());
      String bucket = (String) out.get(0).get("bucket");
      assertNotNull(bucket);
      assertTrue(bucket.matches("\\d{2}:\\d{2}"), "Expected HH:mm format, got: " + bucket);
    }
  }

  @Nested
  class FormatDurationInSelect {
    @Test
    void formatsNanoseconds() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q =
          JfrPathParser.parse(
              "events/jdk.ExecutionSample | select(formatDuration(duration) as dur)");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());
      assertEquals("500.00us", out.get(0).get("dur")); // 500_000 ns = 500us
      assertEquals("1.50s", out.get(1).get("dur")); // 1_500_000_000 ns = 1.5s
    }
  }

  // =========================================================================
  // Pipeline operators
  // =========================================================================

  @Nested
  class AsDateTimePipeline {
    @Test
    void formatsStartTimeByDefault() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q = JfrPathParser.parse("events/jdk.ExecutionSample | asDateTime(startTime)");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());
      String expected =
          DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
              Instant.ofEpochSecond(0, T1).atZone(ZoneId.systemDefault()));
      assertEquals(expected, out.get(0).get("value"));
    }
  }

  @Nested
  class FormatDurationPipeline {
    @Test
    void formatsDurationField() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q = JfrPathParser.parse("events/jdk.ExecutionSample/duration | formatDuration()");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());
      assertEquals("500.00us", out.get(0).get("value")); // 500_000 ns
      assertEquals("1.50s", out.get(1).get("value")); // 1_500_000_000 ns
    }

    @Test
    void formatsExplicitPathArg() throws Exception {
      var eval = new JfrPathEvaluator(twoEvents);
      var q = JfrPathParser.parse("events/jdk.ExecutionSample | formatDuration(duration)");
      List<Map<String, Object>> out = eval.evaluate(session, q);

      assertEquals(2, out.size());
      assertEquals("500.00us", out.get(0).get("value"));
      assertEquals("1.50s", out.get(1).get("value"));
    }
  }
}

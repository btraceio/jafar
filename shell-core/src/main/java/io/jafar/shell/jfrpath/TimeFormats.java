package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Duration, epoch and date-time formatting helpers shared by operators, filters and expressions.
 */
final class TimeFormats {
  static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

  static String formatDuration(long nanos) {
    if (nanos < 1_000) {
      return nanos + "ns";
    } else if (nanos < 1_000_000) {
      return String.format(Locale.ROOT, "%.2fus", nanos / 1_000.0);
    } else if (nanos < 1_000_000_000) {
      return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0);
    } else if (nanos < 60_000_000_000L) {
      return String.format(Locale.ROOT, "%.2fs", nanos / 1_000_000_000.0);
    } else if (nanos < 3_600_000_000_000L) {
      long totalSecs = nanos / 1_000_000_000L;
      long mins = totalSecs / 60;
      long secs = totalSecs % 60;
      return String.format(Locale.ROOT, "%dm %ds", mins, secs);
    } else {
      long totalSecs = nanos / 1_000_000_000L;
      long hours = totalSecs / 3600;
      long mins = (totalSecs % 3600) / 60;
      long secs = totalSecs % 60;
      return String.format(Locale.ROOT, "%dh %dm %ds", hours, mins, secs);
    }
  }

  static DateTimeFormatter getFormatter(String format) {
    if (format == null || format.isEmpty()) {
      return DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    }
    // A user's pattern: Locale.US keeps what an en-US machine always printed ("Tuesday, 13 August
    // 2024"; Locale.ROOT abbreviates MMMM/EEEE and changes era, period and zone text), and the
    // language no longer depends on the machine (issue #224). The zone still does (SYSTEM_ZONE).
    return DateTimeFormatter.ofPattern(format, Locale.US);
  }

  /**
   * The epoch nanoseconds a time argument of {@code before()}, {@code after()} or {@code between()}
   * stands for: a number is taken as is, anything else is parsed as a date-time.
   *
   * <p>One definition for the filter that tests each event ({@link MatchEngine}) and for the bounds
   * that let the parser skip chunks ({@link StartTimeBounds}); if they disagreed, a skipped chunk
   * could hold an event the filter would have accepted.
   */
  static long timeArgument(Object arg) {
    return arg instanceof Number n ? n.longValue() : parseEpochNanos(String.valueOf(arg));
  }

  /**
   * The day {@code on()} means: epoch nanoseconds of its first instant, and of the first instant of
   * the next day, in the system zone. A time is on the day when it is at or after the first and
   * before the second.
   */
  static long[] dayBounds(String dateStr) {
    LocalDate date;
    try {
      date = LocalDate.parse(dateStr);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("on() requires date in yyyy-MM-dd format: " + dateStr);
    }
    return new long[] {
      date.atStartOfDay(SYSTEM_ZONE).toInstant().getEpochSecond() * 1_000_000_000L,
      date.plusDays(1).atStartOfDay(SYSTEM_ZONE).toInstant().getEpochSecond() * 1_000_000_000L
    };
  }

  static long parseEpochNanos(String s) {
    // ISO instant with timezone offset, e.g. "2024-08-13T16:24:00Z"
    try {
      Instant i = Instant.parse(s);
      return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    } catch (DateTimeParseException ignored) {
    }
    // Local date-time without timezone, e.g. "2024-08-13T16:24:00"
    try {
      Instant i = LocalDateTime.parse(s).atZone(SYSTEM_ZONE).toInstant();
      return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    } catch (DateTimeParseException ignored) {
    }
    // Date only, e.g. "2024-08-13" — treated as start of day in local zone
    try {
      Instant i = LocalDate.parse(s).atStartOfDay(SYSTEM_ZONE).toInstant();
      return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    } catch (DateTimeParseException ignored) {
    }
    throw new IllegalArgumentException("Cannot parse datetime: " + s);
  }
}

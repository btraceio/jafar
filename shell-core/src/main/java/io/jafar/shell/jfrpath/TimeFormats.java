package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Duration, epoch and date-time formatting helpers shared by operators, filters and expressions.
 */
final class TimeFormats {
  static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

  static String formatDuration(long nanos) {
    if (nanos < 1_000) {
      return nanos + "ns";
    } else if (nanos < 1_000_000) {
      return String.format("%.2fus", nanos / 1_000.0);
    } else if (nanos < 1_000_000_000) {
      return String.format("%.2fms", nanos / 1_000_000.0);
    } else if (nanos < 60_000_000_000L) {
      return String.format("%.2fs", nanos / 1_000_000_000.0);
    } else if (nanos < 3_600_000_000_000L) {
      long totalSecs = nanos / 1_000_000_000L;
      long mins = totalSecs / 60;
      long secs = totalSecs % 60;
      return String.format("%dm %ds", mins, secs);
    } else {
      long totalSecs = nanos / 1_000_000_000L;
      long hours = totalSecs / 3600;
      long mins = (totalSecs % 3600) / 60;
      long secs = totalSecs % 60;
      return String.format("%dh %dm %ds", hours, mins, secs);
    }
  }

  static DateTimeFormatter getFormatter(String format) {
    if (format == null || format.isEmpty()) {
      return DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    }
    return DateTimeFormatter.ofPattern(format);
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

package io.jafar.shell;

import com.google.gson.GsonBuilder;
import io.jafar.shell.core.compare.CompareResult;
import io.jafar.shell.core.compare.RecordingProfile;
import io.jafar.shell.core.findings.Finding;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders a {@link CompareResult} for {@code jfr-shell compare}: JSON, or a short text report. */
final class CompareOutput {

  /** Longest method name printed in a table before it is shortened from the left. */
  private static final int METHOD_WIDTH = 60;

  private CompareOutput() {}

  /**
   * The result map as pretty-printed JSON. Nulls are kept ({@code deltaPct: null} means "absent in
   * baseline"), and {@code <init>} stays {@code <init>} rather than being HTML-escaped.
   */
  static String json(Map<String, Object> result) {
    return new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create()
            .toJson(result)
        + "\n";
  }

  /** A short human-readable report. */
  static String table(CompareResult result) {
    StringBuilder out = new StringBuilder();
    side(out, "Baseline ", result.baseline());
    side(out, "Candidate", result.candidate());
    out.append('\n');
    for (String note : result.comparability()) {
      out.append("Note: ").append(note).append('\n');
    }

    out.append("\nEvent rates (per second)\n");
    out.append(
        String.format(
            Locale.ROOT, "  %-32s %12s %12s %9s%n", "", "baseline", "candidate", "delta"));
    for (Map<String, Object> metric : result.metrics()) {
      Object deltaPct = metric.get("deltaPct");
      out.append(
          String.format(
              Locale.ROOT,
              "  %-32s %12.3f %12.3f %9s%n",
              metric.get("name"),
              ((Number) metric.get("baselineRate")).doubleValue(),
              ((Number) metric.get("candidateRate")).doubleValue(),
              deltaPct instanceof Number n
                  ? String.format(Locale.ROOT, "%+.1f%%", n.doubleValue())
                  : "n/a"));
    }

    frames(out, "Self time (leaf frame share of samples)", result.frames(), "SelfPct");
    if (result.inclusiveFrames() != null) {
      frames(
          out,
          "Inclusive time (share of samples with the frame on the stack)",
          result.inclusiveFrames(),
          "TotalPct");
    }

    out.append("\nFindings\n");
    for (Finding finding : result.findings()) {
      out.append("  [")
          .append(finding.severity())
          .append("] ")
          .append(finding.title())
          .append('\n');
    }
    return out.toString();
  }

  private static void side(StringBuilder out, String label, RecordingProfile profile) {
    out.append(
        String.format(
            Locale.ROOT,
            "%s %s: %s, %,d samples over %.1fs, %,d events%n",
            label,
            profile.recordingPath(),
            profile.eventType() == null ? "no execution samples" : profile.eventType(),
            profile.sampleCount(),
            profile.durationSeconds(),
            profile.totalEvents()));
  }

  private static void frames(
      StringBuilder out, String title, List<Map<String, Object>> rows, String shareKey) {
    out.append('\n').append(title).append('\n');
    if (rows.isEmpty()) {
      out.append("  no frame moved by the noise floor or more\n");
      return;
    }
    out.append(
        String.format(
            Locale.ROOT,
            "  %-" + METHOD_WIDTH + "s %9s %9s %8s  %s%n",
            "method",
            "baseline",
            "candidate",
            "delta",
            "direction"));
    for (Map<String, Object> row : rows) {
      out.append(
          String.format(
              Locale.ROOT,
              "  %-" + METHOD_WIDTH + "s %8.2f%% %8.2f%% %+8.2f  %s%n",
              shorten(String.valueOf(row.get("method"))),
              ((Number) row.get("baseline" + shareKey)).doubleValue(),
              ((Number) row.get("candidate" + shareKey)).doubleValue(),
              ((Number) row.get("deltaPct")).doubleValue(),
              row.get("direction")));
    }
  }

  private static String shorten(String method) {
    return method.length() <= METHOD_WIDTH
        ? method
        : "..." + method.substring(method.length() - METHOD_WIDTH + 3);
  }
}

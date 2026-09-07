package io.jafar.mcp.findings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Helpers for building, merging and serialising {@link Finding} lists. */
public final class Findings {

  private Findings() {}

  /**
   * Builds a stable finding id from a category and a subject.
   *
   * <p>The subject is normalised — lower-cased, with runs of non-alphanumeric characters collapsed
   * to a single {@code -} — so that the same condition reported by two tools, or by the same tool
   * across two runs, yields the same id and can be de-duplicated.
   */
  public static String id(String category, String subject) {
    String normalisedSubject =
        subject == null || subject.isBlank()
            ? "general"
            : subject
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    return category.toLowerCase(Locale.ROOT) + ":" + normalisedSubject;
  }

  /**
   * Merges several finding lists into one, de-duplicating by {@link Finding#id()} and keeping the
   * most severe of any duplicates. The result is ordered by severity, most severe first, and is
   * stable within a severity: findings keep the order in which they were first seen.
   */
  @SafeVarargs
  public static List<Finding> merge(List<Finding>... lists) {
    Map<String, Finding> byId = new LinkedHashMap<>();
    for (List<Finding> list : lists) {
      if (list == null) {
        continue;
      }
      for (Finding finding : list) {
        if (finding == null) {
          continue;
        }
        byId.merge(finding.id(), finding, Findings::moreSevere);
      }
    }
    List<Finding> merged = new ArrayList<>(byId.values());
    merged.sort(Comparator.comparingInt(f -> f.severity().ordinal()));
    return merged;
  }

  private static Finding moreSevere(Finding existing, Finding candidate) {
    // A later finding wins only when it is strictly more severe, so the first description of a
    // condition survives when both carry the same weight.
    return candidate.severity().ordinal() < existing.severity().ordinal() ? candidate : existing;
  }

  /** Serialises findings for an MCP response. */
  public static List<Map<String, Object>> toMaps(List<Finding> findings) {
    List<Map<String, Object>> maps = new ArrayList<>(findings.size());
    for (Finding finding : findings) {
      maps.add(finding.toMap());
    }
    return maps;
  }

  /** Counts findings per severity, for a compact response header. */
  public static Map<String, Integer> countBySeverity(List<Finding> findings) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Finding.Severity severity : Finding.Severity.values()) {
      counts.put(severity.name(), 0);
    }
    for (Finding finding : findings) {
      counts.merge(finding.severity().name(), 1, Integer::sum);
    }
    return counts;
  }
}

package io.jafar.hdump.shell;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.shell.hdumppath.SubgraphFingerprinter;
import io.jafar.hdump.shell.leaks.LeakDetector;
import io.jafar.hdump.shell.leaks.LeakDetectorRegistry;
import io.jafar.hdump.util.ClassNameUtil;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates a ranked heap health report for a heap session. Always triggers all analyses, including
 * expensive computations (retained sizes, duplicates, leak detectors).
 */
public final class HeapReportGenerator {

  private static final long CRITICAL_RETAINED_BYTES = 100L * 1024 * 1024;
  private static final long WARNING_RETAINED_BYTES = 10L * 1024 * 1024;
  private static final long WARNING_DUPLICATE_WASTE_BYTES = 1L * 1024 * 1024;

  /**
   * Fingerprint depth used when computing duplicates for the report. Depth 1 is cheaper than the
   * interactive default (3) while still catching the most common structural duplicates.
   */
  private static final int DUPLICATE_DEPTH = 1;

  /** Report finding severity. */
  public enum Severity {
    CRITICAL,
    WARNING,
    INFO
  }

  /**
   * A single report finding.
   *
   * @param severity finding severity
   * @param category short category label (e.g. "leaks", "duplicates")
   * @param title one-line summary
   * @param description optional detail line
   * @param retainedSize associated retained size in bytes (-1 if not applicable)
   * @param affectedObjects number of affected objects (-1 if not applicable)
   * @param action suggested next step
   * @param query HdumpPath query to reproduce or drill down
   */
  public record Finding(
      Severity severity,
      String category,
      String title,
      String description,
      long retainedSize,
      int affectedObjects,
      String action,
      String query) {}

  private HeapReportGenerator() {}

  /**
   * Generates a list of findings for the given session.
   *
   * <p>Always triggers all analyses, including expensive computations (approximate retained sizes,
   * duplicate subgraph fingerprinting, and all built-in leak detectors). Progress is reported via
   * the existing {@link HeapSession} machinery.
   *
   * @param session the active heap session
   * @param focus optional set of focus areas to restrict analysis (null or empty = all)
   * @return ranked list of findings, CRITICAL first then WARNING then INFO
   */
  public static List<Finding> generate(HeapSession session, Set<String> focus) {
    HeapDump dump = session.getHeapDump();
    List<Finding> findings = new ArrayList<>();

    boolean runAll = focus == null || focus.isEmpty();

    // --- Cheap contributors (always run) ---
    findings.addAll(analyzeHistogram(dump));
    findings.addAll(analyzeGcRoots(dump));

    // --- Retained sizes: required for leaks + waste ---
    if (runAll || focus.contains("leaks") || focus.contains("waste")) {
      session.computeApproximateRetainedSizes();
    }

    // --- Leak detectors (skip duplicate-strings — O(unique-strings) memory risk) ---
    if (runAll || focus.contains("leaks")) {
      System.err.println("Running leak detectors...");
      findings.addAll(analyzeLeaks(dump));
      System.err.println("Leak detection complete.");
    }

    // --- Waste analysis ---
    if (runAll || focus.contains("waste")) {
      System.err.println("Analyzing collection waste...");
      findings.addAll(analyzeWaste(dump));
    }

    // --- Duplicate subgraphs ---
    if (runAll || focus.contains("duplicates")) {
      findings.addAll(analyzeDuplicates(session));
    }

    // Sort: CRITICAL first, then WARNING, then INFO; within same severity keep insertion order
    findings.sort(Comparator.comparingInt(f -> f.severity().ordinal()));
    return findings;
  }

  /**
   * Formats findings as a plain-text report.
   *
   * @param findings the findings to format
   * @param session the session these findings came from
   * @return formatted report string
   */
  public static String formatText(List<Finding> findings, HeapSession session) {
    HeapDump dump = session.getHeapDump();
    StringBuilder sb = new StringBuilder();

    sb.append("=== Heap Health Report ===\n");
    Path path = dump.getPath();
    String fileName = path != null ? path.getFileName().toString() : "unknown";
    sb.append(
        String.format(
            "Heap: %s (%s, %,d objects, %,d classes)%n",
            fileName,
            formatSize(totalHeapSize(dump)),
            dump.getObjectCount(),
            dump.getClassCount()));
    sb.append("\n");

    for (Severity sev : Severity.values()) {
      List<Finding> group = findings.stream().filter(f -> f.severity() == sev).toList();
      if (group.isEmpty()) continue;
      sb.append("--- ").append(sev.name()).append(" ---\n\n");
      char code = sev == Severity.CRITICAL ? 'C' : sev == Severity.WARNING ? 'W' : 'I';
      int idx = 1;
      for (Finding f : group) {
        sb.append(String.format("[%c%d] %s%n", code, idx++, f.title()));
        if (f.description() != null && !f.description().isBlank()) {
          sb.append("     ").append(f.description()).append("\n");
        }
        if (f.action() != null && !f.action().isBlank()) {
          sb.append("     Action: ").append(f.action()).append("\n");
        }
        if (f.query() != null && !f.query().isBlank()) {
          sb.append("     Query: ").append(f.query()).append("\n");
        }
        sb.append("\n");
      }
    }
    return sb.toString();
  }

  /**
   * Formats findings as a Markdown report.
   *
   * @param findings the findings to format
   * @param session the session these findings came from
   * @return formatted Markdown string
   */
  public static String formatMarkdown(List<Finding> findings, HeapSession session) {
    HeapDump dump = session.getHeapDump();
    StringBuilder sb = new StringBuilder();

    sb.append("# Heap Health Report\n\n");
    Path path = dump.getPath();
    String fileName = path != null ? path.getFileName().toString() : "unknown";
    sb.append(
        String.format(
            "**Heap:** %s | **Size:** %s | **Objects:** %,d | **Classes:** %,d%n%n",
            fileName,
            formatSize(totalHeapSize(dump)),
            dump.getObjectCount(),
            dump.getClassCount()));

    for (Severity sev : Severity.values()) {
      List<Finding> group = findings.stream().filter(f -> f.severity() == sev).toList();
      if (group.isEmpty()) continue;
      sb.append("## ").append(sev.name()).append("\n\n");
      char code = sev == Severity.CRITICAL ? 'C' : sev == Severity.WARNING ? 'W' : 'I';
      int idx = 1;
      for (Finding f : group) {
        sb.append(String.format("### [%c%d] %s%n%n", code, idx++, f.title()));
        if (f.description() != null && !f.description().isBlank()) {
          sb.append(f.description()).append("\n\n");
        }
        if (f.action() != null && !f.action().isBlank()) {
          sb.append("**Action:** ").append(f.action()).append("\n\n");
        }
        if (f.query() != null && !f.query().isBlank()) {
          sb.append("```\n").append(f.query()).append("\n```\n\n");
        }
      }
    }
    return sb.toString();
  }

  // ---- Contributors ----

  private static List<Finding> analyzeHistogram(HeapDump dump) {
    List<Finding> findings = new ArrayList<>();

    long totalShallow = totalHeapSize(dump);
    findings.add(
        new Finding(
            Severity.INFO,
            "summary",
            String.format(
                "Heap summary: %,d objects, %,d classes, %s total shallow",
                dump.getObjectCount(), dump.getClassCount(), formatSize(totalShallow)),
            null,
            totalShallow,
            dump.getObjectCount(),
            null,
            null));

    // Find top class by instance count
    HeapClass topClass =
        dump.getClasses().stream()
            .filter(c -> c.getName() != null && !c.getName().startsWith("["))
            .max(Comparator.comparingLong(HeapClass::getInstanceCount))
            .orElse(null);

    if (topClass != null) {
      long shallowTotal = (long) topClass.getInstanceCount() * topClass.getInstanceSize();
      findings.add(
          new Finding(
              Severity.INFO,
              "histogram",
              String.format(
                  "Top class by instance count: %s (%,d instances, %s shallow)",
                  ClassNameUtil.toHumanReadable(topClass.getName()),
                  topClass.getInstanceCount(),
                  formatSize(shallowTotal)),
              null,
              shallowTotal,
              topClass.getInstanceCount(),
              "Inspect with: objects/" + ClassNameUtil.toHumanReadable(topClass.getName()),
              "objects/"
                  + ClassNameUtil.toHumanReadable(topClass.getName())
                  + " | groupBy(class) | stats(shallow)"));
    }

    return findings;
  }

  private static List<Finding> analyzeGcRoots(HeapDump dump) {
    List<Finding> findings = new ArrayList<>();

    Map<String, Integer> byType = new LinkedHashMap<>();
    for (GcRoot root : dump.getGcRoots()) {
      String type = root.getType() != null ? root.getType().name() : "UNKNOWN";
      byType.merge(type, 1, Integer::sum);
    }

    if (byType.isEmpty()) {
      return findings;
    }

    StringBuilder desc = new StringBuilder();
    byType.forEach((t, c) -> desc.append(c).append(" ").append(t).append(", "));
    if (desc.length() > 2) desc.setLength(desc.length() - 2);

    findings.add(
        new Finding(
            Severity.INFO,
            "gcroots",
            String.format("GC roots: %s", desc),
            null,
            -1,
            dump.getGcRootCount(),
            null,
            "gcroots | groupBy(type)"));

    return findings;
  }

  private static List<Finding> analyzeLeaks(HeapDump dump) {
    List<Finding> findings = new ArrayList<>();

    for (LeakDetector detector : LeakDetectorRegistry.getAllDetectors()) {
      List<Map<String, Object>> results = detector.detect(dump, null, null);
      if (results.isEmpty()) continue;

      long totalRetained = 0;
      for (Map<String, Object> row : results) {
        Object r = row.get("retained");
        if (r == null) r = row.get("retainedSize");
        if (r instanceof Number n) totalRetained += n.longValue();
      }

      Severity sev =
          totalRetained > CRITICAL_RETAINED_BYTES
              ? Severity.CRITICAL
              : totalRetained > WARNING_RETAINED_BYTES ? Severity.WARNING : Severity.INFO;

      String query = "checkLeaks(detector=\"" + detector.getName() + "\")";
      findings.add(
          new Finding(
              sev,
              "leaks/" + detector.getName(),
              String.format(
                  "%s: %,d suspect(s) found%s",
                  detector.getName(),
                  results.size(),
                  totalRetained > 0 ? ", ~" + formatSize(totalRetained) + " retained" : ""),
              detector.getDescription(),
              totalRetained,
              results.size(),
              "Inspect with: " + query,
              query));
    }

    return findings;
  }

  private static List<Finding> analyzeDuplicates(HeapSession session) {
    // Use cached result if the user already ran 'duplicates' interactively (any depth).
    // Otherwise trigger a fresh computation at the report depth.
    Map<Integer, SubgraphFingerprinter.Result> cached = session.getAllCachedDuplicates();
    SubgraphFingerprinter.Result result;
    if (!cached.isEmpty()) {
      result =
          cached.values().stream()
              .max(
                  Comparator.comparingInt(
                      r -> r.rows().isEmpty() ? 0 : (int) r.rows().get(0).getOrDefault("depth", 0)))
              .orElse(null);
    } else {
      System.err.println("Computing duplicate subgraph fingerprints...");
      result = session.getOrComputeDuplicates(DUPLICATE_DEPTH);
    }

    if (result == null || result.rows().isEmpty()) return List.of();

    long totalWaste = 0;
    int groups = result.rows().size();
    Map<String, Object> mostWasteful = null;
    long maxWaste = 0;

    for (Map<String, Object> row : result.rows()) {
      Object wb = row.get("wastedBytes");
      long waste = wb instanceof Number ? ((Number) wb).longValue() : 0;
      totalWaste += waste;
      if (waste > maxWaste) {
        maxWaste = waste;
        mostWasteful = row;
      }
    }

    Severity sev = totalWaste > WARNING_DUPLICATE_WASTE_BYTES ? Severity.WARNING : Severity.INFO;

    String desc = null;
    if (mostWasteful != null) {
      Object rc = mostWasteful.get("rootClass");
      Object copies = mostWasteful.get("copies");
      if (rc != null) {
        desc =
            String.format(
                "Most wasteful: %s (%s copies)", rc, copies != null ? copies.toString() : "?");
      }
    }

    return List.of(
        new Finding(
            sev,
            "duplicates",
            String.format(
                "Duplicate subgraphs: %,d groups, ~%s reclaimable", groups, formatSize(totalWaste)),
            desc,
            totalWaste,
            groups,
            "Run: duplicates | sortBy(wastedBytes desc)",
            "duplicates | sortBy(wastedBytes desc)"));
  }

  private static List<Finding> analyzeWaste(HeapDump dump) {
    long wastedEstimate = 0;
    int wastedCollections = 0;
    for (HeapClass cls : dump.getClasses()) {
      String name = cls.getName();
      if (name == null) continue;
      if (name.equals("java/util/HashMap")
          || name.equals("java/util/LinkedHashMap")
          || name.equals("java/util/ArrayList")
          || name.equals("java/util/concurrent/ConcurrentHashMap")) {
        // Rough estimate: on average ~50% of slots are unused
        wastedEstimate += (long) cls.getInstanceCount() * dump.getIdSize() * 4L;
        wastedCollections += cls.getInstanceCount();
      }
    }

    if (wastedCollections == 0) return List.of();

    Severity sev = wastedEstimate > WARNING_RETAINED_BYTES ? Severity.WARNING : Severity.INFO;
    return List.of(
        new Finding(
            sev,
            "waste",
            String.format(
                "Collection capacity waste: %,d collection instances, estimated ~%s overhead",
                wastedCollections, formatSize(wastedEstimate)),
            "Run waste() for exact per-object figures",
            wastedEstimate,
            wastedCollections,
            "Inspect with: objects/instanceof/java.util.Map | waste() | sortBy(wastedBytes desc)",
            "objects/instanceof/java.util.Map | waste() | filter(wastedBytes > 1MB)"
                + " | top(10, wastedBytes)"));
  }

  // ---- Utilities ----

  /**
   * Returns the total shallow heap size. Falls back to summing {@code instanceCount * instanceSize}
   * from the class histogram when the indexed parser leaves {@code getTotalHeapSize()} at zero
   * (large heap dumps use lazy loading and don't accumulate the total during parsing).
   */
  private static long totalHeapSize(HeapDump dump) {
    long size = dump.getTotalHeapSize();
    if (size > 0) return size;
    return dump.getClasses().stream()
        .mapToLong(c -> (long) c.getInstanceCount() * c.getInstanceSize())
        .sum();
  }

  private static String formatSize(long bytes) {
    if (bytes < 0) return "?";
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }
}

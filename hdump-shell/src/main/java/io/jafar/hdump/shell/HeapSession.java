package io.jafar.hdump.shell;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.shell.leaks.LeakDetector;
import io.jafar.hdump.shell.leaks.LeakDetectorRegistry;
import io.jafar.shell.core.InteractiveMenu;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.TableFormatter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session implementation for heap dump analysis. Wraps a parsed HeapDump and provides Session
 * interface for shell integration.
 */
public final class HeapSession implements Session {

  private static final Logger LOG = LoggerFactory.getLogger(HeapSession.class);

  private final Path path;
  private final HeapDump dump;
  private final ParserOptions options;
  private volatile boolean closed = false;

  private HeapSession(Path path, HeapDump dump, ParserOptions options) {
    this.path = path;
    this.dump = dump;
    this.options = options;
  }

  /**
   * Opens a heap dump file.
   *
   * @param path path to the HPROF file
   * @return a new heap session
   * @throws IOException if the file cannot be read
   */
  public static HeapSession open(Path path) throws IOException {
    return open(path, ParserOptions.DEFAULT); // AUTO mode: >2GB uses indexed, smaller uses in-memory
  }

  /**
   * Opens a heap dump file with custom options.
   *
   * @param path path to the HPROF file
   * @param options parser options
   * @return a new heap session
   * @throws IOException if the file cannot be read
   */
  public static HeapSession open(Path path, ParserOptions options) throws IOException {
    LOG.info("Opening heap dump: {}", path);

    // Create delayed progress reporter (starts after 250ms)
    DelayedProgressReporter progressReporter = new DelayedProgressReporter(250);

    try {
      HeapDump dump = HeapDumpParser.parse(path, options, progressReporter);
      progressReporter.complete();

      LOG.info(
          "Loaded {} classes, {} objects, {} GC roots",
          dump.getClassCount(),
          dump.getObjectCount(),
          dump.getGcRootCount());
      return new HeapSession(path, dump, options);
    } catch (Exception e) {
      progressReporter.cancel();
      throw e;
    }
  }

  /** Progress reporter that starts displaying after a delay to avoid flicker for fast operations. */
  private static class DelayedProgressReporter implements HeapDumpParser.ProgressCallback {
    private final long delayMs;
    private final long startTime;
    private boolean started = false;
    private String lastMessage = "";

    DelayedProgressReporter(long delayMs) {
      this.delayMs = delayMs;
      this.startTime = System.currentTimeMillis();
    }

    @Override
    public synchronized void onProgress(double progress, String message) {
      long elapsed = System.currentTimeMillis() - startTime;

      if (!started && elapsed < delayMs) {
        return; // Don't show progress yet
      }

      if (!started) {
        started = true;
        // Don't print anything initially - first progress update will show message
      }

      // Update progress on same line
      int percentage = (int) (progress * 100);
      if (!message.equals(lastMessage) || percentage % 5 == 0) {
        String statusMessage = message != null && !message.isEmpty() ? message : "Parsing";
        System.err.print("\r" + statusMessage + "... " + percentage + "%");
        lastMessage = message;
      }
    }

    synchronized void complete() {
      if (started) {
        System.err.println("\r" + (lastMessage != null ? lastMessage : "Complete") + "... 100%");
      }
    }

    synchronized void cancel() {
      if (started) {
        System.err.println("\rFailed                                    ");
      }
    }
  }

  /** Returns the underlying HeapDump for direct access. */
  public HeapDump getHeapDump() {
    return dump;
  }

  @Override
  public String getType() {
    return "hdump";
  }

  @Override
  public Path getFilePath() {
    return path;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public Set<String> getAvailableTypes() {
    return dump.getClasses().stream()
        .map(c -> c.getName())
        .filter(name -> name != null && !name.startsWith("[")) // Exclude array classes
        .map(name -> name.replace('/', '.')) // Convert internal format to Java format
        .collect(Collectors.toSet());
  }

  /**
   * Computes full dominator tree with progress feedback.
   *
   * @param progressCallback callback for progress updates
   */
  public void computeFullDominatorTree(
      io.jafar.hdump.impl.DominatorTreeComputer.ProgressCallback progressCallback) {
    if (dump instanceof io.jafar.hdump.impl.HeapDumpImpl impl) {
      impl.computeFullDominatorTree(progressCallback);
    }
  }

  /**
   * Checks if full dominator tree has been computed.
   */
  public boolean hasFullDominatorTree() {
    if (dump instanceof io.jafar.hdump.impl.HeapDumpImpl impl) {
      return impl.hasFullDominatorTree();
    }
    return false;
  }

  /**
   * Computes approximate retained sizes with progress display (no prompt needed).
   * This is faster than full dominator tree but less accurate.
   */
  public void computeApproximateRetainedSizes() {
    if (dump instanceof io.jafar.hdump.impl.HeapDumpImpl impl) {
      if (impl.hasDominators()) {
        return; // Already computed
      }

      System.err.println();
      System.err.println("Computing approximate retained sizes...");
      System.err.println(
          String.format(
              "Scanning %,d objects from heap dump (may take a while for large dumps)",
              dump.getObjectCount()));
      System.err.println();

      final String[] spinner = {"|", "/", "-", "\\"};
      final int[] spinnerIndex = {0};
      final long[] lastUpdate = {System.currentTimeMillis()};

      impl.computeDominators(
          (progress, message) -> {
            long now = System.currentTimeMillis();
            // Update every 200ms to avoid flickering
            if (now - lastUpdate[0] >= 200) {
              String progressBar = createProgressBar(progress, 30);
              System.err.print(
                  String.format(
                      "\r%s [%s] %.0f%% - %s",
                      spinner[spinnerIndex[0] % spinner.length],
                      progressBar,
                      progress * 100,
                      message));
              System.err.flush();
              spinnerIndex[0]++;
              lastUpdate[0] = now;
            }
          });

      System.err.println();
      System.err.println("Approximate retained size computation complete!");
      System.err.println();
    }
  }

  /**
   * Computes full dominator tree with automatic progress display.
   * No user prompt - computation starts immediately when called.
   *
   * @return true if computation completed successfully
   */
  public boolean promptAndComputeDominatorTree() {
    if (hasFullDominatorTree()) {
      return true; // Already computed
    }

    // Inform user about computation
    System.err.println();
    System.err.println("Computing full dominator tree for dominators() operator...");
    System.err.println(
        String.format(
            "Analyzing %,d objects (estimated time: 15-30 seconds for 10M objects)",
            dump.getObjectCount()));
    System.err.println();

    final String[] spinner = {"|", "/", "-", "\\"};
    final int[] spinnerIndex = {0};
    final long[] lastUpdate = {System.currentTimeMillis()};

    computeFullDominatorTree(
        (progress, message) -> {
          long now = System.currentTimeMillis();
          // Update every 200ms to avoid flickering
          if (now - lastUpdate[0] >= 200) {
            String progressBar = createProgressBar(progress, 30);
            System.err.print(
                String.format(
                    "\r%s [%s] %.0f%% - %s",
                    spinner[spinnerIndex[0] % spinner.length], progressBar, progress * 100, message));
            System.err.flush();
            spinnerIndex[0]++;
            lastUpdate[0] = now;
          }
        });

    // Clear the progress line and print completion message
    System.err.print("\r" + " ".repeat(80) + "\r");
    System.err.println("Dominator tree computation complete!");
    System.err.println();
    return true;
  }

  private static String createProgressBar(double progress, int width) {
    int filled = (int) (progress * width);
    StringBuilder bar = new StringBuilder();
    for (int i = 0; i < width; i++) {
      if (i < filled) {
        bar.append("=");
      } else if (i == filled) {
        bar.append(">");
      } else {
        bar.append(" ");
      }
    }
    return bar.toString();
  }

  @Override
  public Map<String, Object> getStatistics() {
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("path", path.toString());
    stats.put("format", "HPROF " + dump.getFormatVersion());
    stats.put("timestamp", Instant.ofEpochMilli(dump.getTimestamp()).toString());
    stats.put("idSize", dump.getIdSize() + " bytes");
    stats.put("classes", dump.getClassCount());
    stats.put("objects", dump.getObjectCount());
    stats.put("gcRoots", dump.getGcRootCount());
    stats.put("totalHeapSize", formatSize(dump.getTotalHeapSize()));
    stats.put("dominatorsComputed", dump.hasDominators());
    return stats;
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      dump.close();
      LOG.debug("Closed heap session: {}", path);
    }
  }

  /**
   * Run the interactive leak detection wizard.
   *
   * @param menu Interactive menu component for user input
   */
  public void runLeakDetectionWizard(InteractiveMenu menu) throws IOException {
    PrintWriter out = new PrintWriter(System.out, true);

    // Step 1: Detector selection
    out.println("\n=== Leak Detection Wizard ===\n");

    List<InteractiveMenu.MenuItem> detectorItems = new ArrayList<>();
    for (LeakDetector detector : LeakDetectorRegistry.getAllDetectors()) {
      detectorItems.add(
          new InteractiveMenu.MenuItem(
              detector.getName(), detector.getName(), detector.getDescription()));
    }

    List<String> selectedIds =
        menu.showMultiSelect(
            "Select detectors to run (Space: toggle, Arrows: navigate, Enter: confirm)",
            detectorItems);

    if (selectedIds.isEmpty()) {
      out.println("No detectors selected. Wizard cancelled.");
      return;
    }

    // Step 2: Configure each selected detector
    Map<String, DetectorConfig> configs = new LinkedHashMap<>();

    for (String detectorId : selectedIds) {
      LeakDetector detector = LeakDetectorRegistry.getDetector(detectorId);
      out.println("\nConfiguring detector: " + detector.getName());

      DetectorConfig config = configureDetector(menu, detector);
      configs.put(detectorId, config);
    }

    // Step 2.5: Ensure retained sizes are computed (required for leak detection)
    computeApproximateRetainedSizes();

    // Step 3: Run all detectors
    out.println("\nRunning leak detection...");
    Map<String, List<Map<String, Object>>> allResults = new LinkedHashMap<>();

    for (String detectorId : selectedIds) {
      LeakDetector detector = LeakDetectorRegistry.getDetector(detectorId);
      DetectorConfig config = configs.get(detectorId);

      out.print("  [⋯] " + detector.getName());
      out.flush();

      List<Map<String, Object>> results = detector.detect(dump, config.threshold, config.minSize);

      out.print("\r  [✓] " + detector.getName() + " (found " + results.size() + " issue(s))\n");

      allResults.put(detectorId, results);
    }

    // Step 4: Display all results grouped by detector
    out.println();
    for (Map.Entry<String, List<Map<String, Object>>> entry : allResults.entrySet()) {
      displayDetectorResults(out, entry.getKey(), entry.getValue());
    }
  }

  private DetectorConfig configureDetector(InteractiveMenu menu, LeakDetector detector) {
    DetectorConfig config = new DetectorConfig();

    // Get default parameters
    String defaultsMsg = getDefaultParametersMessage(detector);
    System.out.println("  Default parameters: " + defaultsMsg);

    boolean useDefaults = menu.promptYesNo("  Use defaults?", true);

    if (useDefaults) {
      // Set actual default values
      config.threshold = getDefaultThreshold(detector);
      config.minSize = getDefaultMinSize(detector);
    } else {
      // Prompt for custom values
      config.threshold = promptThresholdIfNeeded(menu, detector);
      config.minSize = promptMinSizeIfNeeded(menu, detector);
    }

    return config;
  }

  private String getDefaultParametersMessage(LeakDetector detector) {
    return switch (detector.getName()) {
      case "duplicate-strings" -> "threshold=100 (min duplicate count)";
      case "growing-collections" -> "minSize=10485760 bytes (10.0 MB)";
      case "threadlocal-leak" -> "minSize=1048576 bytes (1.0 MB)";
      case "classloader-leak" -> "minSize=10485760 bytes (10.0 MB)";
      case "listener-leak" -> "threshold=50 (min instance count)";
      case "finalizer-queue" -> "threshold=100 (min queue size)";
      default -> "no parameters";
    };
  }

  private Integer getDefaultThreshold(LeakDetector detector) {
    return switch (detector.getName()) {
      case "duplicate-strings" -> 100;
      case "listener-leak" -> 50;
      case "finalizer-queue" -> 100;
      default -> null;
    };
  }

  private Integer getDefaultMinSize(LeakDetector detector) {
    return switch (detector.getName()) {
      case "growing-collections" -> 10 * 1024 * 1024; // 10 MB
      case "threadlocal-leak" -> 1024 * 1024; // 1 MB
      case "classloader-leak" -> 10 * 1024 * 1024; // 10 MB
      default -> null;
    };
  }

  private Integer promptThresholdIfNeeded(InteractiveMenu menu, LeakDetector detector) {
    return switch (detector.getName()) {
      case "duplicate-strings" -> menu.promptInt("  Enter threshold (min duplicate count)", 100);
      case "listener-leak" -> menu.promptInt("  Enter threshold (min instance count)", 50);
      case "finalizer-queue" -> menu.promptInt("  Enter threshold (min queue size)", 100);
      default -> null;
    };
  }

  private Integer promptMinSizeIfNeeded(InteractiveMenu menu, LeakDetector detector) {
    return switch (detector.getName()) {
      case "growing-collections" ->
          menu.promptInt("  Enter minSize in bytes", 10 * 1024 * 1024);
      case "threadlocal-leak" -> menu.promptInt("  Enter minSize in bytes", 1024 * 1024);
      case "classloader-leak" -> menu.promptInt("  Enter minSize in bytes", 10 * 1024 * 1024);
      default -> null;
    };
  }

  private void displayDetectorResults(PrintWriter out, String detectorId, List<Map<String, Object>> results) {

    if (results.isEmpty()) {
      out.println("=== " + detectorId + " Results ===");
      out.println("No issues found.\n");
      return;
    }

    out.println("=== " + detectorId + " Results ===");

    // Use TableFormatter to display results
    String table = TableFormatter.formatTable(results, results.size());
    out.println(table);
    out.println();
  }

  /** Configuration for a leak detector. */
  private static class DetectorConfig {
    Integer threshold;
    Integer minSize;
  }
}

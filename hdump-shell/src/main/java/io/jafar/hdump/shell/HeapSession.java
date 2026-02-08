package io.jafar.hdump.shell;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.shell.core.Session;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
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
}

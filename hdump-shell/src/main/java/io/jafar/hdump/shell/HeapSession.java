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
    return open(path, ParserOptions.DEFAULT);
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
    HeapDump dump = HeapDumpParser.parse(path, options);
    LOG.info(
        "Loaded {} classes, {} objects, {} GC roots",
        dump.getClassCount(),
        dump.getObjectCount(),
        dump.getGcRootCount());
    return new HeapSession(path, dump, options);
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

package io.jafar.hdump.api;

import io.jafar.hdump.impl.HeapDumpImpl;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Main entry point for parsing HPROF heap dump files.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * try (HeapDump dump = HeapDumpParser.parse(Path.of("heap.hprof"))) {
 *     // Find large strings
 *     dump.getObjectsOfClass("java.lang.String")
 *         .filter(obj -> obj.getShallowSize() > 1000)
 *         .forEach(obj -> System.out.println(obj.getStringValue()));
 *
 *     // Analyze class distribution
 *     dump.getClasses().stream()
 *         .sorted((a, b) -> Integer.compare(b.getInstanceCount(), a.getInstanceCount()))
 *         .limit(10)
 *         .forEach(cls -> System.out.println(cls.getName() + ": " + cls.getInstanceCount()));
 * }
 * }</pre>
 *
 * <p><strong>Limitations:</strong>
 *
 * <ul>
 *   <li>Maximum file size: 2GB (uses memory-mapped I/O with 32-bit addressing)
 *   <li>Thread safety: The returned {@link HeapDump} and its objects are not thread-safe. Access
 *       from multiple threads requires external synchronization.
 * </ul>
 */
public final class HeapDumpParser {

  private HeapDumpParser() {}

  /**
   * Parses a heap dump file.
   *
   * @param path path to the HPROF file
   * @return parsed heap dump
   * @throws IOException if the file cannot be read or has invalid format
   * @throws NullPointerException if path is null
   */
  public static HeapDump parse(Path path) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    return parse(path, ParserOptions.DEFAULT, null);
  }

  /**
   * Parses a heap dump file with progress reporting.
   *
   * @param path path to the HPROF file
   * @param progressCallback optional callback for progress updates (0.0 to 1.0)
   * @return parsed heap dump
   * @throws IOException if the file cannot be read or has invalid format
   * @throws NullPointerException if path is null
   */
  public static HeapDump parse(Path path, ProgressCallback progressCallback) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    return parse(path, ParserOptions.DEFAULT, progressCallback);
  }

  /**
   * Parses a heap dump file with custom options.
   *
   * @param path path to the HPROF file
   * @param options parser options
   * @return parsed heap dump
   * @throws IOException if the file cannot be read or has invalid format
   * @throws NullPointerException if path or options is null
   */
  public static HeapDump parse(Path path, ParserOptions options) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(options, "options must not be null");
    return parse(path, options, null);
  }

  /**
   * Parses a heap dump file with custom options and progress reporting.
   *
   * @param path path to the HPROF file
   * @param options parser options
   * @param progressCallback optional callback for progress updates (0.0 to 1.0)
   * @return parsed heap dump
   * @throws IOException if the file cannot be read or has invalid format
   * @throws NullPointerException if path or options is null
   */
  public static HeapDump parse(Path path, ParserOptions options, ProgressCallback progressCallback)
      throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(options, "options must not be null");

    // Auto-detect parsing mode based on heap dump size
    options = resolveParsingMode(path, options);

    return HeapDumpImpl.parse(path, options, progressCallback);
  }

  /**
   * Resolves AUTO parsing mode to IN_MEMORY or INDEXED based on heap dump file size.
   * Files larger than 2GB use indexed mode, smaller files use in-memory mode.
   */
  private static ParserOptions resolveParsingMode(Path path, ParserOptions options) throws IOException {
    if (options.parsingMode() != ParsingMode.AUTO) {
      return options; // Already resolved
    }

    long fileSize = java.nio.file.Files.size(path);
    boolean useIndexed = fileSize > INDEXED_MODE_THRESHOLD;

    ParsingMode resolved = useIndexed ? ParsingMode.INDEXED : ParsingMode.IN_MEMORY;

    // Log the decision for transparency
    org.slf4j.LoggerFactory.getLogger(HeapDumpParser.class).info(
        "Heap dump size: {} bytes ({}). Using {} parsing mode.",
        fileSize,
        formatBytes(fileSize),
        resolved);

    return new ParserOptions(
        options.computeDominators(),
        options.indexStrings(),
        options.trackInboundRefs(),
        resolved);
  }

  /** Threshold for switching from in-memory to indexed parsing (2 GB). */
  private static final long INDEXED_MODE_THRESHOLD = 2L * 1024 * 1024 * 1024;

  /** Formats byte count as human-readable string. */
  private static String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
  }

  /** Parsing mode for heap dump processing. */
  public enum ParsingMode {
    /**
     * Automatically choose between IN_MEMORY and INDEXED based on heap dump size.
     * Files &gt;2GB use indexed mode, smaller files use in-memory mode.
     */
    AUTO,

    /**
     * In-memory parsing mode. Fast for small heaps (&lt;10M objects, &lt;2GB file size).
     * All objects stored in memory during parsing.
     */
    IN_MEMORY,

    /**
     * Index-based parsing mode. Scalable for large heaps (up to 114M+ objects).
     * Uses disk-based indexes and memory-mapped I/O.
     */
    INDEXED
  }

  /** Parser configuration options. */
  public record ParserOptions(
      boolean computeDominators,
      boolean indexStrings,
      boolean trackInboundRefs,
      ParsingMode parsingMode) {

    /**
     * Default options: auto-detect parsing mode, no dominators, no inbound refs.
     * Files &gt;2GB automatically use indexed mode for scalability.
     */
    public static final ParserOptions DEFAULT = new ParserOptions(false, true, false, ParsingMode.AUTO);

    /** Options for full analysis including dominator computation (auto-detect mode). */
    public static final ParserOptions FULL_ANALYSIS = new ParserOptions(true, true, true, ParsingMode.AUTO);

    /** Options for minimal memory usage (in-memory mode). */
    public static final ParserOptions MINIMAL = new ParserOptions(false, false, false, ParsingMode.IN_MEMORY);

    /**
     * Options for index-based parsing (for large heaps &gt;10M objects).
     * Uses disk-based indexes instead of in-memory maps, enabling analysis
     * of heaps up to 114M+ objects with &lt;4GB heap.
     */
    public static final ParserOptions INDEXED = new ParserOptions(false, true, false, ParsingMode.INDEXED);

    /**
     * Options for in-memory parsing (for small heaps &lt;10M objects).
     * Fast but memory-intensive, all objects stored in memory.
     */
    public static final ParserOptions IN_MEMORY = new ParserOptions(false, true, false, ParsingMode.IN_MEMORY);

    public static Builder builder() {
      return new Builder();
    }

    public static class Builder {
      private boolean computeDominators = false;
      private boolean indexStrings = true;
      private boolean trackInboundRefs = false;
      private ParsingMode parsingMode = ParsingMode.AUTO;

      public Builder computeDominators(boolean value) {
        this.computeDominators = value;
        return this;
      }

      public Builder indexStrings(boolean value) {
        this.indexStrings = value;
        return this;
      }

      public Builder trackInboundRefs(boolean value) {
        this.trackInboundRefs = value;
        return this;
      }

      public Builder parsingMode(ParsingMode mode) {
        this.parsingMode = Objects.requireNonNull(mode, "parsingMode must not be null");
        return this;
      }

      public ParserOptions build() {
        return new ParserOptions(computeDominators, indexStrings, trackInboundRefs, parsingMode);
      }
    }
  }

  /** Callback interface for progress updates during parsing. */
  @FunctionalInterface
  public interface ProgressCallback {
    /**
     * Called periodically during parsing.
     *
     * @param progress progress value between 0.0 and 1.0
     * @param message optional message describing current phase
     */
    void onProgress(double progress, String message);
  }
}

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
    return HeapDumpImpl.parse(path, options, progressCallback);
  }

  /** Parser configuration options. */
  public record ParserOptions(
      boolean computeDominators,
      boolean indexStrings,
      boolean trackInboundRefs,
      boolean useIndexedParsing) {

    /**
     * Default options: in-memory parsing, no dominators, no inbound refs.
     * (indexStrings reserved for future use)
     */
    public static final ParserOptions DEFAULT = new ParserOptions(false, true, false, false);

    /** Options for full analysis including dominator computation (in-memory). */
    public static final ParserOptions FULL_ANALYSIS = new ParserOptions(true, true, true, false);

    /** Options for minimal memory usage (in-memory). */
    public static final ParserOptions MINIMAL = new ParserOptions(false, false, false, false);

    /**
     * Options for index-based parsing (for large heaps >10M objects).
     * Uses disk-based indexes instead of in-memory maps, enabling analysis
     * of heaps up to 114M+ objects with <4GB heap.
     */
    public static final ParserOptions INDEXED = new ParserOptions(false, true, false, true);

    public static Builder builder() {
      return new Builder();
    }

    public static class Builder {
      private boolean computeDominators = false;
      private boolean indexStrings = true;
      private boolean trackInboundRefs = false;
      private boolean useIndexedParsing = false;

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

      public Builder useIndexedParsing(boolean value) {
        this.useIndexedParsing = value;
        return this;
      }

      public ParserOptions build() {
        return new ParserOptions(computeDominators, indexStrings, trackInboundRefs, useIndexedParsing);
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

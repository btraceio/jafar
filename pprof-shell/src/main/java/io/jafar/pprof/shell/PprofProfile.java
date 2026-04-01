package io.jafar.pprof.shell;

import java.util.List;

/**
 * In-memory model for a pprof profile, built from google/pprof/proto/profile.proto.
 *
 * <p>All string references are resolved from the string table at parse time.
 */
public final class PprofProfile {

  private PprofProfile() {}

  /**
   * A complete pprof profile.
   *
   * @param sampleTypes value type descriptors in the same order as {@link Sample#values()}
   * @param samples individual profiling samples
   * @param mappings memory mappings
   * @param locations locations referenced by samples
   * @param functions functions referenced by locations
   * @param stringTable original string table (kept for diagnostics)
   * @param period sampling period in the unit of {@code periodType}
   * @param periodType value type describing the period
   * @param durationNanos profile duration in nanoseconds
   * @param timeNanos profile collection time (Unix timestamp in nanoseconds)
   */
  public record Profile(
      List<ValueType> sampleTypes,
      List<Sample> samples,
      List<Mapping> mappings,
      List<Location> locations,
      List<Function> functions,
      List<String> stringTable,
      long period,
      ValueType periodType,
      long durationNanos,
      long timeNanos) {}

  /**
   * A single profiling sample with a call stack and associated values.
   *
   * @param locationIds call stack from leaf to root (location IDs)
   * @param values sample values in the same order as {@link Profile#sampleTypes()}
   * @param labels key-value annotations on this sample
   */
  public record Sample(List<Long> locationIds, List<Long> values, List<Label> labels) {}

  /**
   * A code location (may represent an inlined call chain via multiple {@link Line}s).
   *
   * @param id unique location identifier
   * @param mappingId the mapping this location belongs to (0 if unknown)
   * @param address virtual memory address
   * @param lines inline call chain at this address, outermost first
   * @param isFolded whether the location is folded (multiple frames collapsed)
   */
  public record Location(
      long id, long mappingId, long address, List<Line> lines, boolean isFolded) {}

  /**
   * A single line within a location.
   *
   * @param functionId the function at this line
   * @param lineNumber source line number (0 if unknown)
   */
  public record Line(long functionId, long lineNumber) {}

  /**
   * A function (symbol), with strings already resolved from the string table.
   *
   * @param id unique function identifier
   * @param name demangled function name
   * @param systemName system function name (may equal {@code name})
   * @param filename source file name
   * @param startLine start line of the function (0 if unknown)
   */
  public record Function(
      long id, String name, String systemName, String filename, long startLine) {}

  /**
   * A memory mapping.
   *
   * @param id unique mapping identifier
   * @param memoryStart start of the mapped virtual address range
   * @param memoryLimit end (exclusive) of the mapped virtual address range
   * @param fileOffset file offset of the mapping
   * @param filename mapped file name
   * @param hasFunctions whether the mapping contains function symbol information
   */
  public record Mapping(
      long id,
      long memoryStart,
      long memoryLimit,
      long fileOffset,
      String filename,
      boolean hasFunctions) {}

  /**
   * A value type descriptor.
   *
   * @param type name of the value (e.g. "cpu", "alloc_objects")
   * @param unit unit of the value (e.g. "nanoseconds", "count")
   */
  public record ValueType(String type, String unit) {}

  /**
   * A sample label (key-value annotation).
   *
   * @param key label key (string, resolved from string table)
   * @param str string value (non-null for string labels, null for numeric labels)
   * @param num numeric value (non-zero for numeric labels)
   * @param numUnit unit for the numeric value (may be null)
   */
  public record Label(String key, String str, long num, String numUnit) {}
}

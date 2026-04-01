package io.jafar.otelp.shell;

import java.util.List;

/**
 * In-memory model for an OpenTelemetry profiling signal, built from
 * opentelemetry/proto/profiles/v1development/profiles.proto.
 *
 * <p>All string references are resolved from the string table at parse time. Index-based references
 * between tables are resolved during the build phase.
 */
public final class OtelpProfile {

  private OtelpProfile() {}

  /**
   * The top-level container holding all profiles and the shared dictionary.
   *
   * @param profiles all profiles from all resource/scope containers, flattened
   * @param dictionary shared lookup tables referenced by index
   */
  public record ProfilesData(List<Profile> profiles, Dictionary dictionary) {}

  /**
   * Shared lookup tables for the entire message. Index 0 in each table is always a zero/null
   * sentinel value per the OTLP spec.
   *
   * @param mappingTable binary mappings referenced by location.mappingIndex
   * @param locationTable code locations referenced by stack.locationIndices
   * @param functionTable function symbols referenced by line.functionIndex
   * @param stringTable string values (index 0 is always "")
   * @param attributeTable key-value attributes referenced by attribute_indices
   * @param stackTable stacks referenced by sample.stackIndex
   */
  public record Dictionary(
      List<Mapping> mappingTable,
      List<Location> locationTable,
      List<Function> functionTable,
      List<String> stringTable,
      List<Attribute> attributeTable,
      List<Stack> stackTable) {}

  /**
   * A single profile with a declared sample type and a list of samples.
   *
   * @param sampleType the value type for all samples in this profile
   * @param samples individual profiling samples
   * @param timeUnixNano profile collection time (Unix timestamp in nanoseconds)
   * @param durationNano profile duration in nanoseconds
   * @param periodType the value type describing the sampling period
   * @param period the number of events between sampled occurrences
   */
  public record Profile(
      ValueType sampleType,
      List<Sample> samples,
      long timeUnixNano,
      long durationNano,
      ValueType periodType,
      long period) {}

  /**
   * A single profiling sample.
   *
   * @param stackIndex index into {@link Dictionary#stackTable()}
   * @param attributeIndices indices into {@link Dictionary#attributeTable()}
   * @param values sample values; the single element corresponds to {@link Profile#sampleType()}
   * @param timestampsUnixNano per-observation timestamps in nanoseconds (may be empty)
   */
  public record Sample(
      int stackIndex,
      List<Integer> attributeIndices,
      List<Long> values,
      List<Long> timestampsUnixNano) {}

  /**
   * A stack trace represented as an ordered list of location indices.
   *
   * @param locationIndices indices into {@link Dictionary#locationTable()}, leaf frame first
   */
  public record Stack(List<Integer> locationIndices) {}

  /**
   * A code location (may represent an inlined call chain via multiple {@link Line}s).
   *
   * @param mappingIndex index into {@link Dictionary#mappingTable()} (0 if unknown)
   * @param address virtual memory address
   * @param lines inline call chain at this address, leaf first
   * @param attributeIndices indices into {@link Dictionary#attributeTable()}
   */
  public record Location(
      int mappingIndex, long address, List<Line> lines, List<Integer> attributeIndices) {}

  /**
   * A single line within a location.
   *
   * @param functionIndex index into {@link Dictionary#functionTable()}
   * @param line source line number (0 if unknown)
   * @param column source column number (0 if unknown)
   */
  public record Line(int functionIndex, long line, long column) {}

  /**
   * A function symbol with strings already resolved from the string table.
   *
   * @param name demangled function name
   * @param systemName system function name (may equal {@code name})
   * @param filename source file name
   * @param startLine start line of the function (0 if unknown)
   */
  public record Function(String name, String systemName, String filename, long startLine) {}

  /**
   * A binary memory mapping.
   *
   * @param memoryStart start of the mapped virtual address range
   * @param memoryLimit end (exclusive) of the mapped virtual address range
   * @param fileOffset file offset of the mapping
   * @param filename mapped file name
   */
  public record Mapping(long memoryStart, long memoryLimit, long fileOffset, String filename) {}

  /**
   * A value type descriptor.
   *
   * @param type name of the value (e.g. "cpu", "alloc_objects")
   * @param unit unit of the value (e.g. "nanoseconds", "count")
   */
  public record ValueType(String type, String unit) {}

  /**
   * A key-value attribute with an optional unit, from the shared attribute table.
   *
   * @param key attribute key (resolved from string table)
   * @param value attribute value as a string (simplified from AnyValue)
   * @param unit optional unit string (resolved from string table, may be empty)
   */
  public record Attribute(String key, String value, String unit) {}
}

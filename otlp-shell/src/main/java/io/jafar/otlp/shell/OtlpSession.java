package io.jafar.otlp.shell;

import io.jafar.shell.core.Session;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session implementation for OpenTelemetry profiling analysis. Wraps a parsed {@link
 * OtlpProfile.ProfilesData}.
 */
public final class OtlpSession implements Session {

  private static final Logger LOG = LoggerFactory.getLogger(OtlpSession.class);

  private final Path path;
  private final OtlpProfile.ProfilesData data;
  private volatile boolean closed = false;

  private OtlpSession(Path path, OtlpProfile.ProfilesData data) {
    this.path = path;
    this.data = data;
  }

  /**
   * Opens and parses an OTLP profiles file.
   *
   * @param path path to the .otlp file
   * @return a new session
   * @throws IOException if reading or parsing fails
   */
  public static OtlpSession open(Path path) throws IOException {
    LOG.info("Opening OTLP profiles: {}", path);
    OtlpProfile.ProfilesData data = OtlpReader.read(path);
    int totalSamples = data.profiles().stream().mapToInt(p -> p.samples().size()).sum();
    LOG.info(
        "Loaded {} profiles, {} total samples, {} locations, {} functions",
        data.profiles().size(),
        totalSamples,
        data.dictionary().locationTable().size(),
        data.dictionary().functionTable().size());
    return new OtlpSession(path, data);
  }

  /** Returns the parsed profiles data for direct access by the evaluator. */
  public OtlpProfile.ProfilesData getData() {
    return data;
  }

  @Override
  public String getType() {
    return "otlp";
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
    return Set.of("samples");
  }

  @Override
  public Map<String, Object> getStatistics() {
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("path", path.toString());
    stats.put("format", "otlp");
    stats.put("profiles", data.profiles().size());
    int totalSamples = data.profiles().stream().mapToInt(p -> p.samples().size()).sum();
    stats.put("samples", totalSamples);
    stats.put("locations", data.dictionary().locationTable().size());
    stats.put("functions", data.dictionary().functionTable().size());
    stats.put("stacks", data.dictionary().stackTable().size());
    if (!data.profiles().isEmpty()) {
      OtlpProfile.Profile first = data.profiles().get(0);
      OtlpProfile.ValueType st = first.sampleType();
      if (st != null && !st.type().isEmpty()) {
        stats.put("sampleType", st.type() + " (" + st.unit() + ")");
      }
      if (first.durationNano() > 0) {
        stats.put("duration", formatDuration(first.durationNano()));
      }
      if (first.timeUnixNano() > 0) {
        stats.put("collectedAt", Instant.ofEpochSecond(0, first.timeUnixNano()).toString());
      }
    }
    return stats;
  }

  private static String formatDuration(long nanos) {
    if (nanos < 1_000) return nanos + " ns";
    if (nanos < 1_000_000) return String.format("%.2f µs", nanos / 1_000.0);
    if (nanos < 1_000_000_000) return String.format("%.2f ms", nanos / 1_000_000.0);
    return String.format("%.2f s", nanos / 1_000_000_000.0);
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      LOG.debug("Closed OTLP session: {}", path);
    }
  }
}

package io.jafar.pprof.shell;

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
 * Session implementation for pprof profile analysis. Wraps a parsed {@link PprofProfile.Profile}.
 */
public final class PprofSession implements Session {

  private static final Logger LOG = LoggerFactory.getLogger(PprofSession.class);

  private final Path path;
  private final PprofProfile.Profile profile;
  private volatile boolean closed = false;

  private PprofSession(Path path, PprofProfile.Profile profile) {
    this.path = path;
    this.profile = profile;
  }

  /**
   * Opens and parses a pprof profile file.
   *
   * @param path path to the .pb.gz or .pprof file
   * @return a new session
   * @throws IOException if reading or parsing fails
   */
  public static PprofSession open(Path path) throws IOException {
    LOG.info("Opening pprof profile: {}", path);
    PprofProfile.Profile profile = PprofReader.read(path);
    LOG.info(
        "Loaded {} samples, {} locations, {} functions",
        profile.samples().size(),
        profile.locations().size(),
        profile.functions().size());
    return new PprofSession(path, profile);
  }

  /** Returns the parsed profile for direct access by the evaluator. */
  public PprofProfile.Profile getProfile() {
    return profile;
  }

  @Override
  public String getType() {
    return "pprof";
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
    stats.put("format", "pprof");
    stats.put("samples", profile.samples().size());
    stats.put("locations", profile.locations().size());
    stats.put("functions", profile.functions().size());
    stats.put("mappings", profile.mappings().size());
    if (!profile.sampleTypes().isEmpty()) {
      StringBuilder types = new StringBuilder();
      for (PprofProfile.ValueType vt : profile.sampleTypes()) {
        if (!types.isEmpty()) types.append(", ");
        types.append(vt.type()).append(" (").append(vt.unit()).append(")");
      }
      stats.put("sampleTypes", types.toString());
    }
    if (profile.durationNanos() > 0) {
      stats.put("duration", formatDuration(profile.durationNanos()));
    }
    if (profile.timeNanos() > 0) {
      stats.put("collectedAt", Instant.ofEpochSecond(0, profile.timeNanos()).toString());
    }
    if (profile.period() > 0 && profile.periodType() != null) {
      stats.put("period", profile.period() + " " + profile.periodType().unit());
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
      LOG.debug("Closed pprof session: {}", path);
    }
  }
}

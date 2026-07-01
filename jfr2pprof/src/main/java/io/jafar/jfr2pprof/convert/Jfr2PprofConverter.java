package io.jafar.jfr2pprof.convert;

import io.jafar.jfr2pprof.config.LabelSpec;
import io.jafar.jfr2pprof.config.MappingConfig;
import io.jafar.jfr2pprof.config.ProfileSpec;
import io.jafar.jfr2pprof.config.ValueSpec;
import io.jafar.jfr2pprof.proto.Label;
import io.jafar.jfr2pprof.proto.PprofBuilder;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.api.Values;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Converts a JFR recording to pprof format according to a {@link MappingConfig}. */
public final class Jfr2PprofConverter {

  /**
   * Converts the JFR recording at {@code jfrPath} to pprof and writes the result to {@code out}.
   *
   * @param jfrPath path to the JFR recording
   * @param config mapping configuration
   * @param gzip whether to gzip-wrap the output
   * @param out destination stream
   * @return the number of distinct samples written
   * @throws IOException on I/O error
   * @throws IllegalStateException if no configured event type is found in the recording
   */
  public int convert(Path jfrPath, MappingConfig config, boolean gzip, OutputStream out)
      throws IOException {
    Map<String, ProfileSpec> byEvent = new HashMap<>();
    for (ProfileSpec spec : config.profiles()) {
      byEvent.put(spec.event(), spec);
    }

    PprofBuilder builder = new PprofBuilder(config.allValueTypes().size());

    long[] timeNanos = {0L};
    long[] durationNanos = {0L};
    long[] lastChunkId = {Long.MIN_VALUE};

    try (UntypedJafarParser p =
        UntypedJafarParser.open(jfrPath, ParsingContext.create(), UntypedStrategy.FULL_ITERATION)) {

      p.handle(
          (type, value, ctl) -> {
            Control.ChunkInfo ci = ctl.chunkInfo();
            if (ci.chunkId() != lastChunkId[0]) {
              lastChunkId[0] = ci.chunkId();
              if (timeNanos[0] == 0L) {
                timeNanos[0] = ci.startTime().toEpochMilli() * 1_000_000L;
              }
              durationNanos[0] += ci.duration().toNanos();
            }

            ProfileSpec spec = byEvent.get(type.getName());
            if (spec == null) return;

            Object frames = Values.get(value, spec.stackField(), "frames");
            long[] locationIds = builder.internStack(frames, config.frame());

            long[] vals = new long[spec.values().size()];
            for (int i = 0; i < vals.length; i++) {
              ValueSpec v = spec.values().get(i);
              if (v.isCount()) {
                vals[i] = 1L;
              } else {
                vals[i] =
                    (long)
                        (Values.as(value, Long.class, (Object[]) v.fieldSegments()).orElse(0L)
                            * v.scale());
              }
            }

            List<Label> labels = new ArrayList<>(spec.labels().size());
            for (LabelSpec l : spec.labels()) {
              Object raw = Values.get(value, (Object[]) l.jfrSegments());
              labels.add(builder.label(l.pprofKey(), raw));
            }

            builder.addSample(locationIds, spec.sampleTypeIndex(), vals, labels);
          });
      p.run();
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Failed to parse JFR recording: " + jfrPath, e);
    }

    if (builder.sampleCount() == 0) {
      throw new IllegalStateException("No configured event type found in: " + jfrPath);
    }

    builder.build(config.allValueTypes(), timeNanos[0], durationNanos[0], gzip, out);
    return builder.sampleCount();
  }
}

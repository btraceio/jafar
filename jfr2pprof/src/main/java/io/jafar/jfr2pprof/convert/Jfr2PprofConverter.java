package io.jafar.jfr2pprof.convert;

import io.jafar.jfr2pprof.config.MappingConfig;
import io.jafar.jfr2pprof.config.ProfileSpec;
import io.jafar.jfr2pprof.config.ValueSpec;
import io.jafar.jfr2pprof.proto.Label;
import io.jafar.jfr2pprof.proto.PprofBuilder;
import io.jafar.parser.TypeFilter;
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

    try (UntypedJafarParser p =
        UntypedJafarParser.open(jfrPath, ParsingContext.create(), UntypedStrategy.FULL_ITERATION)) {

      // Only the mapped event types are decoded; the parser skips every other event unread.
      p.handle(
          TypeFilter.named(byEvent.keySet()),
          (type, value, ctl) -> {
            ProfileSpec spec = byEvent.get(type.getName());
            if (spec == null) return;

            // The values are read and the frames rendered on the parsing thread, outside the lock.
            PprofBuilder.RenderedFrame[] frames =
                PprofBuilder.renderStack(
                    Values.get(value, spec.stackField(), "frames"), config.frame());

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

            Object[] rawLabels = new Object[spec.labels().size()];
            for (int i = 0; i < rawLabels.length; i++) {
              rawLabels[i] = Values.get(value, (Object[]) spec.labels().get(i).jfrSegments());
            }

            // The parser calls this handler from one thread per chunk at the same time, and
            // PprofBuilder is not thread-safe: unsynchronised, samples of different chunks that
            // share a stack and labels lose each other's additions.
            synchronized (builder) {
              long[] locationIds = builder.internFrames(frames);
              List<Label> labels = new ArrayList<>(rawLabels.length);
              for (int i = 0; i < rawLabels.length; i++) {
                labels.add(builder.label(spec.labels().get(i).pprofKey(), rawLabels[i]));
              }
              builder.addSample(locationIds, spec.sampleTypeIndex(), vals, labels);
            }
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

    // From the chunk headers, not from the events: a chunk without a mapped event is never
    // delivered to the handler, and its time still belongs to the profile.
    RecordingSpan span = RecordingSpan.read(jfrPath);
    builder.build(config.allValueTypes(), span.startEpochNanos(), span.durationNanos(), gzip, out);
    return builder.sampleCount();
  }
}

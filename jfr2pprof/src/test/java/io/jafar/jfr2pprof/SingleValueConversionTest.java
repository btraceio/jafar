package io.jafar.jfr2pprof;

import static org.assertj.core.api.Assertions.assertThat;

import io.jafar.jfr2pprof.config.MappingConfig;
import io.jafar.jfr2pprof.config.MappingLoader;
import io.jafar.jfr2pprof.convert.Jfr2PprofConverter;
import io.jafar.pprof.shell.PprofProfile;
import io.jafar.pprof.shell.PprofReader;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

class SingleValueConversionTest {

  @TempDir Path tempDir;

  private static final String EVENT_NAME = "test.CpuSample";

  private static final String CONFIG_YAML =
      """
            profiles:
              - event: test.CpuSample
                stackField: stackTrace
                values:
                  - name: cpu-time
                    unit: nanoseconds
                    field: weight
            """;

  /**
   * Creates a JFR file with a custom "test.CpuSample" event that has: - a stackTrace field (JDK
   * StackTrace type, written explicitly as null) - a weight field (long, custom)
   *
   * <p>The JMC writer requires that implicit event fields (startTime, eventThread, stackTrace) be
   * written explicitly to the event stream even though they appear in the type metadata
   * automatically. Failure to write them causes field deserialization to be offset.
   */
  private Path createJfrFile() throws Exception {
    Path jfrFile = tempDir.resolve("test.jfr");
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    try (Recording recording = Recordings.newRecording(baos)) {
      Types types = recording.getTypes();
      Type stackTraceType = types.getType(Types.JDK.STACK_TRACE);
      Type threadType = types.getType(Types.JDK.THREAD);

      // registerEventType automatically adds startTime, eventThread, stackTrace to metadata
      Type eventType =
          recording.registerEventType(EVENT_NAME, b -> b.addField("weight", Types.Builtin.LONG));

      // Implicit fields must be written explicitly (JMC Writer bug workaround)
      recording.writeEvent(
          eventType.asValue(
              v -> {
                v.putField("stackTrace", stackTraceType.nullValue());
                v.putField("eventThread", threadType.nullValue());
                v.putField("startTime", System.nanoTime());
                v.putField("weight", 100_000L);
              }));
    }

    Files.write(jfrFile, baos.toByteArray());
    return jfrFile;
  }

  @Test
  void testSingleValueConversion() throws Exception {
    Path jfrFile = createJfrFile();
    MappingConfig config = MappingLoader.load(new StringReader(CONFIG_YAML));

    Path outFile = tempDir.resolve("out.pprof");
    try (var out = Files.newOutputStream(outFile)) {
      new Jfr2PprofConverter().convert(jfrFile, config, true, out);
    }

    PprofProfile.Profile profile = PprofReader.read(outFile);

    assertThat(profile.sampleTypes()).hasSize(1);
    assertThat(profile.sampleTypes().get(0).type()).isEqualTo("cpu-time");
    assertThat(profile.sampleTypes().get(0).unit()).isEqualTo("nanoseconds");

    assertThat(profile.samples().size()).isGreaterThanOrEqualTo(1);
    assertThat(profile.samples().get(0).values()).hasSizeGreaterThanOrEqualTo(1);

    // T11 AC: duration and time fields must be > 0
    assertThat(profile.durationNanos()).isGreaterThan(0);
    assertThat(profile.timeNanos()).isGreaterThan(0);
  }
}

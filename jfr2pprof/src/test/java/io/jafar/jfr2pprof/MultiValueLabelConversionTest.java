package io.jafar.jfr2pprof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

class MultiValueLabelConversionTest {

  @TempDir Path tempDir;

  private static final String EVENT_NAME = "test.AllocSample";

  private static final String CONFIG_YAML =
      """
            profiles:
              - event: test.AllocSample
                stackField: stackTrace
                values:
                  - { name: alloc-samples, unit: count, field: "@count" }
                  - { name: alloc-space,   unit: bytes, field: weight }
                labels:
                  - jfr: sampledThread.osThreadId
                    pprof: "thread id"
                  - jfr: sampledThread.javaName
                    pprof: "thread name"
            """;

  /**
   * Creates a JFR file with a custom "test.AllocSample" event that has: - a stackTrace field (JDK
   * StackTrace type, written explicitly as null) - a weight field (long, custom) - a sampledThread
   * field (JDK Thread type, custom) with osThreadId and javaName
   *
   * <p>The JMC writer requires that implicit event fields (startTime, eventThread, stackTrace) be
   * written explicitly to the event stream even though they appear in the type metadata
   * automatically. Failure to write them causes field deserialization to be offset.
   */
  private Path createJfrFile() throws Exception {
    Path jfrFile = tempDir.resolve("test-alloc.jfr");
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    try (Recording recording = Recordings.newRecording(baos)) {
      Types types = recording.getTypes();
      Type stackTraceType = types.getType(Types.JDK.STACK_TRACE);
      Type threadType = types.getType(Types.JDK.THREAD);

      // Build thread value for the sampledThread label
      Thread currentThread = Thread.currentThread();
      TypedValue threadValue =
          threadType.asValue(
              tv ->
                  tv.putField("osThreadId", currentThread.getId())
                      .putField("javaThreadId", currentThread.getId())
                      .putField("javaName", currentThread.getName()));

      // registerEventType automatically adds startTime, eventThread, stackTrace to metadata
      Type eventType =
          recording.registerEventType(
              EVENT_NAME,
              b -> {
                b.addField("weight", Types.Builtin.LONG);
                b.addField("sampledThread", Types.JDK.THREAD);
              });

      // Implicit fields must be written explicitly (JMC Writer bug workaround)
      recording.writeEvent(
          eventType.asValue(
              v -> {
                v.putField("stackTrace", stackTraceType.nullValue());
                v.putField("eventThread", threadType.nullValue());
                v.putField("startTime", System.nanoTime());
                v.putField("weight", 4096L);
                v.putField("sampledThread", threadValue);
              }));
    }

    Files.write(jfrFile, baos.toByteArray());
    return jfrFile;
  }

  @Test
  void testMultiValueLabelConversion() throws Exception {
    Path jfrFile = createJfrFile();
    MappingConfig config = MappingLoader.load(new StringReader(CONFIG_YAML));

    assertThat(config.profiles()).hasSize(1);
    assertThat(config.allValueTypes()).hasSize(2);
    assertThat(config.profiles().get(0).labels()).hasSize(2);

    Path outFile = tempDir.resolve("out-alloc.pprof");
    try (var out = Files.newOutputStream(outFile)) {
      new Jfr2PprofConverter().convert(jfrFile, config, true, out);
    }

    PprofProfile.Profile profile = PprofReader.read(outFile);

    // Must have 2 sample types: alloc-samples/count and alloc-space/bytes
    assertThat(profile.sampleTypes()).hasSize(2);
    assertThat(profile.sampleTypes().get(0).type()).isEqualTo("alloc-samples");
    assertThat(profile.sampleTypes().get(0).unit()).isEqualTo("count");
    assertThat(profile.sampleTypes().get(1).type()).isEqualTo("alloc-space");
    assertThat(profile.sampleTypes().get(1).unit()).isEqualTo("bytes");

    assertThat(profile.samples()).hasSizeGreaterThanOrEqualTo(1);

    PprofProfile.Sample sample = profile.samples().get(0);

    // Each sample must have 2 values (one per value type column)
    assertThat(sample.values()).hasSize(2);
    // alloc-samples uses @count -> 1
    assertThat(sample.values().get(0)).isEqualTo(1L);
    // alloc-space uses weight field -> 4096
    assertThat(sample.values().get(1)).isEqualTo(4096L);

    // Must have both a numeric and a string label
    assertThat(sample.labels()).hasSize(2);

    boolean hasNumericLabel = sample.labels().stream().anyMatch(l -> l.str() == null);
    boolean hasStringLabel = sample.labels().stream().anyMatch(l -> l.str() != null);
    assertThat(hasNumericLabel).as("expected numeric label for thread id").isTrue();
    assertThat(hasStringLabel).as("expected string label for thread name").isTrue();
  }

  @Test
  void testNoMatchingEventsThrows() throws Exception {
    // Create a JFR with events that don't match the config
    Path jfrFile = tempDir.resolve("no-match.jfr");
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (Recording recording = Recordings.newRecording(baos)) {
      Type eventType = recording.registerEventType("other.Event");
      recording.writeEvent(eventType.asValue(v -> v.putField("startTime", 0L)));
    }
    Files.write(jfrFile, baos.toByteArray());

    MappingConfig config = MappingLoader.load(new StringReader(CONFIG_YAML));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertThatThrownBy(() -> new Jfr2PprofConverter().convert(jfrFile, config, false, out))
        .isInstanceOf(IllegalStateException.class);
  }
}

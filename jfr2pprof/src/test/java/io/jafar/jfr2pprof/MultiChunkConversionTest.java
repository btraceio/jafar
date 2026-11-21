package io.jafar.jfr2pprof;

import static org.assertj.core.api.Assertions.assertThat;

import io.jafar.jfr2pprof.config.MappingConfig;
import io.jafar.jfr2pprof.config.MappingLoader;
import io.jafar.jfr2pprof.convert.Jfr2PprofConverter;
import io.jafar.pprof.api.PprofParser;
import io.jafar.pprof.api.PprofProfile;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Converts a recording of several chunks.
 *
 * <p>The parser hands events of different chunks to the handler from different threads at the same
 * time. Every sample here has the same stack and labels, so all of them add into one pprof sample:
 * an unsynchronised {@code +=} on that sample loses additions, and the total comes out short.
 *
 * <p>Each chunk also holds events of a type the mapping does not name, and one chunk holds nothing
 * but those: the converter skips them, and the profile duration must still cover that chunk.
 */
class MultiChunkConversionTest {

  @TempDir Path tempDir;

  private static final String SAMPLE = "test.CpuSample";
  private static final String OTHER = "test.Unmapped";

  private static final int CHUNKS = 8;
  private static final int SAMPLES_PER_CHUNK = 50_000;
  private static final int OTHERS_PER_CHUNK = 1_000;

  private static final String CONFIG_YAML =
      """
            profiles:
              - event: test.CpuSample
                stackField: stackTrace
                values:
                  - name: samples
                    unit: count
                    field: "@count"
                  - name: cpu-time
                    unit: nanoseconds
                    field: weight
            """;

  @Test
  void everySampleOfEveryChunkIsCounted() throws Exception {
    Path jfr = tempDir.resolve("multi.jfr");
    try (OutputStream out = Files.newOutputStream(jfr)) {
      for (int i = 0; i < CHUNKS; i++) {
        out.write(chunk(SAMPLES_PER_CHUNK, OTHERS_PER_CHUNK));
      }
      // A chunk with no sample at all: nothing in it reaches the converter's handler.
      out.write(chunk(0, OTHERS_PER_CHUNK));
    }
    long[] header = sumOfChunkHeaders(jfr);
    // Without time of its own, the sample-free chunk would not show whether it is counted.
    assertThat(header[2]).as("duration of the sample-free last chunk").isPositive();
    MappingConfig config = MappingLoader.load(new StringReader(CONFIG_YAML));

    Path pprof = tempDir.resolve("multi.pprof");
    try (OutputStream out = Files.newOutputStream(pprof)) {
      new Jfr2PprofConverter().convert(jfr, config, false, out);
    }
    PprofProfile.Profile profile = PprofParser.parse(pprof);

    long expected = (long) CHUNKS * SAMPLES_PER_CHUNK;
    assertThat(profile.samples()).hasSize(1);
    assertThat(profile.samples().get(0).values()).containsExactly(expected, expected * 7);
    assertThat(profile.timeNanos()).isEqualTo(header[0]);
    assertThat(profile.durationNanos()).isEqualTo(header[1]);
  }

  /** One single-chunk recording: {@code samples} mapped events and {@code others} unmapped ones. */
  private static byte[] chunk(int samples, int others) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (Recording recording = Recordings.newRecording(bytes)) {
      Types types = recording.getTypes();
      Type stackTrace = types.getType(Types.JDK.STACK_TRACE);
      Type thread = types.getType(Types.JDK.THREAD);
      Type sample =
          recording.registerEventType(SAMPLE, b -> b.addField("weight", Types.Builtin.LONG));
      Type other = recording.registerEventType(OTHER, b -> b.addField("v", Types.Builtin.LONG));
      // The JMC writer needs the implicit fields written explicitly; see
      // SingleValueConversionTest.
      for (int i = 0; i < samples; i++) {
        recording.writeEvent(
            sample.asValue(
                v -> {
                  v.putField("stackTrace", stackTrace.nullValue());
                  v.putField("eventThread", thread.nullValue());
                  v.putField("startTime", System.nanoTime());
                  v.putField("weight", 7L);
                }));
      }
      for (int i = 0; i < others; i++) {
        long value = i;
        recording.writeEvent(
            other.asValue(
                v -> {
                  v.putField("stackTrace", stackTrace.nullValue());
                  v.putField("eventThread", thread.nullValue());
                  v.putField("startTime", System.nanoTime());
                  v.putField("v", value);
                }));
      }
    }
    return bytes.toByteArray();
  }

  /**
   * The earliest start, the summed duration and the last chunk's duration of the recording's
   * chunks, in nanoseconds, read from the chunk headers: the size at offset 8, the start at 32 and
   * the duration at 40.
   */
  private static long[] sumOfChunkHeaders(Path jfr) throws Exception {
    ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(jfr));
    long start = Long.MAX_VALUE;
    long duration = 0;
    long last = 0;
    int chunks = 0;
    for (int pos = 0; pos < file.limit(); pos += (int) file.getLong(pos + 8)) {
      start = Math.min(start, file.getLong(pos + 32));
      last = file.getLong(pos + 40);
      duration += last;
      chunks++;
    }
    assertThat(chunks).isEqualTo(CHUNKS + 1);
    return new long[] {start, duration, last};
  }
}

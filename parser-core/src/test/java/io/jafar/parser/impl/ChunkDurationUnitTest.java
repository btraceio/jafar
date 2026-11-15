package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.impl.SyntheticRecording.Bytes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A chunk header's duration is in nanoseconds, whatever the chunk's tick frequency.
 *
 * <p>{@link ChunkInfoImpl} used to convert it as a tick count. That gives the same number when the
 * frequency is 1e9 and a wrong one otherwise: on a JDK 21 recording with a frequency of 2.1e9, a
 * chunk that lasted 119.3 ms (the distance to the next chunk's start, exactly) was reported as 56.8
 * ms. The JDK's own reader names the field {@code durationNanos}.
 */
class ChunkDurationUnitTest {
  private static final int T_LONG = 6;
  private static final int T_EVENT = 101;

  private static final long START_NANOS = 1_700_000_000_000_000_000L;
  private static final long START_TICKS = 5_000_000L;
  private static final long DURATION_NANOS = 119_294_428L;

  @TempDir Path dir;

  private Control.ChunkInfo chunkInfo(long frequency) throws Exception {
    List<byte[]> events = new ArrayList<>();
    events.add(new Bytes().varint(T_EVENT).varint(START_TICKS + 10).toByteArray());
    Path jfr = dir.resolve("frequency-" + frequency + ".jfr");
    Files.write(
        jfr,
        SyntheticRecording.chunk(
            elem("root")
                .with(
                    elem("metadata")
                        .with(
                            elem("class", "id", "" + T_LONG, "name", "long"),
                            elem(
                                    "class",
                                    "id",
                                    "" + T_EVENT,
                                    "name",
                                    "test.Event",
                                    "superType",
                                    "jdk.jfr.Event")
                                .with(elem("field", "name", "value", "class", "" + T_LONG)))),
            events,
            START_NANOS,
            START_TICKS,
            frequency,
            DURATION_NANOS));

    List<Control.ChunkInfo> seen = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser parser = ParsingContext.create().newUntypedParser(jfr)) {
      parser.handle((type, value, ctl) -> seen.add(ctl.chunkInfo()));
      parser.run();
    }
    assertEquals(1, seen.size(), "one event, one chunk info");
    return seen.get(0);
  }

  @Test
  void durationIsTheHeaderValueInNanosecondsAtATscFrequency() throws Exception {
    Control.ChunkInfo info = chunkInfo(2_100_000_000L);
    assertEquals(Duration.ofNanos(DURATION_NANOS), info.duration());
    // the tick conversions are what depend on the frequency
    assertEquals(1_000_000_000L, info.asDurationNanos(2_100_000_000L));
  }

  @Test
  void durationIsTheHeaderValueInNanosecondsAtASlowFrequency() throws Exception {
    Control.ChunkInfo info = chunkInfo(1_000_000L);
    assertEquals(Duration.ofNanos(DURATION_NANOS), info.duration());
  }

  @Test
  void durationIsUnchangedWhereTicksAreNanoseconds() throws Exception {
    assertEquals(Duration.ofNanos(DURATION_NANOS), chunkInfo(1_000_000_000L).duration());
  }
}

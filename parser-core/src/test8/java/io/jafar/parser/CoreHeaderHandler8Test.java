package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser.EventHeaderHandler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Event headers on a JDK 8 runtime: the start times {@code handleHeaders} carries on the shared
 * {@code go-parser/jfr/testdata/start-time-shapes.jfr} (built by parser-core's test {@code
 * StartTimeShapesFixture}, which this source set cannot see, hence the constants here): epoch
 * nanoseconds after skipped fields, the stored value without {@code @Timestamp}, {@code
 * NO_START_TIME} otherwise, and a parse that goes on past an unreachable start time.
 */
public class CoreHeaderHandler8Test {
  private static final Path SHAPES = Paths.get("../go-parser/jfr/testdata/start-time-shapes.jfr");
  private static final long START_NANOS = 1_700_000_000_000_000_000L;
  private static final long START_TICKS = 5_000_000L;
  private static final long NS_PER_TICK = 1000L;

  private static long ticks(int seq) {
    return START_TICKS + 100L * (seq + 1);
  }

  private static long epochNanos(long ticks) {
    return START_NANOS + (ticks - START_TICKS) * NS_PER_TICK;
  }

  @Test
  void startTimesOnJdk8() throws Exception {
    // R9: the test8 task must really run this on Java 8
    assertEquals("1.8", System.getProperty("java.specification.version"));
    final Map<String, List<Long>> byType = Collections.synchronizedMap(new TreeMap<>());
    try (UntypedJafarParser parser = ParsingContext.create().newUntypedParser(SHAPES)) {
      parser.handleHeaders(
          type -> true,
          (type, size, startTime, ctl) -> {
            synchronized (byType) {
              byType.computeIfAbsent(type.getName(), k -> new ArrayList<>()).add(startTime);
            }
          });
      parser.run();
    }
    long none = EventHeaderHandler.NO_START_TIME;
    Map<String, List<Long>> expected = new TreeMap<>();
    expected.put(
        "test.LateStart",
        Arrays.asList(epochNanos(ticks(0)), epochNanos(ticks(2)), epochNanos(ticks(8))));
    expected.put("test.CorruptBefore", Collections.singletonList(none));
    expected.put("test.ArrayStart", Collections.singletonList(none));
    expected.put("test.PooledStart", Collections.singletonList(none));
    expected.put("test.IntStart", Collections.singletonList(none));
    expected.put("test.UntimedStart", Collections.singletonList(ticks(6)));
    expected.put("test.UndeclaredBefore", Collections.singletonList(none));
    assertEquals(expected, byType);
  }
}

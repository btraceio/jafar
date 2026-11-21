package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

class TypeDiscoveryTypeCountsTest {

  private static final Path RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr");

  /** The counts, now taken from event headers, equal those of a parse that decodes every event. */
  @Test
  void typeCountsEqualTheDecodedCounts() throws Exception {
    Map<String, LongAdder> decoded = new ConcurrentHashMap<>();
    try (UntypedJafarParser parser = ParsingContext.create().newUntypedParser(RECORDING)) {
      parser.handle(
          (type, value, ctl) ->
              decoded.computeIfAbsent(type.getName(), k -> new LongAdder()).increment());
      parser.run();
    }
    Map<String, Long> expected = new TreeMap<>();
    decoded.forEach((name, count) -> expected.put(name, count.sum()));
    assertEquals(67_657L, expected.values().stream().mapToLong(Long::longValue).sum());

    assertEquals(expected, new TypeDiscovery(ParsingContext.create()).getTypeCounts(RECORDING));
  }
}

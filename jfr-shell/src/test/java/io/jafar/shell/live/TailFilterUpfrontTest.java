package io.jafar.shell.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.Main;
import io.jafar.shell.jfrpath.UnsupportedPipelineOperationException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * What a {@code tail} filter may hold (#185), checked when it is given, not when its first event
 * arrives: on a target that never emits the type, that would be never. Row-set operators aggregate;
 * only those that read the recording, and {@code flamegraph}, are refused.
 */
class TailFilterUpfrontTest {

  @Test
  void operatorsThatCannotRunLiveAreRefused() {
    for (String q :
        new String[] {
          "events/jdk.FileRead | flamegraph()",
          "events/jdk.FileRead | crossref()",
          "events/jdk.FileRead | decorateByTime(jdk.ThreadPark, fields=duration)",
          "events/jdk.FileRead | filter(bytesRead > 1) | count() | flamegraph()",
        }) {
      assertThrows(UnsupportedPipelineOperationException.class, () -> TailRunner.parseFilter(q), q);
    }
    var e =
        assertThrows(
            UnsupportedPipelineOperationException.class,
            () -> TailRunner.parseFilter("events/jdk.FileRead | flamegraph()"));
    assertEquals("flamegraph", e.getOperation());
    assertTrue(e.getMessage().contains("stackprofile()"), e.getMessage());
  }

  @Test
  void aValueOperatorWithNoPathNeedsAProjection() {
    for (String q :
        new String[] {
          "events/jdk.FileRead | stats()",
          "events/jdk.FileRead | sum()",
          "events/jdk.FileRead | quantiles(0.5)",
          "events/jdk.FileRead | filter(bytesRead > 0) | sketch()",
        }) {
      var e = assertThrows(IllegalArgumentException.class, () -> TailRunner.parseFilter(q), q);
      assertTrue(e.getMessage().contains("requires projection or a value path"), e.getMessage());
    }
    // With a projection or a value path they aggregate
    assertTrue(
        TailRunner.aggregates(TailRunner.parseFilter("events/jdk.FileRead/bytesRead | stats()")));
    assertTrue(
        TailRunner.aggregates(TailRunner.parseFilter("events/jdk.FileRead | stats(bytesRead)")));
    // After select() the rows are its columns, as in a file query
    assertTrue(
        TailRunner.aggregates(
            TailRunner.parseFilter("events/jdk.FileRead | select(bytesRead) | stats()")));
  }

  @Test
  void rowSetOperatorsAggregate() {
    for (String q :
        new String[] {
          "events/jdk.FileRead | count()",
          "events/jdk.FileRead | groupBy(path)",
          "events/jdk.FileRead | top(10, by=bytesRead)",
          "events/jdk.FileRead | stats(bytesRead)",
          "events/jdk.FileRead | stackprofile()",
          "events/jdk.FileRead | filter(bytesRead > 1) | sortby(path) | head(3)",
        }) {
      assertTrue(TailRunner.aggregates(TailRunner.parseFilter(q)), q);
    }
    assertFalse(
        TailRunner.aggregates(TailRunner.parseFilter("events/jdk.FileRead | select(path)")));
    assertFalse(TailRunner.aggregates(null));
  }

  @Test
  void everyTakesSecondsOrAUnit() {
    assertEquals(Duration.ofSeconds(5), TailRunner.parseEvery("5"));
    assertEquals(Duration.ofSeconds(5), TailRunner.parseEvery("5s"));
    assertEquals(Duration.ofMillis(500), TailRunner.parseEvery("500ms"));
    assertEquals(Duration.ofMinutes(2), TailRunner.parseEvery("2m"));
    assertEquals(Duration.ofHours(24), TailRunner.parseEvery("1440m"));
    for (String bad :
        new String[] {
          "0",
          "0s",
          "-1",
          "5h",
          "fast",
          "",
          "1441m",
          "999999999m",
          "999999999999999m",
          "999999999999999999999"
        }) {
      assertThrows(IllegalArgumentException.class, () -> TailRunner.parseEvery(bad), bad);
    }
  }

  private static int cli(ByteArrayOutputStream err, String... args) {
    PrintStream saved = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      return new CommandLine(new Main()).execute(args);
    } finally {
      System.setErr(saved);
    }
  }

  /**
   * The one-shot CLI: the filter is checked before the target is looked up, so a pid nothing runs
   * under still gets the operator error, not a process error.
   */
  @Test
  void theCliRefusesItBeforeLookingForTheTarget() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int rc = cli(err, "tail", "2147483646", "events/jdk.FileRead | flamegraph()");
    String stderr = err.toString(StandardCharsets.UTF_8);
    assertEquals(1, rc, stderr);
    assertTrue(stderr.contains("Pipeline operator 'flamegraph()'"), stderr);
    assertFalse(stderr.contains("2147483646"), stderr);
  }

  @Test
  void theCliRefusesEveryWithoutAnAggregatingFilter() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int rc = cli(err, "tail", "2147483646", "events/jdk.FileRead", "--every", "1s");
    String stderr = err.toString(StandardCharsets.UTF_8);
    assertEquals(1, rc, stderr);
    assertTrue(stderr.contains("--every and --max-rows apply to a filter that aggregates"), stderr);
  }
}

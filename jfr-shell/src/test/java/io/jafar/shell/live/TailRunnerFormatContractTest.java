package io.jafar.shell.live;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The decoded-event contract between {@link LiveEventDecoder} (shell-core) and the formatters
 * ({@code TailRunner.format} table path, {@code JsonLines.toJson}): the decorated keys are pinned
 * by constants, the table path hides exactly {@code NON_DATA_KEYS}, the timestamp comes from the
 * explicit {@code startNanos} parameter (not the possibly-transformed map), and no {@code
 * __eventType} side-channel key exists anywhere.
 */
class TailRunnerFormatContractTest {

  private static Map<String, Object> decoratedMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("costlyStacks", true);
    m.put(LiveEventDecoder.START_TIME_KEY, 0L);
    m.put(LiveEventDecoder.DURATION_KEY, 5L);
    return m;
  }

  @Test
  void tableHidesOnlyTheDecoratedKeys() {
    // NOTE: no real field named __eventType exists in the fixture — a leaked side-channel key
    // would show up as an extra column.
    String line =
        TailRunner.format(
            "jdk.FileRead", 1_700_000_000_000_000_000L, decoratedMap(), TailRunner.Format.TABLE);
    assertTrue(line.contains("costlyStacks=true"), line);
    assertFalse(line.contains("startTime="), line);
    assertFalse(line.contains("duration="), line);
    assertFalse(line.contains("__eventType"), line);
  }

  @Test
  void jsonKeepsDecoratedTimesAndNeverHasTheSideChannel() {
    String line = TailRunner.format("jdk.FileRead", 0L, decoratedMap(), TailRunner.Format.JSON);
    assertTrue(line.contains("\"startTime\""), line);
    assertTrue(line.contains("\"duration\""), line);
    assertFalse(line.contains("__eventType"), line);
  }

  @Test
  void tableTimestampSurvivesATransformThatRewritesStartTime() {
    // A pipeline op rewrote the startTime key to a String: the rendered clock time must still
    // come from the event's own startNanos parameter. Expected prefix is derived with the same
    // pattern/zone TailRunner renders with (system zone — NOT assumed UTC).
    Map<String, Object> m = decoratedMap();
    m.put(LiveEventDecoder.START_TIME_KEY, "rewritten-by-pipeline");
    String line = TailRunner.format("jdk.FileRead", 0L, m, TailRunner.Format.TABLE);
    String expectedPrefix =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(0, 0L));
    assertTrue(line.startsWith(expectedPrefix), line);
    assertFalse(line.contains("rewritten-by-pipeline"), line);
  }

  @Test
  void aRealFieldNamedEventTypeKeyNowRenders() {
    // Before the side-channel removal, a real user field named __eventType was shadowed by the
    // injected matching key. Now it is plain data and renders in both formats.
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("__eventType", "user-data");
    String table = TailRunner.format("jdk.FileRead", 0L, m, TailRunner.Format.TABLE);
    assertTrue(table.contains("__eventType=user-data"), table);
    String json = TailRunner.format("jdk.FileRead", 0L, m, TailRunner.Format.JSON);
    assertTrue(json.contains("__eventType"), json);
  }

  @Test
  void literalEventTypeKeyPredicateMatchesNothing() {
    // A filter naming the literal __eventType is an ordinary field predicate now — the map no
    // longer carries that key, so it matches nothing (regression pin for the side-channel
    // removal).
    var q = io.jafar.shell.jfrpath.JfrPathParser.parse("events/jdk.FileRead[__eventType == \"x\"]");
    assertFalse(
        new io.jafar.shell.jfrpath.JfrPathEvaluator()
            .matchesEvent(q, "jdk.FileRead", decoratedMap()));
  }

  @Test
  void nonDataKeysConstantMatchesTheDecoratedSet() {
    assertEquals(
        java.util.Set.of("startTime", "duration"),
        java.util.Set.copyOf(LiveEventDecoder.NON_DATA_KEYS));
  }
}

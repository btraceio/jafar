package io.jafar.shell.live;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code PrintSink} output-channel discipline (status suppressed in JSON mode, summary always
 * printed) and the bounded table cells ({@code summarizeValue}: type+size placeholders for
 * non-scalars, 48-char truncation for long scalars). Lives in {@code io.jafar.shell.live} because
 * {@code TailRunner.format} is package-private.
 */
class TailRunnerOutputTest {

  private static class CapturingIO implements io.jafar.shell.cli.CommandDispatcher.IO {
    final List<String> stdout = new java.util.ArrayList<>();
    final List<String> stderr = new java.util.ArrayList<>();

    @Override
    public void println(String s) {
      stdout.add(s);
    }

    @Override
    public void printf(String fmt, Object... args) {
      stdout.add(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      stderr.add(s);
    }
  }

  @Test
  void statusIsSuppressedInJsonMode() {
    CapturingIO io = new CapturingIO();
    TailRunner.PrintSink sink = new TailRunner.PrintSink(io, TailRunner.Format.JSON);
    sink.status("listening: 1 shown, 2 filtered, 3 dropped");
    assertEquals(0, io.stdout.size(), "JSON mode must keep stdout pure JSON-lines");
    assertEquals(0, io.stderr.size());
  }

  @Test
  void statusPrintsInTableMode() {
    CapturingIO io = new CapturingIO();
    TailRunner.PrintSink sink = new TailRunner.PrintSink(io, TailRunner.Format.TABLE);
    sink.status("listening: 1 shown, 2 filtered, 3 dropped");
    assertEquals(List.of("listening: 1 shown, 2 filtered, 3 dropped"), io.stdout);
  }

  @Test
  void endSummaryAlwaysPrints() {
    for (TailRunner.Format format : TailRunner.Format.values()) {
      CapturingIO io = new CapturingIO();
      TailRunner.PrintSink sink = new TailRunner.PrintSink(io, format);
      sink.end("tail finished (limit reached): 3 events shown, 0 filtered, 0 dropped");
      assertEquals(
          List.of("tail finished (limit reached): 3 events shown, 0 filtered, 0 dropped"),
          io.stdout,
          format.toString());
    }
  }

  private static String table(Map<String, Object> fields) {
    return TailRunner.format("jdk.FileRead", 0L, fields, TailRunner.Format.TABLE);
  }

  @Test
  void mapValueRendersAsSizePlaceholder() {
    Map<String, Object> m = new LinkedHashMap<>();
    Map<String, Object> nested = new LinkedHashMap<>();
    for (int i = 0; i < 10; i++) nested.put("k" + i, i);
    m.put("payload", nested);
    String line = table(m);
    assertTrue(line.contains("payload=map(size=10)"), line);
    assertFalse(line.contains("k9"), "full toString must not be materialized: " + line);
  }

  @Test
  void collectionValueRendersAsSizePlaceholder() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("frames", List.of("a", "b", "c"));
    String line = table(m);
    assertTrue(line.contains("frames=list(size=3)"), line);
  }

  @Test
  void largeIterableRendersWithOverflowMark() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(
        "items",
        (Iterable<Integer>) () -> java.util.stream.IntStream.range(0, 5000).boxed().iterator());
    String line = table(m);
    assertTrue(line.contains("items=list(size>=1000+)"), line);
  }

  @Test
  void longScalarStillTruncatesAt48Chars() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("cmd", "x".repeat(100));
    String line = table(m);
    assertTrue(line.contains("cmd=" + "x".repeat(47) + "…"), line);
    assertFalse(line.contains("x".repeat(48)), line);
  }
}

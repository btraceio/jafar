package io.jafar.otlp.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.otlp.api.OtlpParser;
import io.jafar.otlp.api.OtlpProfile;
import io.jafar.otlp.shell.otlppath.OtlpPathEvaluator;
import io.jafar.otlp.shell.otlppath.OtlpPathParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The OTLP parser and OtlpPath against files a real producer wrote: async-profiler 4.6's agent and
 * its {@code jfrconv -o otlp}, and async-profiler 4.1's agent. How each file was made, and its
 * sha256, is in {@code src/test/resources/real/README.md}. The expected values were decoded
 * independently, field by field against opentelemetry-proto's {@code profiles.proto}.
 */
class RealOtlpProfilesTest {

  static final Path REAL = Path.of("src", "test", "resources", "real");

  // --- the dictionary -------------------------------------------------------------------------

  @Test
  void everyTableStartsWithItsZeroValue() throws Exception {
    OtlpProfile.Dictionary dict = OtlpParser.parse(REAL.resolve("ap-cpu.otlp")).dictionary();
    assertEquals("", dict.stringTable().get(0));
    assertEquals(new OtlpProfile.Function("", "", "", 0), dict.functionTable().get(0));
    assertEquals(List.of(), dict.locationTable().get(0).lines());
    assertEquals(List.of(), dict.stackTable().get(0).locationIndices());
    assertEquals(1, dict.mappingTable().size());
    // The first real entries sit at index 1.
    assertEquals("java/lang/Integer.getChars", dict.functionTable().get(1).name());
    assertEquals(1, dict.locationTable().get(1).lines().get(0).functionIndex());
  }

  @Test
  void stacksResolveByAbsoluteIndex() throws Exception {
    List<Map<String, Object>> rows = run("ap-cpu.otlp", "samples");
    assertEquals(61, rows.size());
    // The first sample refers to stack 1, the second entry of the stack table.
    assertEquals(
        List.of(
            "java/lang/Integer.getChars",
            "java/lang/StringConcatHelper.prepend",
            "java/lang/StringConcatHelper.prepend",
            "java/lang/invoke/DirectMethodHandle$Holder.invokeStatic",
            "java/lang/invoke/LambdaForm$MH.0x00007f324c002c00.invoke",
            "java/lang/invoke/LambdaForm$MH.0x00007f324c003000.invoke",
            "java/lang/invoke/LambdaForm$MH.0x00007f324c004c00.invoke",
            "java/lang/invoke/Invokers$Holder.linkToTargetMethod",
            "RealProf.allocate",
            "RealProf.main"),
        names(rows.get(0)));
    for (Map<String, Object> row : rows) {
      assertTrue(!names(row).isEmpty(), "every sample has a stack: " + row);
    }
  }

  @Test
  void attributesResolveByAbsoluteIndex() throws Exception {
    // jfrconv writes thread.name per sample; the agent writes no attributes.
    assertEquals(
        java.util.Set.of(
            "[DestroyJavaVM tid=24735]",
            "[tid=24754]",
            "[C2 CompilerThre tid=24748]",
            "[C1 CompilerThre tid=24749]",
            "[sleeper tid=24753]"),
        run("conv-cpu.otlp", "samples").stream()
            .map(r -> r.get("thread.name"))
            .collect(java.util.stream.Collectors.toSet()));
  }

  // --- values ---------------------------------------------------------------------------------

  @Test
  void agentValuesAreSampleCounts() throws Exception {
    assertEquals(217, sum("ap-cpu.otlp", "perf_events"));
    assertEquals(903, sum("ap-wall.otlp", "wall"));
  }

  @Test
  void aSampleWithoutValuesCountsItsTimestamps() throws Exception {
    // jfrconv writes no values for a single-event recording: one timestamp per sample, 216 of
    // them, which is also the JFR's jdk.ExecutionSample count.
    assertEquals(216, sum("conv-cpu.otlp", "cpu"));
  }

  @Test
  void aSampleWithSeveralValuesCountsThemAll() throws Exception {
    // One value per timestamp: 513 timestamps over 85 records, 1,161 samples.
    assertEquals(1161, sum("conv-multi-cpu.otlp", "cpu"));
  }

  // --- the layout -----------------------------------------------------------------------------

  @Test
  void theOlderLayoutIsRefused() {
    IOException e =
        assertThrows(IOException.class, () -> OtlpParser.parse(REAL.resolve("ap41-cpu.otlp")));
    assertTrue(e.getMessage().contains("unsupported OTLP profiles layout"), e.getMessage());
    assertTrue(e.getMessage().contains("v1.7.0"), e.getMessage());
  }

  // --- helpers --------------------------------------------------------------------------------

  static List<Map<String, Object>> run(String file, String query) throws Exception {
    try (OtlpSession session = OtlpSession.open(REAL.resolve(file))) {
      return OtlpPathEvaluator.evaluate(session, OtlpPathParser.parse(query));
    }
  }

  private static long sum(String file, String field) throws Exception {
    long total = 0;
    for (Map<String, Object> row : run(file, "samples")) {
      total += ((Number) row.get(field)).longValue();
    }
    return total;
  }

  @SuppressWarnings("unchecked")
  private static List<String> names(Map<String, Object> row) {
    return ((List<Map<String, Object>>) row.get("stackTrace"))
        .stream().map(f -> (String) f.get("name")).toList();
  }
}

package io.jafar.testrecordings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reads the generated recordings back with Jafar's own parser. */
class TestRecordingGeneratorTest {

  @Test
  void generatesTheRecordingsTheSuitesRead(@TempDir Path dir) throws Exception {
    Path demo = dir.resolve("demo");
    Path parser = dir.resolve("parser");
    TestRecordingGenerator.main(new String[] {"tests", demo.toString(), parser.toString()});

    Map<String, Long> ap = countTypes(demo.resolve("test-ap.jfr"));
    assertTrue(ap.get("jdk.ExecutionSample") >= 5000, ap.toString());
    assertTrue(ap.get("jdk.CPULoad") > 0, ap.toString());
    assertTrue(ap.get("jdk.GCHeapSummary") > 0, ap.toString());
    assertTrue(ap.get("jdk.InitialSystemProperty") > 0, ap.toString());

    Map<String, Long> jfr = countTypes(demo.resolve("test-jfr.jfr"));
    assertTrue(jfr.get("jdk.ExecutionSample") > 0, jfr.toString());
    assertTrue(jfr.get("jdk.GCHeapSummary") > 0, jfr.toString());

    Map<String, Long> dd = countTypes(demo.resolve("test-dd.jfr"));
    assertTrue(dd.get("datadog.ExecutionSample") > 0, dd.toString());
    assertTrue(dd.get("jdk.ExecutionSample") > 0, dd.toString());
    assertTrue(dd.get("jdk.JVMInformation") > 0, dd.toString());

    for (String name : new String[] {"test-ap.jfr", "test-jfr.jfr", "test-dd.jfr"}) {
      assertTrue(Files.exists(TestRecordingGenerator.marker(parser.resolve(name))), name);
    }
    assertArrayEquals(
        Files.readAllBytes(demo.resolve("test-jfr.jfr")),
        Files.readAllBytes(parser.resolve("test-dd.jfr")),
        "parser-core's test-dd.jfr is a copy of test-jfr.jfr, as get_resources.sh makes it");
  }

  @Test
  void samplesHaveStacksAndJavaHomeIsAnInlineString(@TempDir Path dir) throws Exception {
    Path demo = dir.resolve("demo");
    TestRecordingGenerator.main(
        new String[] {"tests", demo.toString(), dir.resolve("parser").toString()});
    Path ap = demo.resolve("test-ap.jfr");

    AtomicLong framesSeen = new AtomicLong();
    Set<String> chunks = ConcurrentHashMap.newKeySet();
    Map<String, Boolean> heapSpaceInCp = new ConcurrentHashMap<>();
    try (UntypedJafarParser p = ParsingContext.create().newUntypedParser(ap)) {
      p.handle(
          (type, value, ctl) -> {
            chunks.add(String.valueOf(ctl.chunkInfo().chunkId()));
            if (type.getName().equals("jdk.ExecutionSample") && value.get("stackTrace") != null) {
              // Frames must be inline (as the JVM writes them), not constant-pool references:
              // jfr-mcp's frame extraction reads each frame as a map.
              Object st = ((io.jafar.parser.api.ComplexType) value.get("stackTrace")).getValue();
              Object frames = ((Map<?, ?>) st).get("frames");
              Object first =
                  java.lang.reflect.Array.get(
                      ((io.jafar.parser.api.ArrayType) frames).getArray(), 0);
              if (first instanceof Map) {
                framesSeen.incrementAndGet();
              }
            }
            if (type.getName().equals("jdk.GCHeapSummary")) {
              for (MetadataField f : type.getFields()) {
                if (f.getName().equals("heapSpace")) {
                  heapSpaceInCp.put("heapSpace", f.hasConstantPool());
                }
              }
            }
          });
      p.run();
    }
    assertTrue(framesSeen.get() > 0);
    assertEquals(1, chunks.size(), "test-ap.jfr is a single chunk, like the original");
    assertEquals(Boolean.FALSE, heapSpaceInCp.get("heapSpace"), "VirtualSpace is an inline struct");

    // The scrubber rewrites inline event strings in place, so java.home's value must be inline:
    // encoding byte 3 (UTF-8) followed by the length, inside the event region (before the first
    // constant pool, whose offset is at header byte 16).
    byte[] bytes = Files.readAllBytes(ap);
    long cpOffset = java.nio.ByteBuffer.wrap(bytes, 16, 8).getLong();
    byte[] value = "/usr/lib/jvm/java-21-openjdk-amd64".getBytes(StandardCharsets.UTF_8);
    int at = indexOf(bytes, value);
    assertTrue(at > 0 && at < cpOffset, "java.home value not inline in the event region: " + at);
    assertEquals(3, bytes[at - 2], "UTF-8 string encoding");
  }

  @Test
  void testDdIsACopyOfTheGivenSource(@TempDir Path dir) throws Exception {
    Path demo = dir.resolve("demo");
    Path source = dir.resolve("source.jfr");
    // Any valid recording will do as the source.
    TestRecordingGenerator.main(new String[] {"benchmark", source.toString(), "2", "500", "dd"});
    TestRecordingGenerator.main(
        new String[] {
          "tests", demo.toString(), dir.resolve("parser").toString(), source.toString()
        });
    assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(demo.resolve("test-dd.jfr")));
    assertTrue(Files.exists(TestRecordingGenerator.marker(demo.resolve("test-dd.jfr"))));
  }

  @Test
  void outputIsDeterministic(@TempDir Path dir) throws Exception {
    Path a = dir.resolve("a");
    Path b = dir.resolve("b");
    TestRecordingGenerator.main(new String[] {"tests", a.toString(), a.resolve("p").toString()});
    TestRecordingGenerator.main(new String[] {"tests", b.toString(), b.resolve("p").toString()});
    for (String name : new String[] {"test-ap.jfr", "test-jfr.jfr", "test-dd.jfr"}) {
      assertArrayEquals(
          Files.readAllBytes(a.resolve(name)), Files.readAllBytes(b.resolve(name)), name);
    }
  }

  @Test
  void keepsRealRecordingsAndReplacesInvalidFiles(@TempDir Path dir) throws Exception {
    Path demo = Files.createDirectories(dir.resolve("demo"));
    Path parser = dir.resolve("parser");
    // A real recording: valid JFR, no marker. Use a generated one, minus its marker.
    TestRecordingGenerator.main(new String[] {"tests", demo.toString(), parser.toString()});
    Files.delete(TestRecordingGenerator.marker(demo.resolve("test-dd.jfr")));
    byte[] real = Files.readAllBytes(demo.resolve("test-dd.jfr"));
    // A broken download: an HTML page saved under a recording's name, no marker.
    Files.write(demo.resolve("test-jfr.jfr"), "<!DOCTYPE html>".getBytes(StandardCharsets.UTF_8));

    TestRecordingGenerator.main(new String[] {"tests", demo.toString(), parser.toString()});

    assertArrayEquals(real, Files.readAllBytes(demo.resolve("test-dd.jfr")), "real file kept");
    assertFalse(Files.exists(TestRecordingGenerator.marker(demo.resolve("test-dd.jfr"))));
    assertTrue(TestRecordingGenerator.isJfr(demo.resolve("test-jfr.jfr")), "HTML replaced");
    assertTrue(Files.exists(TestRecordingGenerator.marker(demo.resolve("test-jfr.jfr"))));
  }

  @Test
  void benchmarkModeWritesTheRequestedChunks(@TempDir Path dir) throws Exception {
    Path out = dir.resolve("bench.jfr");
    TestRecordingGenerator.main(new String[] {"benchmark", out.toString(), "3", "1000", "dd"});
    Set<Long> chunks = ConcurrentHashMap.newKeySet();
    AtomicLong samples = new AtomicLong();
    try (UntypedJafarParser p = ParsingContext.create().newUntypedParser(out)) {
      p.handle(
          (type, value, ctl) -> {
            chunks.add(ctl.chunkInfo().chunkId());
            if (type.getName().equals("datadog.ExecutionSample")) {
              samples.incrementAndGet();
            }
          });
      p.run();
    }
    assertEquals(3, chunks.size());
    assertEquals(3000, samples.get());
  }

  private static Map<String, Long> countTypes(Path file) throws Exception {
    Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    try (UntypedJafarParser p = ParsingContext.create().newUntypedParser(file)) {
      p.handle(
          (MetadataClass type, Map<String, Object> value, io.jafar.parser.api.Control ctl) ->
              counts.computeIfAbsent(type.getName(), k -> new AtomicLong()).incrementAndGet());
      p.run();
    }
    Map<String, Long> result = new TreeMap<>();
    counts.forEach((k, v) -> result.put(k, v.get()));
    return result;
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}

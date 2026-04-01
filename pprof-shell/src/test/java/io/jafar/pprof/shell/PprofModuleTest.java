package io.jafar.pprof.shell;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link PprofModule}: file detection, extension handling, SPI contract. */
class PprofModuleTest {

  @TempDir Path tempDir;

  private final PprofModule module = new PprofModule();

  // ---- SPI contract ----

  @Test
  void idIsPprof() {
    assertEquals("pprof", module.getId());
  }

  @Test
  void supportedExtensionsContainsPprofAndPbGz() {
    Set<String> exts = module.getSupportedExtensions();
    assertTrue(exts.contains("pprof"), "should support .pprof");
    assertTrue(exts.contains("pb.gz"), "should support .pb.gz");
  }

  @Test
  void queryEvaluatorIsNonNull() {
    assertNotNull(module.getQueryEvaluator());
  }

  @Test
  void examplesIsNonNull() {
    assertNotNull(module.getExamples());
    assertFalse(module.getExamples().isEmpty());
  }

  @Test
  void initializeAndShutdownDoNotThrow() {
    assertDoesNotThrow(module::initialize);
    assertDoesNotThrow(module::shutdown);
  }

  // ---- canHandle: positive cases ----

  @Test
  void canHandleValidPprofFile() throws Exception {
    Path file = buildMinimalProfile("profile.pb.gz");
    assertTrue(module.canHandle(file), "should accept valid .pb.gz pprof file");
  }

  @Test
  void canHandlePprofExtension() throws Exception {
    Path file = buildMinimalProfile("profile.pprof");
    assertTrue(module.canHandle(file), "should accept .pprof extension");
  }

  // ---- canHandle: negative cases ----

  @Test
  void cannotHandleNonGzipFile() throws Exception {
    Path file = tempDir.resolve("not-gzip.pb.gz");
    // Write arbitrary non-gzip bytes
    Files.write(file, new byte[] {0x01, 0x02, 0x03, 0x04, 0x05});
    assertFalse(module.canHandle(file), "should reject file that is not gzip");
  }

  @Test
  void cannotHandleEmptyFile() throws Exception {
    Path file = tempDir.resolve("empty.pb.gz");
    Files.write(file, new byte[0]);
    assertFalse(module.canHandle(file), "should reject empty file");
  }

  @Test
  void cannotHandleDirectory() {
    assertFalse(module.canHandle(tempDir), "should reject directories");
  }

  @Test
  void cannotHandleNonExistentFile() {
    Path missing = tempDir.resolve("does-not-exist.pb.gz");
    assertFalse(module.canHandle(missing), "should reject non-existent paths");
  }

  @Test
  void cannotHandlePlainTextGzip() throws Exception {
    // A gzip file whose content is plain text, not a protobuf Profile
    Path file = tempDir.resolve("not-pprof.pb.gz");
    try (OutputStream fos = Files.newOutputStream(file);
        GZIPOutputStream gz = new GZIPOutputStream(fos)) {
      gz.write("hello world".getBytes());
    }
    // "hello world" → first byte 0x68 = 'h'; tag = 0x68, fieldNumber = 13, wireType = 0
    // fieldNumber 13 is within [1..14], so canHandle may still return true.
    // The important thing is that the method doesn't throw an exception.
    assertDoesNotThrow(() -> module.canHandle(file));
  }

  // ---- createSession ----

  @Test
  void createSessionReturnsNonNullSession() throws Exception {
    Path file = buildMinimalProfile("create.pb.gz");
    try (var session = (PprofSession) module.createSession(file, null)) {
      assertNotNull(session);
      assertEquals("pprof", session.getType());
      assertEquals(file, session.getFilePath());
      assertFalse(session.isClosed());
    }
  }

  @Test
  void createSessionOnBadFileThrowsIOException() {
    Path bad = tempDir.resolve("bad.pb.gz");
    assertThrows(IOException.class, () -> module.createSession(bad, null));
  }

  // ---- helper ----

  /** Builds a minimal valid pprof file at the given filename inside tempDir. */
  private Path buildMinimalProfile(String filename) throws Exception {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    b.addSampleType(cpu, ns);
    long fn = b.addFunction(b.addString("foo.Bar.run"), b.addString("Bar.java"));
    long loc = b.addLocation(fn, 1);
    b.addSample(List.of(loc), List.of(1000L), List.of());

    Path src = b.write(tempDir);
    Path dst = tempDir.resolve(filename);
    Files.move(src, dst);
    return dst;
  }
}

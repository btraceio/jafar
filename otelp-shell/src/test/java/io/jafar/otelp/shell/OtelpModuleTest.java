package io.jafar.otelp.shell;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link OtelpModule}: file detection, extension handling, SPI contract. */
class OtelpModuleTest {

  @TempDir Path tempDir;

  private final OtelpModule module = new OtelpModule();

  // ---- SPI contract ----

  @Test
  void idIsOtelp() {
    assertEquals("otelp", module.getId());
  }

  @Test
  void supportedExtensionsContainsOtlp() {
    Set<String> exts = module.getSupportedExtensions();
    assertTrue(exts.contains("otlp"), "should support .otlp");
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
  void canHandleValidOtlpFile() throws Exception {
    Path file = buildMinimalProfile("profile.otlp");
    assertTrue(module.canHandle(file), "should accept valid .otlp file");
  }

  // ---- canHandle: negative cases ----

  @Test
  void cannotHandleFileWithWrongFirstByte() throws Exception {
    Path file = tempDir.resolve("bad.dat");
    // First byte 0x00 is not a valid protobuf tag for field 1 or 2 LEN
    Files.write(file, new byte[] {0x00, 0x01, 0x02, 0x03, 0x04});
    assertFalse(module.canHandle(file), "should reject file with invalid first byte");
  }

  @Test
  void cannotHandleOtlpExtensionWithWrongContent() throws Exception {
    Path file = tempDir.resolve("truncated.otlp");
    Files.write(file, new byte[] {0x00, 0x01, 0x02, 0x03, 0x04});
    assertFalse(module.canHandle(file), "should reject .otlp file with wrong proto header");
  }

  @Test
  void cannotHandleEmptyFile() throws Exception {
    Path file = tempDir.resolve("empty.otlp");
    Files.write(file, new byte[0]);
    assertFalse(module.canHandle(file), "should reject empty file");
  }

  @Test
  void cannotHandleDirectory() {
    assertFalse(module.canHandle(tempDir), "should reject directories");
  }

  @Test
  void cannotHandleNonExistentFile() {
    Path missing = tempDir.resolve("does-not-exist.otlp");
    assertFalse(module.canHandle(missing), "should reject non-existent paths");
  }

  // ---- createSession ----

  @Test
  void createSessionReturnsNonNullSession() throws Exception {
    Path file = buildMinimalProfile("create.otlp");
    try (var session = (OtelpSession) module.createSession(file, null)) {
      assertNotNull(session);
      assertEquals("otelp", session.getType());
      assertEquals(file, session.getFilePath());
      assertFalse(session.isClosed());
    }
  }

  @Test
  void createSessionOnBadFileThrowsIOException() {
    Path bad = tempDir.resolve("bad.otlp");
    assertThrows(IOException.class, () -> module.createSession(bad, null));
  }

  // ---- helper ----

  private Path buildMinimalProfile(String filename) throws Exception {
    MinimalOtelpBuilder b = new MinimalOtelpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    int fn = b.addFunction(b.addString("foo.Bar.run"), b.addString("Bar.java"));
    int loc = b.addLocation(fn, 1);
    int stack = b.addStack(List.of(loc));
    b.addSample(stack, List.of(), List.of(1000L));

    Path src = b.write(tempDir);
    Path dst = tempDir.resolve(filename);
    Files.move(src, dst);
    return dst;
  }
}

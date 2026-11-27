package io.jafar.hdump.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #264: {@code gcroots/<type>} upper-cases the type and looks it up with {@code
 * GcRoot.Type.valueOf}. Under tr-TR {@code "jni_global".toUpperCase()} is {@code "JNİ_GLOBAL"}
 * (dotted capital I), which names no root type.
 */
class TurkishLocaleCaseMappingTest {

  @TempDir Path dir;

  private Locale saved;
  private Locale savedFormat;
  private Locale savedDisplay;

  @BeforeEach
  void save() {
    saved = Locale.getDefault();
    savedFormat = Locale.getDefault(Locale.Category.FORMAT);
    savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
  }

  @AfterEach
  void restore() {
    // setDefault(Locale) resets both categories; restore each as it was
    Locale.setDefault(saved);
    Locale.setDefault(Locale.Category.FORMAT, savedFormat);
    Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
  }

  @Test
  void gcRootTypeWithAnIResolvesUnderTurkish() throws Exception {
    Path dump = dir.resolve("roots.hprof");
    SyntheticHeapDumpGenerator.generateA3AllGcRoots(dump);
    Locale.setDefault(Locale.ROOT);
    Object expected = jniGlobalRoots(dump);
    assertTrue(((Number) expected).longValue() > 0, "the dump has a JNI global root");

    Locale.setDefault(Locale.of("tr", "TR"));

    assertEquals(expected, jniGlobalRoots(dump), "gcroots/jni_global | count under tr-TR");
  }

  private static Object jniGlobalRoots(Path dump) throws Exception {
    try (HeapSession session = HeapSession.open(dump)) {
      List<Map<String, Object>> rows =
          HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("gcroots/jni_global | count"));
      return rows.get(0).get("count");
    }
  }
}

package io.jafar.hdump.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #224: hdump_summary reported {@code "heapSize": "8,9 MB"} on a ru-RU host. Formats sizes
 * with the default locale set to ru-RU (decimal comma) and checks for dot decimals.
 */
class DefaultLocaleIndependenceTest {

  @TempDir Path dir;

  private static final Pattern COMMA_DECIMAL = Pattern.compile("\\d,\\d+\\s*(%|[KMGT]?B\\b)");

  private Locale saved;

  @BeforeEach
  void ruRu() {
    saved = Locale.getDefault();
    Locale.setDefault(Locale.of("ru", "RU"));
  }

  @AfterEach
  void restore() {
    Locale.setDefault(saved);
  }

  private Path heapOfAFewKilobytes() throws IOException {
    // 250 field-less objects of 24 bytes each: 6000 bytes, "5.9 KB", so the size has a decimal
    MinimalHprofBuilder b = new MinimalHprofBuilder().addClass(1, "com/example/Foo");
    for (long id = 1000; id < 1250; id++) {
      b.addInstance(id, 1);
    }
    return b.addGcRoot(1000).write(dir);
  }

  @Test
  void heapStatisticsUseDotDecimals() throws IOException {
    try (HeapSession s = HeapSession.open(heapOfAFewKilobytes())) {
      String size = String.valueOf(s.getStatistics().get("totalHeapSize"));
      // Was "5,9 KB" under ru-RU
      assertTrue(size.matches("\\d+\\.\\d KB"), size);
    }
  }

  @Test
  void heapReportUsesDotDecimals() throws IOException {
    try (HeapSession s = HeapSession.open(heapOfAFewKilobytes())) {
      List<HeapReportGenerator.Finding> findings = HeapReportGenerator.generate(s, null);
      assertFalse(findings.isEmpty());
      for (HeapReportGenerator.Finding f : findings) {
        for (String text : new String[] {f.title(), f.description(), f.action()}) {
          if (text != null) {
            assertFalse(COMMA_DECIMAL.matcher(text).find(), text);
          }
        }
      }
      for (String rendered :
          List.of(
              HeapReportGenerator.formatMarkdown(findings, s),
              HeapReportGenerator.formatText(findings, s))) {
        assertEquals(-1, indexOfCommaDecimal(rendered), rendered);
        // non-vacuous: the report does print a decimal size
        assertTrue(rendered.matches("(?s).*\\d\\.\\d KB.*"), rendered);
      }
    }
  }

  private static int indexOfCommaDecimal(String text) {
    var m = COMMA_DECIMAL.matcher(text);
    return m.find() ? m.start() : -1;
  }
}

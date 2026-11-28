package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.compileAndLoad;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #224: the processor filled {@code requireStorage(metadata, metaField, %d, ...)} with the
 * default locale, so under ar-EG it generated {@code requireStorage(metadata, metaField, ٠, ...)}
 * and javac failed with "illegal non-ASCII digit". Generates handlers for a pooled type with the
 * default locale set to ar-EG.
 */
class GeneratedSourceLocaleTest {

  private Locale saved;
  private Locale savedFormat;
  private Locale savedDisplay;

  @BeforeEach
  void arabicDigits() {
    saved = Locale.getDefault();
    savedFormat = Locale.getDefault(Locale.Category.FORMAT);
    savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
  }

  @AfterEach
  void restore() {
    Locale.setDefault(saved);
    Locale.setDefault(Locale.Category.FORMAT, savedFormat);
    Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
  }

  @Test
  void generatedSourceCompilesUnderALocaleWithNonAsciiDigits() throws Exception {
    // Sanity: this locale does format digits as non-ASCII, or the test proves nothing
    assertNotEquals("0", String.format("%d", 0), "ar-EG must format 0 with a non-ASCII digit");
    ClassLoader loader =
        compileAndLoad(
            source(
                "PByteHolder",
                "@JfrType(\"test.ByteHolder\")\npublic interface PByteHolder {\n"
                    + "  byte[] bs();\n  int after();\n}\n"),
            source(
                "PBytes",
                "@JfrType(\"test.Bytes\")\npublic interface PBytes {\n"
                    + "  long startTime();\n  byte b();\n  int next();\n  PByteHolder holder();\n}\n"));
    assertNotNull(loader.loadClass("test.PBytes"));
  }
}

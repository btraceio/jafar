package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #224: a timerange/asDateTime format= pattern with month or day names followed the default
 * locale ("13 Aug. 2024 Di." under de-DE). Patterns now use Locale.US on every machine.
 */
class TimeFormatsLocaleTest {

  private Locale saved;
  private Locale savedFormat;
  private Locale savedDisplay;

  @BeforeEach
  void german() {
    saved = Locale.getDefault();
    savedFormat = Locale.getDefault(Locale.Category.FORMAT);
    savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
    Locale.setDefault(Locale.GERMANY);
  }

  @AfterEach
  void restore() {
    Locale.setDefault(saved);
    Locale.setDefault(Locale.Category.FORMAT, savedFormat);
    Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
  }

  @Test
  void monthAndDayNamesDoNotFollowTheDefaultLocale() {
    String formatted =
        TimeFormats.getFormatter("dd MMM yyyy EEE").format(LocalDateTime.of(2024, 8, 13, 15, 4));
    assertEquals("13 Aug 2024 Tue", formatted);
  }

  @Test
  void fullNamesAreTheEnglishOnesAsBefore() {
    // With Locale.ROOT this was "Tue, 13 Aug 2024": full names must stay full
    String formatted =
        TimeFormats.getFormatter("EEEE, d MMMM yyyy G a")
            .format(LocalDateTime.of(2024, 8, 13, 15, 4));
    assertEquals("Tuesday, 13 August 2024 AD PM", formatted);
  }
}

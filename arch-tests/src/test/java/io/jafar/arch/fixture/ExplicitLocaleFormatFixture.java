package io.jafar.arch.fixture;

import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.Formatter;
import java.util.Locale;

/**
 * Negative fixture for rule (j) (see ArchRuleFireTest): the same formatting APIs as {@link
 * DefaultLocaleFormatFixture}, each with an explicit {@code Locale}. Rule (j) must report none of
 * these, so a rule that matches too broadly (ignoring parameter types, or every Formatter
 * constructor) fails the fire test.
 */
@SuppressWarnings({"unused", "ResultOfMethodCallIgnored"})
public final class ExplicitLocaleFormatFixture {

  public static void all(PrintWriter w, Object v) {
    String.format(Locale.ROOT, "%.1f", v);
    System.out.printf(Locale.ROOT, "%.1f", v);
    System.out.format(Locale.ROOT, "%.1f", v);
    w.printf(Locale.ROOT, "%.1f", v);
    new MessageFormat("{0}", Locale.ROOT);
    NumberFormat.getInstance(Locale.ROOT);
    NumberFormat.getPercentInstance(Locale.ROOT);
    new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.ROOT));
    new SimpleDateFormat("dd MMM yyyy", Locale.ROOT);
    DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ROOT);
    new Formatter(Locale.ROOT);
    new Formatter(new StringBuilder(), Locale.ROOT);
  }
}

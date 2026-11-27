package io.jafar.arch.fixture;

import java.io.Console;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Formatter;
import java.util.Locale;

/**
 * Negative fixture for rule (j) (see ArchRuleFireTest): the Locale-taking overload of every API
 * {@link DefaultLocaleFormatFixture} calls without one, where the JDK has one. Four listed groups
 * have none and are absent: {@code String.formatted}, the static {@code MessageFormat.format},
 * {@code DateFormat.getInstance()} and {@code DateTimeFormatter.ofLocalized*} (which take a locale
 * only through {@code withLocale}). Rule (j) must report none of these, so a rule that matches too
 * broadly fails the fire test.
 */
@SuppressWarnings({"unused", "ResultOfMethodCallIgnored"})
public final class ExplicitLocaleFormatFixture {

  public static void all(PrintWriter w, Console c, DateTimeFormatterBuilder b, Object v) {
    Locale l = Locale.ROOT;
    String.format(l, "%.1f", v);
    "Id".toLowerCase(l);
    "id".toUpperCase(l);
    System.out.printf(l, "%.1f", v);
    System.out.format(l, "%.1f", v);
    w.printf(l, "%.1f", v);
    w.format(l, "%.1f", v);
    c.printf(l, "%.1f", v);
    c.format(l, "%.1f", v);
    c.readLine(l, "%.1f", v);
    c.readPassword(l, "%.1f", v);
    new MessageFormat("{0}", l);
    NumberFormat.getInstance(l);
    NumberFormat.getNumberInstance(l);
    NumberFormat.getPercentInstance(l);
    NumberFormat.getIntegerInstance(l);
    NumberFormat.getCurrencyInstance(l);
    NumberFormat.getCompactNumberInstance(l, NumberFormat.Style.SHORT);
    new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(l));
    new DecimalFormatSymbols(l);
    DateFormat.getDateInstance(DateFormat.SHORT, l);
    DateFormat.getTimeInstance(DateFormat.SHORT, l);
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, l);
    new DateFormatSymbols(l);
    DateFormatSymbols.getInstance(l);
    new SimpleDateFormat("dd MMM yyyy", l);
    DateTimeFormatter.ofPattern("dd MMM yyyy", l);
    b.toFormatter(l);
    new Formatter(l);
    new Formatter(new StringBuilder(), l);
  }
}

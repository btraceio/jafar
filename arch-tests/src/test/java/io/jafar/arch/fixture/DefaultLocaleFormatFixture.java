package io.jafar.arch.fixture;

import java.io.Console;
import java.io.PrintStream;
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
import java.time.format.FormatStyle;
import java.util.Formatter;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Fire-test violators for rule (j) (see ArchRuleFireTest): every member of {@code
 * ArchitectureRules.DEFAULT_LOCALE_MEMBERS} is called here on its declared owner, plus a subtype
 * owner, a method reference, a constructor reference, a call inside a lambda and the {@code
 * Formatter} branch. Never imported as part of a real module; only the fire test places it there.
 * The fire test checks that every listed member is reported, so a member without a call here fails
 * it.
 */
@SuppressWarnings({"unused", "ResultOfMethodCallIgnored"})
public final class DefaultLocaleFormatFixture {

  /** A PrintStream subclass: the owner must match with its subtypes. */
  public static final class CustomStream extends PrintStream {
    public CustomStream() {
      super(System.out);
    }
  }

  /** Only forbidden calls here: the fire test requires every call in this method to be reported. */
  public static void all(PrintWriter w, Console c, DateTimeFormatterBuilder b, Object v) {
    String.format("%.1f", v);
    "%.1f".formatted(v);
    System.out.printf("%.1f", v);
    System.out.format("%.1f", v);
    w.printf("%.1f", v);
    w.format("%.1f", v);
    c.printf("%.1f", v);
    c.format("%.1f", v);
    c.readLine("%.1f", v);
    c.readPassword("%.1f", v);
    MessageFormat.format("{0}", v);
    new MessageFormat("{0}");
    NumberFormat.getInstance();
    NumberFormat.getNumberInstance();
    NumberFormat.getPercentInstance();
    NumberFormat.getIntegerInstance();
    NumberFormat.getCurrencyInstance();
    NumberFormat.getCompactNumberInstance();
    new DecimalFormat();
    new DecimalFormat("0.0");
    new DecimalFormatSymbols();
    DecimalFormatSymbols.getInstance();
    DateFormat.getInstance();
    DateFormat.getDateInstance();
    DateFormat.getDateInstance(DateFormat.SHORT);
    DateFormat.getTimeInstance();
    DateFormat.getTimeInstance(DateFormat.SHORT);
    DateFormat.getDateTimeInstance();
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
    new DateFormatSymbols();
    DateFormatSymbols.getInstance();
    new SimpleDateFormat();
    new SimpleDateFormat("dd MMM yyyy");
    DateTimeFormatter.ofPattern("dd MMM yyyy");
    DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT);
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT);
    DateTimeFormatter.ofLocalizedPattern("yMMM");
    b.toFormatter();
  }

  public static void printStreamSubclassPrintf(double v) {
    new CustomStream().printf("%.1f", v);
  }

  public static NumberFormat decimalFormatGetInstance() {
    return DecimalFormat.getInstance();
  }

  public static BiFunction<String, Object[], String> methodReference() {
    return String::format;
  }

  public static Supplier<DecimalFormat> constructorReference() {
    return DecimalFormat::new;
  }

  public static String inLambda(double v) {
    java.util.function.DoubleFunction<String> f = x -> String.format("%.1f", x);
    return f.apply(v);
  }

  public static String formatterNoLocale(double v) {
    return new Formatter().format("%.1f", v).toString();
  }
}

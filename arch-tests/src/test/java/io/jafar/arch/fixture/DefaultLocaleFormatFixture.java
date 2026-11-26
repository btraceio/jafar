package io.jafar.arch.fixture;

import java.io.PrintStream;
import java.text.DecimalFormat;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.Formatter;
import java.util.function.BiFunction;

/**
 * Fire-test violators for rule (j) (see ArchRuleFireTest): one method per form the rule forbids,
 * each formatting with the JVM default locale. Never imported as part of a real module; only the
 * fire test places it there. The method names are what the fire test looks for in the failure.
 */
@SuppressWarnings("unused")
public final class DefaultLocaleFormatFixture {

  /** A PrintStream subclass: the owner must match with its subtypes. */
  public static final class CustomStream extends PrintStream {
    public CustomStream() {
      super(System.out);
    }
  }

  public static String stringFormat(double v) {
    return String.format("%.1f%%", v);
  }

  public static String stringFormatted(double v) {
    return "%.1f".formatted(v);
  }

  public static String inLambda(double v) {
    java.util.function.DoubleFunction<String> f = x -> String.format("%.1f", x);
    return f.apply(v);
  }

  public static BiFunction<String, Object[], String> methodReference() {
    return String::format;
  }

  public static void printStreamPrintf(double v) {
    System.out.printf("%.1f", v);
  }

  public static void printStreamSubclassPrintf(double v) {
    new CustomStream().printf("%.1f", v);
  }

  public static void printWriterFormat(java.io.PrintWriter w, double v) {
    w.format("%.1f", v);
  }

  public static void consolePrintf(double v) {
    System.console().printf("%.1f", v);
  }

  public static String messageFormatStatic(double v) {
    return MessageFormat.format("{0}", v);
  }

  public static String messageFormatInstance(double v) {
    return new MessageFormat("{0}").format(new Object[] {v});
  }

  public static NumberFormat numberFormatFactory() {
    return NumberFormat.getPercentInstance();
  }

  public static NumberFormat decimalFormatGetInstance() {
    return DecimalFormat.getInstance();
  }

  public static NumberFormat compactNumberFactory() {
    return NumberFormat.getCompactNumberInstance();
  }

  public static DecimalFormat decimalFormatNoArgs() {
    return new DecimalFormat();
  }

  public static DecimalFormat decimalFormatPattern() {
    return new DecimalFormat("0.0");
  }

  public static String formatterNoLocale(double v) {
    return new Formatter().format("%.1f", v).toString();
  }

  public static DateTimeFormatter dateTimePattern() {
    return DateTimeFormatter.ofPattern("dd MMM yyyy");
  }

  public static SimpleDateFormat simpleDateFormat() {
    return new SimpleDateFormat("dd MMM yyyy");
  }
}

package io.jafar.arch.fixture;

/**
 * Fire-test violator for rule (j) (see ArchRuleFireTest): formats a number with the JVM default
 * locale, which on a ru-RU host prints "1,5". Never imported as part of a real module; only the
 * fire test places it there.
 */
public final class DefaultLocaleFormatFixture {
  public static String percent(double value) {
    return String.format("%.1f%%", value);
  }
}

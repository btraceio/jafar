package io.jafar.testsupport;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;

/**
 * Checks measured allocation bytes against the committed budget file {@code
 * gradle/allocation-budgets.properties} (written by the measurement tasks of the allocation gate).
 *
 * <p>File format:
 *
 * <ul>
 *   <li>{@code <module>.<entry>.<fixture>.jdk<major>=<bytes>} — e.g. {@code
 *       parser-core.untyped.tck-test.jfr.jdk21=…};
 *   <li>optional {@code <key>.tolerance=<bytes>} for a key (including its {@code jdk<major>}
 *       suffix); the default tolerance is 0;
 *   <li>an excluded cell is written {@code <key>=excluded: <reason>} and aborts the test (skips)
 *       with that reason instead of comparing;
 *   <li>{@code #} comment lines record the JDK build each value was measured on — allocation
 *       figures differ per JDK build, not only per major version (kb/a4-jdk8-alloc).
 * </ul>
 *
 * <p>No outcome is silent (R3: a fallback that hides a misconfiguration is a bug): a missing system
 * property, an unreadable file, a missing key and a malformed value each fail loudly, and every
 * check prints the measured figure next to the budget it was compared against, so the run log
 * always shows where the verdict came from.
 */
public final class AllocationBudgets {

  /**
   * System property holding the absolute path of the budget file. The Gradle allocation tasks set
   * it and make the file a task input, so a budget change re-runs the gate.
   */
  public static final String BUDGETS_PROPERTY = "jafar.alloc.budgets";

  /**
   * The committed file every missing-key message names, so the reader is told exactly where to add
   * the key — the same path every task registers under {@link #BUDGETS_PROPERTY}.
   */
  static final String BUDGETS_FILE_NAME = "gradle/allocation-budgets.properties";

  /** Marker value of an excluded cell; everything after it is the reason. */
  private static final String EXCLUDED_PREFIX = "excluded:";

  private AllocationBudgets() {}

  /**
   * Derives the per-JDK suffix of a budget key from the running JVM: {@code
   * java.specification.version} {@code 1.8} → {@code jdk8}, {@code 21} → {@code jdk21}. Pre-9
   * specification versions carry the major after the {@code 1.} prefix; from 9 on they are the
   * major version themselves.
   */
  public static String jdkKey() {
    return jdkKey(System.getProperty("java.specification.version"));
  }

  /** The derivation above, on an explicit specification version, so it can be tested directly. */
  static String jdkKey(String specVersion) {
    if (specVersion == null) {
      throw new AssertionError(
          "java.specification.version is not set — cannot derive the allocation budget key");
    }
    String major = specVersion.trim();
    if (major.startsWith("1.")) {
      major = major.substring(2);
    }
    int parsed;
    try {
      parsed = Integer.parseInt(major);
    } catch (NumberFormatException e) {
      parsed = -1;
    }
    if (parsed <= 0) {
      throw new AssertionError(
          "cannot derive a JDK key from java.specification.version=\"" + specVersion + "\"");
    }
    return "jdk" + parsed;
  }

  /**
   * Compares {@code measured} allocation bytes against the budget for {@code keyWithoutJdk} on the
   * running JDK. {@code keyWithoutJdk} excludes the {@code jdk<major>} suffix this method appends:
   * call it as {@code check("parser-core.untyped.tck-test.jfr", bytes)}.
   *
   * <p>Outcomes:
   *
   * <ul>
   *   <li>missing {@link #BUDGETS_PROPERTY}, unreadable file, missing key or malformed value →
   *       {@link AssertionError} (R3 — never a silent pass);
   *   <li>excluded cell ({@code <key>=excluded: <reason>}) → {@link Assumptions#abort(String)}: the
   *       test is skipped with the reason, not compared and not failed;
   *   <li>{@code measured > budget + tolerance} → fails, over budget by the excess;
   *   <li>{@code measured < budget - tolerance} → fails, under budget with the re-tighten hint (D2:
   *       a budget that no longer binds is re-tightened, not kept as headroom);
   *   <li>otherwise passes.
   * </ul>
   *
   * <p>Every outcome — including a pass — prints {@code alloc <key> measured=<n> budget=<b> tol=<t>
   * vm=<vendor> <version>}, so the log states where the compared figures came from (R3).
   */
  public static void check(String keyWithoutJdk, long measured) {
    String path = System.getProperty(BUDGETS_PROPERTY);
    if (path == null || path.trim().isEmpty()) {
      throw new AssertionError(
          "system property "
              + BUDGETS_PROPERTY
              + " is not set — the allocation budget file cannot be located. This is a"
              + " misconfiguration of the allocation test task (it must set the property and"
              + " make the file a task input), never a parser result; refusing rather than"
              + " guessing a budget (R3).");
    }
    Properties budgets = load(path);
    String key = keyWithoutJdk + "." + jdkKey();
    String vm = System.getProperty("java.vm.vendor") + " " + System.getProperty("java.vm.version");
    String value = budgets.getProperty(key);
    if (value == null) {
      report(key, measured, "<missing>", 0, vm);
      throw new AssertionError(
          "no budget for "
              + key
              + "; measured "
              + measured
              + " bytes on "
              + vm
              + "; add "
              + key
              + "="
              + measured
              + " to "
              + BUDGETS_FILE_NAME);
    }
    String trimmed = value.trim();
    if (trimmed.startsWith(EXCLUDED_PREFIX)) {
      String reason = trimmed.substring(EXCLUDED_PREFIX.length()).trim();
      report(key, measured, "excluded", 0, vm);
      Assumptions.abort(reason);
      return;
    }
    long budget = parseBytes(key, trimmed);
    long tolerance = parseTolerance(budgets, key);
    report(key, measured, Long.toString(budget), tolerance, vm);
    if (measured > budget + tolerance) {
      fail(
          "over budget by "
              + (measured - budget - tolerance)
              + " bytes (measured "
              + measured
              + ", budget "
              + budget
              + ", tolerance "
              + tolerance
              + ", vm "
              + vm
              + ")");
    }
    if (measured < budget - tolerance) {
      fail(
          "under budget by "
              + (budget - tolerance - measured)
              + " bytes — re-tighten: set "
              + key
              + "="
              + measured);
    }
  }

  private static Properties load(String path) {
    Properties budgets = new Properties();
    try (InputStream in = new FileInputStream(path)) {
      budgets.load(in);
    } catch (IOException e) {
      throw new AssertionError(
          "cannot read the allocation budget file at \""
              + path
              + "\" (from system property "
              + BUDGETS_PROPERTY
              + ")",
          e);
    }
    return budgets;
  }

  private static long parseBytes(String key, String value) {
    long parsed = parseLong(key, value);
    if (parsed < 0) {
      throw new AssertionError("negative budget for " + key + ": " + value);
    }
    return parsed;
  }

  /**
   * The optional {@code <key>.tolerance} entry of a budget: absent means 0 — the default keeps
   * budgets tight, and only a measurement-backed spread widens one (T12 records the spread).
   */
  private static long parseTolerance(Properties budgets, String key) {
    String value = budgets.getProperty(key + ".tolerance");
    if (value == null) {
      return 0;
    }
    return parseBytes(key + ".tolerance", value.trim());
  }

  private static long parseLong(String key, String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new AssertionError(
          "malformed allocation budget for " + key + ": \"" + value + "\" is not a byte count");
    }
  }

  /**
   * The always-printed audit line (R3). {@code budget} is a placeholder string so the missing and
   * excluded outcomes can state what was compared against ({@code <missing>} / {@code excluded})
   * instead of going silent.
   */
  private static void report(String key, long measured, String budget, long tolerance, String vm) {
    System.out.println(
        "alloc "
            + key
            + " measured="
            + measured
            + " budget="
            + budget
            + " tol="
            + tolerance
            + " vm="
            + vm);
  }
}

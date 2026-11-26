package io.jafar.testsupport.work;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Checks measured work counts against the committed budget file {@code
 * gradle/work-budgets.properties}, the ratchet file of the work-budget gate.
 *
 * <p>File format: {@code <module>.<entry>.<fixture>.<counter>=<count>}, e.g. {@code
 * parser-core.untyped.tck-test.jfr.events.decoded=67657}, with {@code #} comment lines. Counts are
 * exact: there is no tolerance, because the counters do not depend on timing, threads or the JDK
 * (the evidence is in {@code doc/plans/work-budgets.md}).
 *
 * <p>No outcome is silent (R3), as in {@code AllocationBudgets}: a missing system property, an
 * unreadable file, a missing key, a malformed value and a key for a counter that no longer exists
 * each fail loudly, and every check prints each measured count next to its budget. A count above
 * its budget fails; a count below it fails too, with "re-tighten", so the file always holds the
 * current counts and a regression cannot hide in slack left by an earlier improvement.
 *
 * <p>With the system property {@value #UPDATE_PROPERTY} set ({@code -Pjafar.work.update}), the
 * check rewrites the entry's lines from the measurement instead of comparing, keeping comments and
 * the order of the file.
 */
public final class WorkBudgets {

  /** System property holding the absolute path of the budget file; set by the workTest tasks. */
  public static final String BUDGETS_PROPERTY = "jafar.work.budgets";

  /** System property that turns a check into a rewrite of the entry's budget lines. */
  public static final String UPDATE_PROPERTY = "jafar.work.update";

  /** The committed file every message names. */
  static final String BUDGETS_FILE_NAME = "gradle/work-budgets.properties";

  private WorkBudgets() {}

  /**
   * Compares the {@code measured} counts of one parse against the budgets of {@code entry}.
   *
   * @param entry {@code <module>.<entry>.<fixture>}, e.g. {@code parser-core.untyped.tck-test.jfr}
   * @param measured counter name to count, as {@link WorkCounters#measure} returns it
   */
  public static void check(String entry, Map<String, Long> measured) {
    String path = System.getProperty(BUDGETS_PROPERTY);
    if (path == null || path.trim().isEmpty()) {
      throw new AssertionError(
          "system property "
              + BUDGETS_PROPERTY
              + " is not set — the work budget file cannot be located. This is a"
              + " misconfiguration of the workTest task (it must set the property and make the"
              + " file a task input), never a parser result; refusing rather than guessing a"
              + " budget (R3).");
    }
    File file = new File(path);
    if (Boolean.parseBoolean(System.getProperty(UPDATE_PROPERTY))) {
      update(file, entry, measured);
      return;
    }
    Properties budgets = load(file);
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Long> count : measured.entrySet()) {
      String key = entry + "." + count.getKey();
      long value = count.getValue();
      String budget = budgets.getProperty(key);
      report(key, value, budget == null ? "<missing>" : budget.trim());
      if (budget == null) {
        problems.add(
            "no budget for " + key + "; add " + key + "=" + value + " to " + BUDGETS_FILE_NAME);
        continue;
      }
      long expected;
      try {
        expected = Long.parseLong(budget.trim());
      } catch (NumberFormatException e) {
        problems.add(
            "malformed work budget for " + key + ": \"" + budget.trim() + "\" is not a count");
        continue;
      }
      if (expected < 0) {
        problems.add("negative work budget for " + key + ": " + expected);
      } else if (value > expected) {
        problems.add(
            key
                + " over budget by "
                + (value - expected)
                + " (measured "
                + value
                + ", budget "
                + expected
                + ")");
      } else if (value < expected) {
        problems.add(
            key
                + " under budget by "
                + (expected - value)
                + " — re-tighten: set "
                + key
                + "="
                + value);
      }
    }
    for (String key : budgets.stringPropertyNames()) {
      if (key.startsWith(entry + ".") && !measured.containsKey(key.substring(entry.length() + 1))) {
        problems.add(
            "stale work budget "
                + key
                + ": no such counter is measured for "
                + entry
                + "; remove the line from "
                + BUDGETS_FILE_NAME);
      }
    }
    if (!problems.isEmpty()) {
      throw new AssertionError(
          "work budget of "
              + entry
              + " ("
              + BUDGETS_FILE_NAME
              + "):\n  "
              + String.join("\n  ", problems)
              + "\nIf the change is intended, regenerate with -Pjafar.work.update; raising a"
              + " count needs a Work-Budget: <reason> statement in the PR body.");
    }
  }

  /** How many times {@link #measureAndCheck} parses; every parse must count the same work. */
  static final int RUNS = 3;

  /**
   * Measures {@code parse} {@value #RUNS} times, requires identical counts, and checks the last
   * against the budgets of {@code entry}. A counter that depended on thread timing, or on what an
   * earlier parse left in a cache, fails here instead of wobbling the budget.
   *
   * <p>Prints one audit line per cell: every run's counts, the derived {@code chunks.skipped} and
   * {@code events.skipped}, the processor count (the chunk pool's size follows it) and the VM.
   *
   * @return the checked counts
   */
  public static Map<String, Long> measureAndCheck(String entry, WorkCounters.Parse parse)
      throws Exception {
    List<Map<String, Long>> runs = new ArrayList<>();
    for (int i = 0; i < RUNS; i++) {
      runs.add(WorkCounters.measure(parse));
    }
    Map<String, Long> last = runs.get(RUNS - 1);
    System.out.println(
        "work "
            + entry
            + " runs="
            + runs
            + " chunks.skipped="
            + (last.get(WorkProbes.CHUNKS_STARTED) - last.get(WorkProbes.CHUNKS_READ))
            + " events.skipped="
            + (last.get(WorkProbes.EVENTS_READ) - last.get(WorkProbes.EVENTS_DECODED))
            + " availableProcessors="
            + Runtime.getRuntime().availableProcessors()
            + " vm="
            + System.getProperty("java.vm.vendor")
            + " "
            + System.getProperty("java.vm.version"));
    System.out.println("work probes applied: " + WorkCounters.applied());
    for (int i = 1; i < RUNS; i++) {
      if (!runs.get(i).equals(runs.get(0))) {
        throw new AssertionError(
            entry
                + ": parse "
                + (i + 1)
                + " counted different work from parse 1, so a counter is not deterministic"
                + " (thread timing, or a cache an earlier parse filled): "
                + runs.get(0)
                + " vs "
                + runs.get(i));
      }
    }
    check(entry, last);
    return last;
  }

  private static Properties load(File file) {
    Properties budgets = new Properties();
    try (InputStream in = new java.io.FileInputStream(file)) {
      budgets.load(in);
    } catch (IOException e) {
      throw new AssertionError(
          "cannot read the work budget file at \""
              + file
              + "\" (from system property "
              + BUDGETS_PROPERTY
              + ")",
          e);
    }
    return budgets;
  }

  /** The always-printed audit line (R3): where the verdict came from. */
  private static void report(String key, long measured, String budget) {
    System.out.println("work " + key + " measured=" + measured + " budget=" + budget);
  }

  /**
   * Rewrites the lines of {@code entry} from {@code measured}: an existing key gets the new count
   * in place, a counter without a key is added after the entry's last line (or at the end of the
   * file), and a key for a counter that is not measured is removed. Comments and other entries are
   * kept as they are. The file is locked while it is rewritten: parser-core's and parser-codegen's
   * workTest tasks may update it at the same time from two JVMs.
   */
  static void update(File file, String entry, Map<String, Long> measured) {
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
        FileChannel channel = raf.getChannel();
        FileLock ignored = channel.lock()) {
      ByteArrayOutputStream original = new ByteArrayOutputStream();
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      channel.position(0);
      while (channel.read(buffer) > 0) {
        buffer.flip();
        original.write(buffer.array(), 0, buffer.limit());
        buffer.clear();
      }
      String text = new String(original.toByteArray(), StandardCharsets.UTF_8);
      String updated = rewrite(text, entry, measured);
      byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
      channel.truncate(0);
      channel.position(0);
      channel.write(ByteBuffer.wrap(bytes));
      // confirm what was written parses as properties, before saying so
      new Properties().load(new ByteArrayInputStream(bytes));
    } catch (IOException e) {
      throw new AssertionError("cannot update the work budget file at \"" + file + "\"", e);
    }
    for (Map.Entry<String, Long> count : measured.entrySet()) {
      report(entry + "." + count.getKey(), count.getValue(), "updated");
    }
  }

  /** The pure text transformation of {@link #update}. */
  static String rewrite(String text, String entry, Map<String, Long> measured) {
    List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    boolean trailingNewline = text.endsWith("\n");
    if (trailingNewline) {
      lines.remove(lines.size() - 1);
    }
    Set<String> written = new LinkedHashSet<>();
    int lastEntryLine = -1;
    List<String> out = new ArrayList<>();
    for (String line : lines) {
      int eq = line.indexOf('=');
      String key = eq > 0 && !line.startsWith("#") ? line.substring(0, eq).trim() : null;
      if (key != null && key.startsWith(entry + ".")) {
        String counter = key.substring(entry.length() + 1);
        Long value = measured.get(counter);
        if (value == null || written.contains(counter)) {
          continue; // stale or duplicate: dropped
        }
        out.add(key + "=" + value);
        written.add(counter);
        lastEntryLine = out.size() - 1;
      } else {
        out.add(line);
      }
    }
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, Long> count : measured.entrySet()) {
      if (!written.contains(count.getKey())) {
        missing.add(entry + "." + count.getKey() + "=" + count.getValue());
      }
    }
    if (!missing.isEmpty()) {
      if (lastEntryLine >= 0) {
        out.addAll(lastEntryLine + 1, missing);
      } else {
        if (!out.isEmpty() && !out.get(out.size() - 1).trim().isEmpty()) {
          out.add("");
        }
        out.addAll(missing);
      }
    }
    String joined = String.join("\n", out);
    return trailingNewline || !missing.isEmpty() ? joined + "\n" : joined;
  }
}

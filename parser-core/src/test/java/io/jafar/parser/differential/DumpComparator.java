package io.jafar.parser.differential;

import io.jafar.testsupport.jfr.JfrCanonicalDump;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares the two canonical dumps of the Java/Go differential (safety-harness step 6; AC-6.2 and
 * AC-6.3): the Java dump written by {@link JfrCanonicalDump#writeEventDump} and the Go dump written
 * by {@code go-parser/internal/differential}. Both are one sorted {@code <type> k=v …} line per
 * event under a {@code # jafar-dump v1} header (R7: one line format, shared with the goldens).
 *
 * <h2>Validation order — the whole point of this class</h2>
 *
 * The headers are validated before a single event line is compared, so a stale, empty, swapped or
 * failed dump fails loudly with its cause instead of degrading into "all lines differ"
 * (kb/probe-pitfalls 1–2: a stale build produced one side from older code, and a discarded stderr
 * turned a failed dump run into an empty file). In order:
 *
 * <ol>
 *   <li>both dumps are present, are {@code jafar-dump v1}, carry the expected {@code side}, and
 *       their {@code events=} header equals their line count;
 *   <li>{@code error=} on one side only fails the compare outright (both sides erroring — a
 *       node-pool fixture that parses nowhere — passes: the recording is refused by both parsers,
 *       which is agreement);
 *   <li>the two {@code fixture-sha256} values are equal (different shas = one dump is stale);
 *   <li>the event totals and the per-type histograms match exactly (AC-6.2);
 *   <li>a line merge of the two sorted dumps: the first mismatch FAILS naming the event type, the
 *       field path and both lines (AC-6.3).
 * </ol>
 *
 * <h2>Allowlist</h2>
 *
 * {@code compare(javaDump, goDump, allowlist)} takes a committed, shrink-only allowlist file (see
 * {@code src/test/resources/differential/allowlist.txt}); it is applied here, in the comparator —
 * never in the dumpers — so the goldens and the differential keep one rendering (R7) and golden
 * files never churn because of a differential entry.
 *
 * <ul>
 *   <li>{@code equiv <type> <fieldPath> <rule>} normalizes both sides' rendering of that field
 *       before the compare; the values are still compared, so an equiv entry can never hide a real
 *       value divergence;
 *   <li>{@code diverge <type> <fieldPath> side=<java|go>} excludes the field from the compare on
 *       both sides and lists the entry in the output, naming the side at fault;
 *   <li>an entry that matches nothing FAILS with "stale allowlist entry" — an equiv entry whose
 *       rule normalized no line of a fixture that carries the entry's event type, or a diverge
 *       entry whose excluded content no longer differs between the sides on such a fixture — so
 *       fixing a divergence is forced to shrink the list. The allowlist is one corpus-level list
 *       while {@code compare} runs once per fixture, so an entry whose event type is absent from
 *       both dumps of a fixture is not judged by that fixture: each fixture exercises only the
 *       types it contains. An entry whose type appears in no fixture of the whole corpus is
 *       therefore silent — that limit is documented in the allowlist header (a typo'd type name
 *       must be caught in review, not by the gate).
 * </ul>
 *
 * <p>Every failure is an {@link AssertionError} with a message naming the cause, the side(s), the
 * numbers and the offending lines; {@link IOException} escapes only for real I/O trouble.
 */
public final class DumpComparator {

  private DumpComparator() {}

  /**
   * Compares the Java and Go dumps of one fixture under {@code allowlist}. Throws {@link
   * AssertionError} on any validation failure or on the first differing event line.
   */
  public static void compare(Path javaDump, Path goDump, Path allowlist) throws IOException {
    Allowlist rules = Allowlist.parse(allowlist);
    Dump javaSide = Dump.read(javaDump, "java");
    Dump goSide = Dump.read(goDump, "go");

    boolean javaErrored = javaSide.error != null;
    boolean goErrored = goSide.error != null;
    if (javaErrored != goErrored) {
      throw new AssertionError(
          "one-sided parse error — the differential is refused, it is never a value diff:\n"
              + "  java: "
              + javaSide.headerLine
              + "\n  go:   "
              + goSide.headerLine);
    }
    if (javaErrored) {
      // both parsers refused the same fixture: agreement, not a divergence (the node-pool cases)
      System.out.println(
          "differential: both sides report error= — the fixture does not parse on either side:");
      System.out.println("  java error: " + javaSide.error);
      System.out.println("  go   error: " + goSide.error);
      return;
    }
    if (!javaSide.sha.equals(goSide.sha)) {
      throw new AssertionError(
          "fixture-sha256 mismatch: java="
              + javaSide.sha
              + " go="
              + goSide.sha
              + " — the two dumps come from different fixture bytes, so one of them is a stale"
              + " dump (kb/probe-pitfalls 1). Re-dump both sides from the same recording.");
    }
    if ((javaSide.events == 0) != (goSide.events == 0)) {
      throw new AssertionError(
          "one-sided empty dump: java="
              + javaSide.events
              + " events, go="
              + goSide.events
              + " — an empty dump next to a non-empty one is a stale or failed run"
              + " (kb/probe-pitfalls 1-2), never a real difference");
    }
    if (javaSide.events != goSide.events) {
      throw new AssertionError(
          "event totals differ (AC-6.2): java=" + javaSide.events + ", go=" + goSide.events);
    }
    checkHistograms(javaSide, goSide);

    List<EventLine> javaLines = applyAllowlist(javaSide, rules, "java");
    List<EventLine> goLines = applyAllowlist(goSide, rules, "go");
    rules.reportAppliedEntries();
    rules.checkForStaleEntries();

    compareLines(javaLines, goLines, javaSide.sha, rules.entries.size());
  }

  // --- step 4: per-type histograms (AC-6.2) ----------------------------------------------

  private static void checkHistograms(Dump javaSide, Dump goSide) {
    Map<String, Long> javaCounts = histogram(javaSide);
    Map<String, Long> goCounts = histogram(goSide);
    TreeMap<String, Long> union = new TreeMap<String, Long>();
    union.putAll(javaCounts);
    union.putAll(goCounts);
    for (Map.Entry<String, Long> type : union.entrySet()) {
      long javaCount = javaCounts.containsKey(type.getKey()) ? javaCounts.get(type.getKey()) : 0L;
      long goCount = goCounts.containsKey(type.getKey()) ? goCounts.get(type.getKey()) : 0L;
      if (javaCount != goCount) {
        throw new AssertionError(
            "event histogram differs for type "
                + type.getKey()
                + ": java="
                + javaCount
                + ", go="
                + goCount
                + " (AC-6.2)");
      }
    }
  }

  private static Map<String, Long> histogram(Dump side) {
    Map<String, Long> counts = new TreeMap<String, Long>();
    for (EventLine line : side.lines) {
      counts.merge(line.type, 1L, Long::sum);
    }
    return counts;
  }

  // --- allowlist application -------------------------------------------------------------

  private static List<EventLine> applyAllowlist(Dump side, Allowlist rules, String sideName) {
    for (Allowlist.Entry entry : rules.entries) {
      for (EventLine line : side.lines) {
        if (!line.type.equals(entry.type)) {
          continue;
        }
        entry.noteTypeSeen(sideName);
        if (entry.kind == Allowlist.Entry.EQUIV) {
          List<Group> normalized = entry.rule.normalize(entry.fieldPath, line.groups);
          if (normalized != null) {
            line.groups = normalized;
            line.modified = true;
            entry.noteNormalized(sideName);
          }
        } else {
          List<Group> remaining = new ArrayList<Group>();
          List<Group> removed = new ArrayList<Group>();
          for (Group group : line.groups) {
            if (group.key.equals(entry.fieldPath) || group.key.startsWith(entry.fieldPath + ".")) {
              removed.add(group);
            } else {
              remaining.add(group);
            }
          }
          if (!removed.isEmpty()) {
            line.groups = remaining;
            line.modified = true;
            for (Group group : removed) {
              entry.noteExcluded(sideName, group.rendered());
            }
          }
        }
      }
    }
    return side.lines;
  }

  // --- step 5: the line merge (AC-6.3) --------------------------------------------------

  private static void compareLines(
      List<EventLine> javaLines, List<EventLine> goLines, String sha, int entries) {
    List<String> javaRendered = renderedSorted(javaLines);
    List<String> goRendered = renderedSorted(goLines);
    if (javaRendered.size() != goRendered.size()) {
      // unreachable: totals were checked before normalization, which never adds or drops events
      throw new AssertionError(
          "internal comparator inconsistency: "
              + javaRendered.size()
              + " java vs "
              + goRendered.size()
              + " go lines after normalization");
    }
    for (int i = 0; i < javaRendered.size(); i++) {
      String javaLine = javaRendered.get(i);
      String goLine = goRendered.get(i);
      if (!javaLine.equals(goLine)) {
        throw mismatch(i, javaLine, goLine);
      }
    }
    System.out.println(
        "differential: "
            + javaRendered.size()
            + " events, fixture-sha256="
            + sha
            + ", allowlist entries applied="
            + entries
            + " — java and go dumps are identical");
  }

  private static List<String> renderedSorted(List<EventLine> lines) {
    List<String> rendered = new ArrayList<String>(lines.size());
    for (EventLine line : lines) {
      rendered.add(line.render());
    }
    Collections.sort(rendered);
    return rendered;
  }

  private static AssertionError mismatch(int index, String javaLine, String goLine) {
    StringBuilder message = new StringBuilder();
    message.append("first differing dump line (sorted index ").append(index + 1).append("):\n");
    EventLine javaParsed = EventLine.parse(javaLine);
    EventLine goParsed = EventLine.parse(goLine);
    if (!javaParsed.type.equals(goParsed.type)) {
      message
          .append("  event types differ: java=")
          .append(javaParsed.type)
          .append(", go=")
          .append(goParsed.type)
          .append('\n');
    } else {
      message.append("  event type: ").append(javaParsed.type).append('\n');
      message
          .append("  field path: ")
          .append(firstDifferingField(javaParsed, goParsed))
          .append('\n');
    }
    message.append("  java line: ").append(javaLine).append('\n');
    message.append("  go   line: ").append(goLine);
    return new AssertionError(message.toString());
  }

  /**
   * The first key at which the two lines' field maps differ, with both renderings of the value — or
   * a layout note when every shared key agrees (the lines then differ in shape, not in a single
   * field).
   */
  private static String firstDifferingField(EventLine javaLine, EventLine goLine) {
    TreeMap<String, Group> union = new TreeMap<String, Group>();
    for (Group group : javaLine.groups) {
      union.put(group.key, group);
    }
    for (Group group : goLine.groups) {
      if (!union.containsKey(group.key)) {
        union.put(group.key, group);
      }
    }
    for (Map.Entry<String, Group> field : union.entrySet()) {
      String javaValue = javaLine.groupValue(field.getKey());
      String goValue = goLine.groupValue(field.getKey());
      if (!javaValue.equals(goValue)) {
        return field.getKey() + " (java=\"" + javaValue + "\", go=\"" + goValue + "\")";
      }
    }
    return "<no single differing field — the lines differ in field layout>";
  }

  // --- dump and header reading ----------------------------------------------------------

  /** One dump file: its validated header and its parsed event lines. */
  static final class Dump {

    final Path path;
    final String headerLine;
    String sha;
    String error;
    int events;
    List<EventLine> lines = new ArrayList<EventLine>();

    private Dump(Path path, String headerLine) {
      this.path = path;
      this.headerLine = headerLine;
    }

    static Dump read(Path file, String expectedSide) throws IOException {
      if (!Files.isRegularFile(file)) {
        throw new AssertionError(
            "dump file missing: " + file + " — the dumper never wrote it (never a pass; R3)");
      }
      List<String> raw = Files.readAllLines(file, StandardCharsets.UTF_8);
      if (raw.isEmpty()) {
        throw new AssertionError(
            "empty dump file "
                + file
                + " — no jafar-dump header: a failed dump run leaves an empty file"
                + " (kb/probe-pitfalls 2), it is never an empty recording");
      }
      String header = raw.get(0);
      if (!header.startsWith(JfrCanonicalDump.DUMP_FORMAT)) {
        throw new AssertionError("not a jafar-dump v1 header: " + header);
      }
      Dump dump = new Dump(file, header);
      List<String> tokens = split(header.substring(JfrCanonicalDump.DUMP_FORMAT.length()));
      // tokens[0] is empty: the header format marker is followed by exactly one space
      if (tokens.size() < 2 || !tokens.get(1).startsWith("side=")) {
        throw new AssertionError(
            "malformed jafar-dump header (expected 'side=…' first): " + header);
      }
      String side = valueAfter(tokens.get(1), "side=");
      if (!expectedSide.equals(side)) {
        throw new AssertionError(
            "wrong side in "
                + file
                + ": header says side="
                + side
                + ", expected "
                + expectedSide
                + " — the dumps are swapped or mislabeled: "
                + header);
      }
      String second = tokens.size() > 2 ? tokens.get(2) : "";
      if (second.startsWith("error=")) {
        // the whole rest of the header is the error message: it may contain spaces and '='
        StringBuilder error = new StringBuilder(second.substring("error=".length()));
        for (int i = 3; i < tokens.size(); i++) {
          error.append(' ').append(tokens.get(i));
        }
        dump.error = error.toString();
        List<String> body = raw.subList(1, raw.size());
        if (!body.isEmpty()) {
          throw new AssertionError(
              "malformed dump "
                  + file
                  + ": an error header must be the only line, but "
                  + body.size()
                  + " event lines follow it");
        }
        return dump;
      }
      // the success header is exactly three fields: side, fixture-sha256, events
      if (!second.startsWith("fixture-sha256=") || tokens.size() != 4) {
        throw new AssertionError(
            "malformed jafar-dump header (expected '"
                + JfrCanonicalDump.DUMP_FORMAT
                + " side=<s> fixture-sha256=<hex> events=<n>'): "
                + header);
      }
      dump.sha = valueAfter(second, "fixture-sha256=");
      if (!dump.sha.matches("[0-9a-fA-F]{64}")) {
        throw new AssertionError(
            "malformed fixture-sha256 in " + file + " (expected 64 hex digits): " + header);
      }
      String eventsToken = tokens.get(3);
      if (!eventsToken.startsWith("events=")) {
        throw new AssertionError("malformed jafar-dump header (expected events=<n>): " + header);
      }
      try {
        dump.events = Integer.parseInt(eventsToken.substring("events=".length()));
      } catch (NumberFormatException e) {
        throw new AssertionError("malformed events field in " + file + ": " + header);
      }
      if (dump.events < 0) {
        throw new AssertionError("malformed events field in " + file + ": " + header);
      }
      List<String> body = raw.subList(1, raw.size());
      List<EventLine> lines = new ArrayList<EventLine>(body.size());
      for (int i = 0; i < body.size(); i++) {
        if (body.get(i).trim().isEmpty()) {
          throw new AssertionError(
              "blank event line " + (i + 1) + " in dump " + file + " — the dump body is malformed");
        }
        lines.add(EventLine.parse(body.get(i)));
      }
      if (dump.events != lines.size()) {
        throw new AssertionError(
            "stale dump "
                + file
                + ": the header says events="
                + dump.events
                + " but the body holds "
                + lines.size()
                + " event lines (kb/probe-pitfalls 1) — the header and the body disagree, which is"
                + " never reported as 'all lines differ'");
      }
      dump.lines = lines;
      return dump;
    }

    private static String valueAfter(String token, String key) {
      return token.substring(key.length());
    }
  }

  // --- the allowlist --------------------------------------------------------------------

  /** The parsed allowlist file: its entries, and their staleness verdicts. */
  static final class Allowlist {

    final List<Entry> entries = new ArrayList<Entry>();

    static Allowlist parse(Path file) throws IOException {
      if (!Files.isRegularFile(file)) {
        throw new AssertionError("allowlist file missing: " + file + " (never a pass; R3)");
      }
      Allowlist out = new Allowlist();
      String pendingReason = null; // non-null only right after a '# reason:' line
      for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String line = raw.trim();
        if (line.isEmpty()) {
          pendingReason = null;
          continue;
        }
        if (line.startsWith("#")) {
          if (line.startsWith("# reason:")) {
            String reason = line.substring("# reason:".length()).trim();
            if (reason.isEmpty()) {
              throw new AssertionError("empty '# reason:' line in allowlist " + file + ": " + raw);
            }
            pendingReason = reason;
          } else {
            // a plain comment between entries: a reason must directly precede its entry
            pendingReason = null;
          }
          continue;
        }
        if (pendingReason == null) {
          throw new AssertionError(
              "allowlist entry without a '# reason:' line immediately before it: " + raw);
        }
        out.entries.add(Entry.parse(raw, line, pendingReason));
        pendingReason = null;
      }
      return out;
    }

    void reportAppliedEntries() {
      for (Entry entry : entries) {
        if (entry.kind == Entry.EQUIV) {
          if (entry.normalizedJava > 0 || entry.normalizedGo > 0) {
            System.out.println(
                "equiv: "
                    + entry.type
                    + " "
                    + entry.fieldPath
                    + " "
                    + entry.rule.name()
                    + " — normalized "
                    + entry.normalizedJava
                    + " java / "
                    + entry.normalizedGo
                    + " go lines; reason: "
                    + entry.reason);
          }
        } else if (!entry.stale()) {
          System.out.println(
              "diverge: "
                  + entry.type
                  + " "
                  + entry.fieldPath
                  + " side="
                  + entry.faultSide
                  + " — excluded from the compare ("
                  + entry.excludedJava.size()
                  + " java / "
                  + entry.excludedGo.size()
                  + " go lines carried the field); reason: "
                  + entry.reason);
        }
      }
    }

    void checkForStaleEntries() {
      for (Entry entry : entries) {
        if (entry.stale()) {
          throw new AssertionError(
              "stale allowlist entry: "
                  + entry.line
                  + "\n  reason: "
                  + entry.reason
                  + "\n  "
                  + (entry.kind == Entry.EQUIV
                      ? "the rule normalized no line of this fixture, which carries the type"
                      : "the field's content no longer differs between the sides")
                  + " — remove the entry (the allowlist is shrink-only)");
        }
      }
    }

    /** One allowlist entry plus the evidence this compare gathered for it. */
    static final class Entry {

      static final int EQUIV = 0;
      static final int DIVERGE = 1;

      final int kind;
      final String line;
      final String reason;
      final String type;
      final String fieldPath;
      final EquivRule rule; // null for DIVERGE
      final String faultSide; // null for EQUIV

      // evidence, gathered while applying the entry to both sides
      int normalizedJava;
      int normalizedGo;
      boolean typeSeenJava;
      boolean typeSeenGo;
      final List<String> excludedJava = new ArrayList<String>();
      final List<String> excludedGo = new ArrayList<String>();

      private Entry(
          int kind,
          String line,
          String reason,
          String type,
          String fieldPath,
          EquivRule rule,
          String faultSide) {
        this.kind = kind;
        this.line = line;
        this.reason = reason;
        this.type = type;
        this.fieldPath = fieldPath;
        this.rule = rule;
        this.faultSide = faultSide;
      }

      static Entry parse(String raw, String line, String reason) {
        String[] parts = line.split("\\s+");
        if (parts.length != 4) {
          throw malformed(line);
        }
        if ("equiv".equals(parts[0])) {
          EquivRule rule = EQUIV_RULES.get(parts[3]);
          if (rule == null) {
            throw new AssertionError(
                "unknown equiv rule '"
                    + parts[3]
                    + "' in allowlist entry: "
                    + line
                    + " (known rules: "
                    + String.join(", ", EQUIV_RULES.keySet())
                    + ")");
          }
          return new Entry(EQUIV, raw, reason, parts[1], parts[2], rule, null);
        }
        if ("diverge".equals(parts[0])) {
          if (!parts[3].startsWith("side=")
              || !("java".equals(parts[3].substring(5)) || "go".equals(parts[3].substring(5)))) {
            throw malformed(line);
          }
          return new Entry(DIVERGE, raw, reason, parts[1], parts[2], null, parts[3].substring(5));
        }
        throw malformed(line);
      }

      private static AssertionError malformed(String line) {
        return new AssertionError(
            "malformed allowlist entry (expected 'equiv <type> <fieldPath> <rule>' or"
                + " 'diverge <type> <fieldPath> side=<java|go>'): "
                + line);
      }

      void noteNormalized(String sideName) {
        if ("java".equals(sideName)) {
          normalizedJava++;
        } else {
          normalizedGo++;
        }
      }

      /** The fixture carries the entry's event type on this side: it judges the entry. */
      void noteTypeSeen(String sideName) {
        if ("java".equals(sideName)) {
          typeSeenJava = true;
        } else {
          typeSeenGo = true;
        }
      }

      void noteExcluded(String sideName, String rendering) {
        if ("java".equals(sideName)) {
          excludedJava.add(rendering);
        } else {
          excludedGo.add(rendering);
        }
      }

      /**
       * An entry is stale when the fixture that carries its event type no longer exhibits the
       * divergence it covers: an equiv entry whose rule normalized no line on either side, or a
       * diverge entry whose excluded content is the same on both sides (including absent from
       * both). An entry whose type is absent from both dumps is not judged by this fixture — the
       * allowlist is one corpus-level list and each fixture exercises only the types it contains
       * (an entry whose type appears in no corpus fixture at all is silent; see the class javadoc).
       */
      boolean stale() {
        if (!typeSeenJava && !typeSeenGo) {
          return false;
        }
        if (kind == EQUIV) {
          return normalizedJava == 0 && normalizedGo == 0;
        }
        List<String> javaSide = new ArrayList<String>(excludedJava);
        List<String> goSide = new ArrayList<String>(excludedGo);
        Collections.sort(javaSide);
        Collections.sort(goSide);
        return javaSide.equals(goSide);
      }
    }
  }

  // --- equiv rules ----------------------------------------------------------------------

  /** A named normalization, applied to both sides' groups of the entry's field. */
  interface EquivRule {

    /** The rule's allowlist name. */
    String name();

    /**
     * Normalizes {@code groups} for the entry's {@code fieldPath}: returns the new groups, or
     * {@code null} when the rule does not apply (nothing to normalize on this side).
     */
    List<Group> normalize(String fieldPath, List<Group> groups);
  }

  private static final Map<String, EquivRule> EQUIV_RULES = new TreeMap<String, EquivRule>();

  static {
    register(new UnwrapEmptyKey());
  }

  private static void register(EquivRule rule) {
    EQUIV_RULES.put(rule.name(), rule);
  }

  /**
   * Drops an empty inner key: a field key {@code <path>.} — the one-entry wrapper {@code
   * Values.resolvedDeep} keeps for a nameless pooled value (a primitive pool class with no fields,
   * e.g. a pooled {@code java.lang.String}, whose metadata declares no field to name the value) —
   * is renamed to {@code <path>}, so {@code x.=V} and {@code x=V} compare equal. The value itself
   * is untouched, so a genuine value divergence still fails the compare.
   */
  static final class UnwrapEmptyKey implements EquivRule {

    @Override
    public String name() {
      return "unwrap-empty-key";
    }

    @Override
    public List<Group> normalize(String fieldPath, List<Group> groups) {
      String wrapperKey = fieldPath + ".";
      for (int i = 0; i < groups.size(); i++) {
        if (groups.get(i).key.equals(wrapperKey)) {
          List<Group> out = new ArrayList<Group>(groups);
          out.set(i, groups.get(i).withKey(fieldPath));
          return out;
        }
      }
      return null;
    }
  }

  // --- event lines ---------------------------------------------------------------------

  /** One {@code <type> k=v …} line, parsed losslessly: an unmodified line re-renders verbatim. */
  static final class EventLine {

    final String raw;
    final String type; // the first token — the event's metadata type name
    final List<String> headTokens; // the type and any bare tokens before the first field
    List<Group> groups;
    boolean modified;

    private EventLine(String raw, String type, List<String> headTokens, List<Group> groups) {
      this.raw = raw;
      this.type = type;
      this.headTokens = headTokens;
      this.groups = groups;
    }

    static EventLine parse(String raw) {
      List<String> tokens = split(raw);
      if (tokens.isEmpty() || tokens.get(0).isEmpty()) {
        throw new AssertionError("malformed event line (no type token): " + raw);
      }
      List<String> head = new ArrayList<String>();
      head.add(tokens.get(0));
      List<Group> groups = new ArrayList<Group>();
      Group open = null;
      for (int i = 1; i < tokens.size(); i++) {
        String token = tokens.get(i);
        int eq = token.indexOf('=');
        if (eq > 0) {
          // a token before '=' holds a non-empty key: it starts a field
          List<String> fieldTokens = new ArrayList<String>();
          fieldTokens.add(token);
          open = new Group(token.substring(0, eq), fieldTokens);
          groups.add(open);
        } else if (open != null) {
          // a value may contain spaces (endpoint=GET /actuator/health): its continuation tokens
          // carry no '=', so they extend the open field. A value whose own token looks like
          // 'k=v' would be mis-split — the compare stays byte-level, so this can only surface as
          // a loud FAIL, never a silent pass
          open.tokens.add(token);
        } else {
          head.add(token);
        }
      }
      return new EventLine(raw, tokens.get(0), head, groups);
    }

    String render() {
      if (!modified) {
        return raw;
      }
      List<Group> sorted = new ArrayList<Group>(groups);
      Collections.sort(sorted, Group.BY_KEY);
      StringBuilder out = new StringBuilder(raw.length() + 16);
      out.append(String.join(" ", headTokens));
      for (Group group : sorted) {
        out.append(' ').append(group.rendered());
      }
      return out.toString();
    }

    String groupValue(String key) {
      for (Group group : groups) {
        if (group.key.equals(key)) {
          return group.value();
        }
      }
      return "<absent>";
    }
  }

  /** One field of a line: its key and its verbatim tokens ({@code k v …}, the first holds k=v). */
  static final class Group {

    static final Comparator<Group> BY_KEY =
        new Comparator<Group>() {
          @Override
          public int compare(Group left, Group right) {
            return left.key.compareTo(right.key);
          }
        };

    final String key;
    final List<String> tokens;

    Group(String key, List<String> tokens) {
      this.key = key;
      this.tokens = tokens;
    }

    String rendered() {
      return String.join(" ", tokens);
    }

    /** The raw value: everything after {@code key=} across the group's tokens. */
    String value() {
      return rendered().substring(key.length() + 1);
    }

    Group withKey(String newKey) {
      List<String> renamed = new ArrayList<String>(tokens);
      renamed.set(0, newKey + tokens.get(0).substring(key.length()));
      return new Group(newKey, renamed);
    }
  }

  /** Splits on spaces keeping empty tokens, so a join with ' ' reproduces the input verbatim. */
  private static List<String> split(String text) {
    List<String> tokens = new ArrayList<String>();
    int start = 0;
    int space;
    while ((space = text.indexOf(' ', start)) >= 0) {
      tokens.add(text.substring(start, space));
      start = space + 1;
    }
    tokens.add(text.substring(start));
    return tokens;
  }
}

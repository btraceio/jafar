package io.jafar.parser.differential;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.testsupport.jfr.JfrCanonicalDump;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Permanent fire tests for the differential comparator (AC-6.3): every way a compare can go wrong —
 * a seeded value change on either side, a count or histogram drift, a stale sha, an empty dump, a
 * one-sided error, a misused allowlist entry — fails here with a message naming the cause, and
 * never degrades into "all lines differ" (kb/probe-pitfalls 1-2). The cases run on synthetic dumps,
 * so they need neither Go nor a recording; the end-to-end run over the real corpus is
 * JavaGoDifferentialTest (T34).
 */
class DumpComparatorTest {

  /** Two distinct well-formed shas — the fixture identity the headers must agree on. */
  private static final String SHA =
      "1111111111111111111111111111111111111111111111111111111111111111";

  private static final String OTHER_SHA =
      "2222222222222222222222222222222222222222222222222222222222222222";

  private static final String T1 = "jdk.ExecutionSample a=1 endpoint=GET /actuator/health";
  private static final String T2 = "jdk.NativeMemoryUsage total=2 used=f:3ff8000000000000";

  @TempDir Path tmp;

  // --- helpers ---------------------------------------------------------------------------

  private String header(String side, String sha, int events) {
    return JfrCanonicalDump.DUMP_FORMAT
        + " side="
        + side
        + " fixture-sha256="
        + sha
        + " events="
        + events;
  }

  private String errorHeader(String side) {
    return JfrCanonicalDump.DUMP_FORMAT + " side=" + side + " error=boom";
  }

  private Path writeDump(String side, String sha, String... events) throws IOException {
    List<String> lines = new ArrayList<String>();
    lines.add(header(side, sha, events.length));
    lines.addAll(Arrays.asList(events));
    Path file = tmp.resolve(side + ".dump");
    Files.write(file, lines, StandardCharsets.UTF_8);
    return file;
  }

  private Path writeRawDump(String name, String... lines) throws IOException {
    Path file = tmp.resolve(name);
    Files.write(file, Arrays.asList(lines), StandardCharsets.UTF_8);
    return file;
  }

  private Path writeAllowlist(String... lines) throws IOException {
    Path file = tmp.resolve("allowlist.txt");
    Files.write(file, Arrays.asList(lines), StandardCharsets.UTF_8);
    return file;
  }

  /** An entryless allowlist — every no-entry case uses it. */
  private Path headerOnlyAllowlist() throws IOException {
    return writeAllowlist("# no entries (a header-only allowlist)");
  }

  /** The committed allowlist, from the test resources — the file the differential ships. */
  private Path committedAllowlist() throws IOException {
    URL resource = DumpComparatorTest.class.getResource("/differential/allowlist.txt");
    if (resource == null) {
      throw new AssertionError("missing test resource differential/allowlist.txt");
    }
    try {
      return Paths.get(resource.toURI());
    } catch (URISyntaxException e) {
      throw new AssertionError("bad resource uri", e);
    }
  }

  private static void assertFails(String what, ThrowingCompare compare, String... expected) {
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () -> {
              try {
                compare.run();
              } catch (IOException e) {
                throw new AssertionError("unexpected IOException in " + what, e);
              }
            },
            what);
    for (String fragment : expected) {
      assertTrue(
          failure.getMessage().contains(fragment),
          "expected the failure to name \"" + fragment + "\" but was: " + failure.getMessage());
    }
  }

  private interface ThrowingCompare {
    void run() throws Exception;
  }

  // --- the pass cases --------------------------------------------------------------------

  @Test
  void equalDumpsPass() throws Exception {
    DumpComparator.compare(
        writeDump("java", SHA, T1, T2),
        writeDump("go", SHA, T2, T1), // sorted by the comparator itself; order in the file is not
        headerOnlyAllowlist());
  }

  @Test
  void bothEmptyDumpsPass() throws Exception {
    DumpComparator.compare(writeDump("java", SHA), writeDump("go", SHA), headerOnlyAllowlist());
  }

  @Test
  void bothSidesErrorPasses() throws Exception {
    // a node-pool fixture the two parsers refuse identically is agreement, not a divergence
    DumpComparator.compare(
        writeRawDump("java.dump", errorHeader("java")),
        writeRawDump("go.dump", errorHeader("go")),
        headerOnlyAllowlist());
  }

  @Test
  void aHeaderOnlyAllowlistNeverFailsACompare() throws Exception {
    // an entryless allowlist must never fail a compare (guards the shipped file's syntax class)
    DumpComparator.compare(
        writeDump("java", SHA, T1), writeDump("go", SHA, T1), headerOnlyAllowlist());
  }

  // --- the committed allowlist — the root-caused datadog.Endpoint residue (T35, AC-6.4) -------

  /**
   * The verbatim recorded tck-test.jfr residue pair (T34): the java side keeps the ""-keyed wrapper
   * a pooled java.lang.String gets on the map path, the go side resolves the scalar.
   */
  private static final String ENDPOINT_JAVA =
      "datadog.Endpoint duration=0 endpoint.=GET /actuator/health"
          + " eventThread.javaName=http-nio-8080-exec-34 eventThread.javaThreadId=194"
          + " eventThread.osName=http-nio-8080-exec-34 eventThread.osThreadId=198"
          + " localRootSpanId=139072174018390321 operation.value=servlet.request stackTrace=null"
          + " startTime=1765998924993361313";

  private static final String ENDPOINT_GO =
      "datadog.Endpoint duration=0 endpoint=GET /actuator/health"
          + " eventThread.javaName=http-nio-8080-exec-34 eventThread.javaThreadId=194"
          + " eventThread.osName=http-nio-8080-exec-34 eventThread.osThreadId=198"
          + " localRootSpanId=139072174018390321 operation.value=servlet.request stackTrace=null"
          + " startTime=1765998924993361313";

  @Test
  void theCommittedAllowlistResolvesTheRecordedEndpointResidue() throws Exception {
    // the root-caused entry must make the real tck-test.jfr residue pair compare equal — the
    // differential's one committed equiv, decided in T35 with the proof in its '# reason:'
    DumpComparator.compare(
        writeDump("java", SHA, ENDPOINT_JAVA),
        writeDump("go", SHA, ENDPOINT_GO),
        committedAllowlist());
  }

  @Test
  void theCommittedAllowlistStillComparesTheResidueValue() throws Exception {
    // the committed equiv entry normalizes the wrapper key only: a differing endpoint VALUE must
    // still fail under the shipped file (R5 fire — the entry can never hide a value divergence)
    String changedGo =
        "datadog.Endpoint duration=0 endpoint=POST /other"
            + " eventThread.javaName=http-nio-8080-exec-34 eventThread.javaThreadId=194"
            + " eventThread.osName=http-nio-8080-exec-34 eventThread.osThreadId=198"
            + " localRootSpanId=139072174018390321 operation.value=servlet.request stackTrace=null"
            + " startTime=1765998924993361313";
    assertFails(
        "the committed equiv entry must not hide a value divergence",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, ENDPOINT_JAVA),
                writeDump("go", SHA, changedGo),
                committedAllowlist()),
        "event type: datadog.Endpoint",
        "field path: endpoint (java=\"GET /actuator/health\", go=\"POST /other\")");
  }

  @Test
  void theCommittedAllowlistCarriesExactlyTheRootCausedEntry() throws Exception {
    // the shipped file is pinned: exactly the datadog.Endpoint equiv, with the proof in its
    // reason — removing it, or adding a second entry, fails here before it can hide anything
    DumpComparator.Allowlist rules = DumpComparator.Allowlist.parse(committedAllowlist());
    if (rules.entries.size() != 1) {
      throw new AssertionError(
          "expected exactly one committed allowlist entry, found "
              + rules.entries.size()
              + " — the allowlist is shrink-only and T35 root-caused one residue");
    }
    DumpComparator.Allowlist.Entry entry = rules.entries.get(0);
    if (entry.kind != DumpComparator.Allowlist.Entry.EQUIV
        || !"datadog.Endpoint".equals(entry.type)
        || !"endpoint".equals(entry.fieldPath)
        || !"unwrap-empty-key".equals(entry.rule.name())) {
      throw new AssertionError(
          "the committed allowlist entry must be the root-caused"
              + " 'equiv datadog.Endpoint endpoint unwrap-empty-key', but is: "
              + entry.line);
    }
    assertTrue(
        entry.reason.contains("representational"),
        "the entry's '# reason:' must state the proof: " + entry.reason);
  }

  // --- header validation (kb/probe-pitfalls 1-2) ----------------------------------------

  @Test
  void aStaleShaFails() throws Exception {
    assertFails(
        "different fixture shas must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1), writeDump("go", OTHER_SHA, T1), headerOnlyAllowlist()),
        "fixture-sha256 mismatch",
        SHA,
        OTHER_SHA,
        "stale");
  }

  @Test
  void anEmptyDumpFileFailsLoudly() throws Exception {
    // a failed dump run with its stderr discarded leaves an EMPTY file: that is a failure, never
    // an "all lines differ" diff (kb/probe-pitfalls 2)
    Path empty = tmp.resolve("java.dump");
    Files.write(empty, new byte[0]);
    assertFails(
        "an empty dump file must fail",
        () -> DumpComparator.compare(empty, writeDump("go", SHA, T1), headerOnlyAllowlist()),
        "empty dump file",
        "no jafar-dump header");
  }

  @Test
  void aOneSidedEmptyDumpFails() throws Exception {
    assertFails(
        "an empty dump next to a non-empty one must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA), writeDump("go", SHA, T1), headerOnlyAllowlist()),
        "one-sided empty dump");
  }

  @Test
  void aOneSidedErrorFails() throws Exception {
    assertFails(
        "an error header on one side only must fail",
        () ->
            DumpComparator.compare(
                writeRawDump("java.dump", errorHeader("java")),
                writeDump("go", SHA, T1),
                headerOnlyAllowlist()),
        "one-sided parse error");
  }

  @Test
  void aHeaderCountDisagreeingWithTheBodyFails() throws Exception {
    assertFails(
        "events= disagreeing with the line count must fail",
        () ->
            DumpComparator.compare(
                writeRawDump(
                    "java.dump", header("java", SHA, 1), T1, T2), // header says 1, body holds 2
                writeDump("go", SHA, T1, T2),
                headerOnlyAllowlist()),
        "stale dump",
        "events=1",
        "2 event lines");
  }

  @Test
  void aSwappedSideFails() throws Exception {
    assertFails(
        "a mislabeled side must fail",
        () ->
            DumpComparator.compare(
                writeDump("go", SHA, T1), writeDump("java", SHA, T1), headerOnlyAllowlist()),
        "wrong side");
  }

  // --- totals and histograms (AC-6.2) ---------------------------------------------------

  @Test
  void anEventCountMismatchFails() throws Exception {
    assertFails(
        "differing event totals must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1), writeDump("go", SHA, T1, T2), headerOnlyAllowlist()),
        "event totals differ");
  }

  @Test
  void aHistogramMismatchFails() throws Exception {
    assertFails(
        "a per-type histogram drift must fail naming the type",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1, T1, T2),
                writeDump("go", SHA, T1, T2, T2),
                headerOnlyAllowlist()),
        "event histogram differs",
        "jdk.ExecutionSample",
        "java=2",
        "go=1");
  }

  // --- the line merge (AC-6.3) ----------------------------------------------------------

  @Test
  void aValueChangedOnTheJavaSideFailsNamingTypeFieldAndBothLines() throws Exception {
    String changed = "jdk.NativeMemoryUsage total=2 used=f:3ff80000000000ff";
    assertFails(
        "a seeded java-side value change must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1, changed),
                writeDump("go", SHA, T1, T2),
                headerOnlyAllowlist()),
        "event type: jdk.NativeMemoryUsage",
        "field path: used (java=\"f:3ff80000000000ff\", go=\"f:3ff8000000000000\")",
        "java line: " + changed,
        "go   line: " + T2);
  }

  @Test
  void aValueChangedOnTheGoSideFailsNamingTypeFieldAndBothLines() throws Exception {
    String changed = "jdk.ExecutionSample a=2 endpoint=GET /actuator/health";
    assertFails(
        "a seeded go-side value change must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1, T2),
                writeDump("go", SHA, changed, T2),
                headerOnlyAllowlist()),
        "event type: jdk.ExecutionSample",
        "field path: a (java=\"1\", go=\"2\")",
        "java line: " + T1,
        "go   line: " + changed);
  }

  // --- the allowlist --------------------------------------------------------------------

  @Test
  void anEquivEntryNormalizesBothSides() throws Exception {
    // the simple-type wrapper the java side keeps: 'endpoint.' vs go's flat 'endpoint', with a
    // space inside the value — exactly the datadog.Endpoint residue shape (T35 decides its entry)
    String javaLine = "datadog.Endpoint duration=0 endpoint.=GET /actuator/health";
    String goLine = "datadog.Endpoint duration=0 endpoint=GET /actuator/health";
    DumpComparator.compare(
        writeDump("java", SHA, javaLine),
        writeDump("go", SHA, goLine),
        writeAllowlist(
            "# reason: wrapper vs flat rendering of a simple-type constant — identical value",
            "equiv datadog.Endpoint endpoint unwrap-empty-key"));
  }

  @Test
  void anEquivEntryStillComparesTheNormalizedValues() throws Exception {
    // equiv normalizes the shape, never the value: differing values must still fail
    String javaLine = "datadog.Endpoint duration=0 endpoint.=GET /actuator/health";
    String goLine = "datadog.Endpoint duration=0 endpoint=POST /other";
    assertFails(
        "an equiv entry must not hide a value divergence",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, javaLine),
                writeDump("go", SHA, goLine),
                writeAllowlist(
                    "# reason: wrapper vs flat rendering of a simple-type constant",
                    "equiv datadog.Endpoint endpoint unwrap-empty-key")),
        "field path: endpoint (java=\"GET /actuator/health\", go=\"POST /other\")");
  }

  @Test
  void aDivergeEntryExcludesTheFieldAndListsItInTheOutput() throws Exception {
    String javaLine = "datadog.Endpoint duration=0 endpoint=GET /actuator/health";
    String goLine = "datadog.Endpoint duration=0 endpoint=POST /other";
    Path javaDump = writeDump("java", SHA, javaLine);
    Path goDump = writeDump("go", SHA, goLine);
    Path allowlist =
        writeAllowlist(
            "# reason: a real cross-parser divergence named for its faulty side",
            "diverge datadog.Endpoint endpoint side=go");

    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(captured, true, "UTF-8"));
    try {
      DumpComparator.compare(javaDump, goDump, allowlist);
    } finally {
      System.setOut(originalOut);
    }
    String output = captured.toString("UTF-8");
    assertTrue(output.contains("diverge: datadog.Endpoint endpoint side=go"), output);
    assertTrue(output.contains("excluded from the compare"), output);
    assertTrue(
        output.contains("reason: a real cross-parser divergence named for its faulty side"),
        output);
  }

  @Test
  void aStaleEquivEntryFails() throws Exception {
    assertFails(
        "an equiv entry that normalizes nothing must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1, T2),
                writeDump("go", SHA, T1, T2),
                writeAllowlist(
                    "# reason: no such field in any event of the corpus",
                    "equiv jdk.ExecutionSample zzz unwrap-empty-key")),
        "stale allowlist entry",
        "equiv jdk.ExecutionSample zzz unwrap-empty-key");
  }

  @Test
  void anEntryForATypeAbsentFromBothDumpsIsNotStale() throws Exception {
    // the allowlist is one corpus-level list while compare runs once per fixture: a fixture that
    // carries no event of the entry's type must not judge it (T35; the differential over
    // test-ap/test-jfr/node-pool-intact proved this fires without the fixture scoping)
    DumpComparator.compare(
        writeDump("java", SHA, T1, T2),
        writeDump("go", SHA, T1, T2),
        writeAllowlist(
            "# reason: representational only — the committed endpoint entry, judged by tck-test",
            "equiv datadog.Endpoint endpoint unwrap-empty-key"));
  }

  @Test
  void aStaleDivergeEntryFails() throws Exception {
    // the dumps AGREE at the diverged field: the exclusion is no longer needed, so it must shrink
    assertFails(
        "a diverge entry whose field no longer differs must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1, T2),
                writeDump("go", SHA, T1, T2),
                writeAllowlist(
                    "# reason: pinned a divergence that has since been fixed",
                    "diverge jdk.ExecutionSample a side=java")),
        "stale allowlist entry",
        "diverge jdk.ExecutionSample a side=java");
  }

  @Test
  void aDivergeEntryForAnAbsentFieldFails() throws Exception {
    assertFails(
        "a diverge entry for a field no line carries must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1, T2),
                writeDump("go", SHA, T1, T2),
                writeAllowlist(
                    "# reason: no such field in any event of the corpus",
                    "diverge jdk.ExecutionSample zzz side=go")),
        "stale allowlist entry",
        "no longer differs");
  }

  @Test
  void anEntryWithoutAReasonFails() throws Exception {
    assertFails(
        "an entry with no '# reason:' line before it must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1),
                writeDump("go", SHA, T1),
                writeAllowlist("diverge jdk.ExecutionSample a side=java")),
        "without a '# reason:'");
  }

  @Test
  void anUnknownEquivRuleFails() throws Exception {
    assertFails(
        "an equiv entry naming no registered rule must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1),
                writeDump("go", SHA, T1),
                writeAllowlist("# reason: typo", "equiv jdk.ExecutionSample a no-such-rule")),
        "unknown equiv rule",
        "no-such-rule");
  }

  @Test
  void aMalformedEntryFails() throws Exception {
    assertFails(
        "a malformed entry line must fail",
        () ->
            DumpComparator.compare(
                writeDump("java", SHA, T1),
                writeDump("go", SHA, T1),
                writeAllowlist("# reason: bad syntax", "diverge jdk.ExecutionSample a side=perl")),
        "malformed allowlist entry");
  }
}

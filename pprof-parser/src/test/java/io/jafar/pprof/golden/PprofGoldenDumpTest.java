package io.jafar.pprof.golden;

import io.jafar.pprof.MinimalPprofBuilder;
import io.jafar.pprof.api.PprofParser;
import io.jafar.pprof.api.PprofProfile;
import io.jafar.testsupport.DumpDigest;
import io.jafar.testsupport.GoldenFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pprof golden-value-dump gate (AC-4.1): two profiles built by {@link MinimalPprofBuilder} into
 * a temp dir — the default build of {@code PprofParserTest#roundTripBasicProfile} (T14's allocation
 * fixture) and a richer variant combining the existing {@code multipleSampleTypes}, {@code
 * multipleSamplesAndLocations} and {@code numericLabel} profiles — are rendered through the public
 * {@code io.jafar.pprof.api} model into sorted {@code <kind> k=v…} lines, and their value identity
 * compared against the committed goldens in {@code pprof-parser/src/test/golden}.
 *
 * <p>The dump is value-complete: value types, the string table, functions, locations with resolved
 * function names and file names, mappings, and samples whose stacks are resolved to {@code
 * function:line} sequences — plus the profile scalars (period, period type, duration, time). A
 * change to any decoded value — a sample value, a resolved string, a line number — fails here
 * naming the fixture and the regeneration command (AC-4.2).
 *
 * <p>Unlike hdump, the pprof fixture bytes ARE pinned: the builder writes a deterministic
 * gzip/protobuf stream (Java's {@code GZIPOutputStream} stamps no time into the header), so each
 * golden carries the fixture's sha256 and a builder change is reported as a fixture-bytes change,
 * distinct from a parser value change.
 */
class PprofGoldenDumpTest {

  /** The documented regeneration command, printed in every failure and stored in every golden. */
  private static final String REGEN_COMMAND =
      "./gradlew :pprof-parser:test --tests '*PprofGoldenDumpTest' -Pjafar.golden.update --rerun";

  private static final String DEFAULT_FIXTURE = "minimal.pb.gz";
  private static final String RICH_FIXTURE = "rich.pb.gz";

  @TempDir Path tempDir;

  /** The default build: one sample type, one function/location, one labelled sample (T14's). */
  @Test
  void defaultProfileGoldenMatches() throws IOException {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    int fnName = b.addString("com.example.Foo.bar");
    int filename = b.addString("Foo.java");
    int threadKey = b.addString("thread");
    int mainStr = b.addString("main");
    b.addSampleType(cpu, ns);
    b.setDurationNanos(5_000_000_000L);
    long fn1 = b.addFunction(fnName, filename);
    long loc1 = b.addLocation(fn1, 42);
    b.addSample(List.of(loc1), List.of(1_234_567L), List.of(new long[] {threadKey, mainStr, 0, 0}));
    verifyGolden(DEFAULT_FIXTURE, b.write(tempDir));
  }

  /**
   * The richer existing variant: two sample types, two functions over three locations, two samples
   * — one carrying a numeric label — plus duration and collection time.
   */
  @Test
  void richProfileGoldenMatches() throws IOException {
    MinimalPprofBuilder b = new MinimalPprofBuilder();
    int cpu = b.addString("cpu");
    int ns = b.addString("nanoseconds");
    int alloc = b.addString("alloc_objects");
    int count = b.addString("count");
    int goroutine = b.addString("goroutine");
    b.addSampleType(cpu, ns);
    b.addSampleType(alloc, count);
    long fn1 = b.addFunction(b.addString("a.A.foo"), b.addString("A.java"));
    long fn2 = b.addFunction(b.addString("b.B.bar"), b.addString("B.java"));
    long loc1 = b.addLocation(fn1, 10);
    long loc2 = b.addLocation(fn2, 20);
    long loc3 = b.addLocation(fn1, 11);
    b.addSample(List.of(loc1, loc2), List.of(1_000_000L, 3L), List.of());
    b.addSample(List.of(loc3, loc2), List.of(200L, 7L), List.of(new long[] {goroutine, 0, 42L, 0}));
    b.setDurationNanos(30_000_000_000L);
    b.setTimeNanos(1_700_000_000_000_000_000L);
    verifyGolden(RICH_FIXTURE, b.write(tempDir));
  }

  private static void verifyGolden(String fixture, Path file) throws IOException {
    PprofProfile.Profile profile = PprofParser.parse(file);
    List<String> dump = render(profile);
    String fixtureSha = DumpDigest.sha256Hex(Files.readAllBytes(file));
    GoldenFile.verify(
        fixture, DumpDigest.of(fixtureSha, dump, counts(dump)), () -> dump, REGEN_COMMAND);
  }

  /**
   * The full value dump, sorted: one line per profile identity, first token the kind. Counts are
   * derived from the rendered lines themselves, so the golden's {@code count.<kind>} lines can
   * never drift from what was actually dumped (R7).
   */
  private static List<String> render(PprofProfile.Profile profile) {
    Map<Long, PprofProfile.Function> functions = new HashMap<>();
    for (PprofProfile.Function fn : profile.functions()) {
      functions.put(fn.id(), fn);
    }
    Map<Long, PprofProfile.Location> locations = new HashMap<>();
    for (PprofProfile.Location loc : profile.locations()) {
      locations.put(loc.id(), loc);
    }
    List<String> lines = new ArrayList<>();
    lines.add(
        "profile durationNanos="
            + profile.durationNanos()
            + " timeNanos="
            + profile.timeNanos()
            + " period="
            + profile.period()
            + " periodType="
            + (profile.periodType() == null
                ? "none"
                : escape(profile.periodType().type()) + "/" + escape(profile.periodType().unit())));
    for (int i = 0; i < profile.sampleTypes().size(); i++) {
      PprofProfile.ValueType vt = profile.sampleTypes().get(i);
      lines.add(
          "value-type idx=" + i + " type=" + escape(vt.type()) + " unit=" + escape(vt.unit()));
    }
    for (int i = 0; i < profile.stringTable().size(); i++) {
      lines.add("string idx=" + i + " value=" + escape(profile.stringTable().get(i)));
    }
    for (PprofProfile.Function fn : profile.functions()) {
      lines.add(
          "function id="
              + fn.id()
              + " name="
              + escape(fn.name())
              + " systemName="
              + escape(fn.systemName())
              + " file="
              + escape(fn.filename())
              + " startLine="
              + fn.startLine());
    }
    for (PprofProfile.Location loc : profile.locations()) {
      StringBuilder line =
          new StringBuilder("location")
              .append(" id=")
              .append(loc.id())
              .append(" mappingId=")
              .append(loc.mappingId())
              .append(" address=")
              .append(loc.address())
              .append(" folded=")
              .append(loc.isFolded());
      for (int i = 0; i < loc.lines().size(); i++) {
        PprofProfile.Line l = loc.lines().get(i);
        PprofProfile.Function fn = functions.get(l.functionId());
        line.append(" line.").append(i).append(".fnId=").append(l.functionId());
        // The resolution the golden exists to pin: a line's function name and file come from the
        // function table, its line number from the location — a wrong join fails here.
        line.append(" line.")
            .append(i)
            .append(".fn=")
            .append(fn == null ? "none" : escape(fn.name()));
        line.append(" line.")
            .append(i)
            .append(".file=")
            .append(fn == null ? "none" : escape(fn.filename()));
        line.append(" line.").append(i).append(".num=").append(l.lineNumber());
      }
      lines.add(line.toString());
    }
    for (PprofProfile.Mapping m : profile.mappings()) {
      lines.add(
          "mapping id="
              + m.id()
              + " start="
              + m.memoryStart()
              + " limit="
              + m.memoryLimit()
              + " offset="
              + m.fileOffset()
              + " file="
              + escape(m.filename())
              + " hasFunctions="
              + m.hasFunctions());
    }
    for (int i = 0; i < profile.samples().size(); i++) {
      PprofProfile.Sample s = profile.samples().get(i);
      StringBuilder line =
          new StringBuilder("sample")
              .append(" idx=")
              .append(i)
              .append(" locs=")
              .append(joinLongs(s.locationIds()))
              .append(" stack=")
              .append(resolvedStack(s.locationIds(), locations, functions))
              .append(" values=")
              .append(joinLongs(s.values()));
      if (!s.labels().isEmpty()) {
        line.append(" labels=").append(labels(s.labels()));
      }
      lines.add(line.toString());
    }
    Collections.sort(lines);
    return lines;
  }

  /**
   * The sample's stack, resolved: each location renders as its inline chain of {@code
   * function:lineNumber} frames (joined with {@code |}), locations leaf-first separated by {@code
   * :}; an unresolvable id or function renders as {@code none}.
   */
  private static String resolvedStack(
      long[] locationIds,
      Map<Long, PprofProfile.Location> locations,
      Map<Long, PprofProfile.Function> functions) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < locationIds.length; i++) {
      if (i > 0) {
        out.append(':');
      }
      PprofProfile.Location loc = locations.get(locationIds[i]);
      if (loc == null || loc.lines().isEmpty()) {
        out.append("none");
        continue;
      }
      for (int j = 0; j < loc.lines().size(); j++) {
        if (j > 0) {
          out.append('|');
        }
        PprofProfile.Line l = loc.lines().get(j);
        PprofProfile.Function fn = functions.get(l.functionId());
        out.append(fn == null ? "none" : escape(fn.name())).append(':').append(l.lineNumber());
      }
    }
    return out.toString();
  }

  /**
   * The sample's labels, in decode order (label order is a decoded value — two profiles differing
   * only in label order must differ): a string label as {@code key=str}, a numeric label as {@code
   * key=num(unit)}.
   */
  private static String labels(List<PprofProfile.Label> labels) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < labels.size(); i++) {
      PprofProfile.Label label = labels.get(i);
      if (i > 0) {
        out.append(',');
      }
      out.append(escape(label.key())).append('=');
      if (label.str() != null) {
        out.append(escape(label.str()));
      } else {
        out.append(label.num());
        if (label.numUnit() != null) {
          out.append('(').append(escape(label.numUnit())).append(')');
        }
      }
    }
    return out.toString();
  }

  private static String joinLongs(long[] values) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        out.append(':');
      }
      out.append(values[i]);
    }
    return out.toString();
  }

  /**
   * Escapes the two characters that would split a dump line: a raw newline in a value would turn
   * one record into several lines and silently change the line count (the embedded-newline
   * pitfall).
   */
  private static String escape(String text) {
    return text.replace("\n", "\\n").replace("\r", "\\r");
  }

  /** kind → count, derived from the sorted dump lines (the kind is the first token). */
  private static Map<String, Long> counts(List<String> sortedLines) {
    Map<String, Long> counts = new TreeMap<>();
    for (String line : sortedLines) {
      String kind = line.split(" ", 2)[0];
      counts.merge(kind, 1L, Long::sum);
    }
    return counts;
  }
}

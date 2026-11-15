package io.jafar.otlp.golden;

import io.jafar.otlp.MinimalOtlpBuilder;
import io.jafar.otlp.api.OtlpParser;
import io.jafar.otlp.api.OtlpProfile;
import io.jafar.testsupport.DumpDigest;
import io.jafar.testsupport.GoldenFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The otlp golden-value-dump gate (AC-4.1): two profiles built by {@link MinimalOtlpBuilder} into a
 * temp dir — the default build of {@code OtlpParserTest#roundTripBasicProfile} (T15's allocation
 * fixture) and a richer variant combining the existing {@code multipleStacksAndSamples} and {@code
 * timeUnixNanoIsPreserved} profiles plus a sample attribute — are rendered through the public
 * {@code io.jafar.otlp.api} model into sorted {@code <kind> k=v…} lines, and their value identity
 * compared against the committed goldens in {@code otlp-parser/src/test/golden}.
 *
 * <p>The dump is value-complete over the whole model the plan names: profile scalars (sample type,
 * period, period type, time, duration), the entire string table, the attribute table with resolved
 * keys and units, functions, locations with their inline line chains joined to the function table,
 * stacks rendered both raw and resolved, and samples with resolved stack frames, resolved
 * attributes, values and timestamps. A change to any decoded value — a sample value, a resolved
 * string, a line number — fails here naming the fixture and the regeneration command (AC-4.2).
 *
 * <p>Like pprof (and unlike hdump, whose generator stamps a wall-clock timestamp), the otlp fixture
 * bytes ARE pinned: {@code MinimalOtlpBuilder} writes a raw protobuf stream with no gzip wrapper
 * and no embedded time — {@code System.nanoTime()} enters only the temp file's name, never its
 * bytes — so each golden carries the fixture's sha256 and a builder change is reported as a
 * fixture-bytes change, distinct from a parser value change.
 */
class OtlpGoldenDumpTest {

  /** The documented regeneration command, printed in every failure and stored in every golden. */
  private static final String REGEN_COMMAND =
      "./gradlew :otlp-parser:test --tests '*OtlpGoldenDumpTest' -Pjafar.golden.update --rerun";

  private static final String DEFAULT_FIXTURE = "minimal.otlp";
  private static final String RICH_FIXTURE = "rich.otlp";

  @TempDir Path tempDir;

  /** The default build: one sample type, one function/location, one attributed sample (T15's). */
  @Test
  void defaultProfileGoldenMatches() throws IOException {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    int typeIdx = b.addString("cpu");
    int unitIdx = b.addString("nanoseconds");
    int fnName = b.addString("com.example.Foo.bar");
    int filename = b.addString("Foo.java");
    int threadKey = b.addString("thread");
    b.setSampleType(typeIdx, unitIdx);
    b.setDurationNanos(5_000_000_000L);
    int fnIdx = b.addFunction(fnName, filename);
    int locIdx = b.addLocation(fnIdx, 42);
    int stackIdx = b.addStack(List.of(locIdx));
    int attrIdx = b.addAttribute(threadKey, "main");
    b.addSample(stackIdx, List.of(attrIdx), List.of(1_234_567L));
    verifyGolden(DEFAULT_FIXTURE, b.write(tempDir));
  }

  /**
   * The richer existing variant: two functions over two locations, two stacks — one two frames deep
   * — two samples (one carrying an attribute), plus collection time and duration.
   */
  @Test
  void richProfileGoldenMatches() throws IOException {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    int fn1 = b.addFunction(b.addString("a.A.foo"), b.addString("A.java"));
    int fn2 = b.addFunction(b.addString("b.B.bar"), b.addString("B.java"));
    int loc1 = b.addLocation(fn1, 10);
    int loc2 = b.addLocation(fn2, 20);
    int stack1 = b.addStack(List.of(loc1, loc2));
    int stack2 = b.addStack(List.of(loc2));
    int threadKey = b.addString("thread");
    int attrIdx = b.addAttribute(threadKey, "worker-1");
    b.addSample(stack1, List.of(), List.of(100L));
    b.addSample(stack2, List.of(attrIdx), List.of(200L));
    b.setTimeUnixNano(1_700_000_000_000_000_000L);
    b.setDurationNanos(30_000_000_000L);
    verifyGolden(RICH_FIXTURE, b.write(tempDir));
  }

  private static void verifyGolden(String fixture, Path file) throws IOException {
    OtlpProfile.ProfilesData data = OtlpParser.parse(file);
    List<String> dump = render(data);
    String fixtureSha = DumpDigest.sha256Hex(Files.readAllBytes(file));
    GoldenFile.verify(
        fixture, DumpDigest.of(fixtureSha, dump, counts(dump)), () -> dump, REGEN_COMMAND);
  }

  /**
   * The full value dump, sorted: one line per model identity, first token the kind. Counts are
   * derived from the rendered lines themselves, so the golden's {@code count.<kind>} lines can
   * never drift from what was actually dumped (R7).
   */
  private static List<String> render(OtlpProfile.ProfilesData data) {
    OtlpProfile.Dictionary dict = data.dictionary();
    List<OtlpProfile.Location> locations = dict.locationTable();
    List<OtlpProfile.Function> functions = dict.functionTable();
    List<OtlpProfile.Attribute> attributes = dict.attributeTable();
    List<OtlpProfile.Stack> stacks = dict.stackTable();
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < data.profiles().size(); i++) {
      OtlpProfile.Profile p = data.profiles().get(i);
      lines.add(
          "profile idx="
              + i
              + " sampleType="
              + valueType(p.sampleType())
              + " period="
              + p.period()
              + " periodType="
              + (p.periodType() == null ? "none" : valueType(p.periodType()))
              + " timeUnixNano="
              + p.timeUnixNano()
              + " durationNano="
              + p.durationNano());
    }
    for (int i = 0; i < dict.stringTable().size(); i++) {
      lines.add("string idx=" + i + " value=" + escape(dict.stringTable().get(i)));
    }
    for (int i = 0; i < attributes.size(); i++) {
      OtlpProfile.Attribute a = attributes.get(i);
      // The attribute value arrives as the string form of an AnyValue (string, bool, int, double);
      // the unit is "" when the wire carried no strindex — both are decoded values this pins.
      lines.add(
          "attribute idx="
              + i
              + " key="
              + escape(a.key())
              + " value="
              + escape(a.value())
              + " unit="
              + escape(a.unit()));
    }
    for (int i = 0; i < functions.size(); i++) {
      OtlpProfile.Function fn = functions.get(i);
      lines.add(
          "function idx="
              + i
              + " name="
              + escape(fn.name())
              + " systemName="
              + escape(fn.systemName())
              + " file="
              + escape(fn.filename())
              + " startLine="
              + fn.startLine());
    }
    for (int i = 0; i < locations.size(); i++) {
      OtlpProfile.Location loc = locations.get(i);
      StringBuilder line =
          new StringBuilder("location")
              .append(" idx=")
              .append(i)
              .append(" mappingIndex=")
              .append(loc.mappingIndex())
              .append(" mapping=")
              .append(mapping(loc.mappingIndex(), dict))
              .append(" address=")
              .append(loc.address());
      for (int j = 0; j < loc.lines().size(); j++) {
        OtlpProfile.Line l = loc.lines().get(j);
        OtlpProfile.Function fn = at(functions, l.functionIndex());
        line.append(" line.").append(j).append(".fnIdx=").append(l.functionIndex());
        // The resolution the golden exists to pin: a line's function name and file come from the
        // function table, its line and column numbers from the location — a wrong join fails here.
        line.append(" line.")
            .append(j)
            .append(".fn=")
            .append(fn == null ? "none" : escape(fn.name()));
        line.append(" line.")
            .append(j)
            .append(".file=")
            .append(fn == null ? "none" : escape(fn.filename()));
        line.append(" line.").append(j).append(".num=").append(l.line());
        line.append(" line.").append(j).append(".col=").append(l.column());
      }
      lines.add(line.toString());
    }
    for (int i = 0; i < stacks.size(); i++) {
      OtlpProfile.Stack stack = stacks.get(i);
      lines.add(
          "stack idx="
              + i
              + " locs="
              + joinInts(stack.locationIndices())
              + " frames="
              + frames(stack.locationIndices(), locations, functions));
    }
    for (int i = 0; i < data.profiles().size(); i++) {
      List<OtlpProfile.Sample> samples = data.profiles().get(i).samples();
      for (int j = 0; j < samples.size(); j++) {
        OtlpProfile.Sample s = samples.get(j);
        StringBuilder line =
            new StringBuilder("sample")
                .append(" profile=")
                .append(i)
                .append(" idx=")
                .append(j)
                .append(" stackIndex=")
                .append(s.stackIndex())
                .append(" frames=")
                .append(stackFrames(s.stackIndex(), stacks, locations, functions));
        if (!s.attributeIndices().isEmpty()) {
          line.append(" attrs=").append(attributes(s.attributeIndices(), attributes));
        }
        line.append(" values=").append(joinLongs(s.values()));
        if (!s.timestampsUnixNano().isEmpty()) {
          line.append(" timestamps=").append(joinLongs(s.timestampsUnixNano()));
        }
        lines.add(line.toString());
      }
    }
    Collections.sort(lines);
    return lines;
  }

  /**
   * Resolves a wire table index against an API list. Per the OTLP spec, index 0 in each table is a
   * zero/null sentinel, and the sentinel is not transmitted — the API lists hold exactly the
   * transmitted entries — so wire index {@code n} is API entry {@code n - 1}, and index 0 (and any
   * out-of-range index) resolves to nothing.
   */
  private static <T> T at(List<T> table, int wireIndex) {
    if (wireIndex <= 0 || wireIndex > table.size()) {
      return null;
    }
    return table.get(wireIndex - 1);
  }

  /** The sample's stack, resolved through the stack table to its frame sequence. */
  private static String stackFrames(
      int stackIndex,
      List<OtlpProfile.Stack> stacks,
      List<OtlpProfile.Location> locations,
      List<OtlpProfile.Function> functions) {
    OtlpProfile.Stack stack = at(stacks, stackIndex);
    if (stack == null) {
      return "none";
    }
    return frames(stack.locationIndices(), locations, functions);
  }

  /**
   * A stack's frames, resolved: each location renders as its inline chain of {@code
   * function:lineNumber} frames (joined with {@code |}), locations leaf-first separated by {@code
   * :}; an unresolvable index or function renders as {@code none}.
   */
  private static String frames(
      List<Integer> locationIndices,
      List<OtlpProfile.Location> locations,
      List<OtlpProfile.Function> functions) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < locationIndices.size(); i++) {
      if (i > 0) {
        out.append(':');
      }
      OtlpProfile.Location loc = at(locations, locationIndices.get(i));
      if (loc == null || loc.lines().isEmpty()) {
        out.append("none");
        continue;
      }
      for (int j = 0; j < loc.lines().size(); j++) {
        if (j > 0) {
          out.append('|');
        }
        OtlpProfile.Line l = loc.lines().get(j);
        OtlpProfile.Function fn = at(functions, l.functionIndex());
        out.append(fn == null ? "none" : escape(fn.name())).append(':').append(l.line());
      }
    }
    return out.toString();
  }

  /** The sample's attributes, in decode order, resolved to {@code key=value} pairs. */
  private static String attributes(
      List<Integer> attributeIndices, List<OtlpProfile.Attribute> attributes) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < attributeIndices.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      OtlpProfile.Attribute a = at(attributes, attributeIndices.get(i));
      if (a == null) {
        out.append("none");
      } else {
        out.append(escape(a.key())).append('=').append(escape(a.value()));
      }
    }
    return out.toString();
  }

  private static String mapping(int mappingIndex, OtlpProfile.Dictionary dict) {
    OtlpProfile.Mapping m = at(dict.mappingTable(), mappingIndex);
    return m == null ? "none" : escape(m.filename());
  }

  private static String valueType(OtlpProfile.ValueType vt) {
    return vt == null ? "none" : escape(vt.type()) + "/" + escape(vt.unit());
  }

  private static String joinInts(List<Integer> values) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        out.append(':');
      }
      out.append(values.get(i));
    }
    return out.toString();
  }

  private static String joinLongs(List<Long> values) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        out.append(':');
      }
      out.append(values.get(i));
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
    Map<String, Long> counts = new TreeMap<String, Long>();
    for (String line : sortedLines) {
      String kind = line.split(" ", 2)[0];
      counts.merge(kind, 1L, Long::sum);
    }
    return counts;
  }
}

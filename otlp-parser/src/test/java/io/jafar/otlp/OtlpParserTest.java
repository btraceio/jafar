package io.jafar.otlp;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.otlp.api.OtlpParser;
import io.jafar.otlp.api.OtlpProfile;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link OtlpParser}: round-trip encoding and parsing of synthetic profiles. */
class OtlpParserTest {

  @TempDir Path tempDir;

  @Test
  void roundTripBasicProfile() throws Exception {
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

    Path file = b.write(tempDir);
    OtlpProfile.ProfilesData data = OtlpParser.parse(file);

    // profiles
    assertEquals(1, data.profiles().size());
    OtlpProfile.Profile profile = data.profiles().get(0);

    // sample type
    assertNotNull(profile.sampleType());
    assertEquals("cpu", profile.sampleType().type());
    assertEquals("nanoseconds", profile.sampleType().unit());

    // duration
    assertEquals(5_000_000_000L, profile.durationNano());

    // samples
    assertEquals(1, profile.samples().size());
    OtlpProfile.Sample sample = profile.samples().get(0);
    assertEquals(1, sample.values().size());
    assertEquals(1_234_567L, sample.values().get(0));

    // stack resolution: every table holds its zero value at index 0, and indices are absolute
    OtlpProfile.Dictionary dict = data.dictionary();
    assertEquals(1, sample.stackIndex());
    assertEquals(2, dict.stackTable().size());
    assertEquals(List.of(), dict.stackTable().get(0).locationIndices());
    assertEquals(List.of(1), dict.stackTable().get(1).locationIndices());

    // function
    assertEquals(2, dict.functionTable().size());
    assertEquals("", dict.functionTable().get(0).name());
    assertEquals("com.example.Foo.bar", dict.functionTable().get(1).name());
    assertEquals("Foo.java", dict.functionTable().get(1).filename());

    // location
    assertEquals(2, dict.locationTable().size());
    assertEquals(List.of(), dict.locationTable().get(0).lines());
    assertEquals(1, dict.locationTable().get(1).lines().size());
    assertEquals(1, dict.locationTable().get(1).lines().get(0).functionIndex());
    assertEquals(42L, dict.locationTable().get(1).lines().get(0).line());

    // attribute
    assertEquals(List.of(1), sample.attributeIndices());
    assertEquals(2, dict.attributeTable().size());
    assertEquals("", dict.attributeTable().get(0).key());
    assertEquals("thread", dict.attributeTable().get(1).key());
    assertEquals("main", dict.attributeTable().get(1).value());
  }

  @Test
  void multipleStacksAndSamples() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    int typeIdx = b.addString("cpu");
    int unitIdx = b.addString("nanoseconds");
    b.setSampleType(typeIdx, unitIdx);

    int fn1 = b.addFunction(b.addString("a.A.foo"), b.addString("A.java"));
    int fn2 = b.addFunction(b.addString("b.B.bar"), b.addString("B.java"));
    int loc1 = b.addLocation(fn1, 10);
    int loc2 = b.addLocation(fn2, 20);

    int stack1 = b.addStack(List.of(loc1, loc2));
    int stack2 = b.addStack(List.of(loc2));

    b.addSample(stack1, List.of(), List.of(100L));
    b.addSample(stack2, List.of(), List.of(200L));

    Path file = b.write(tempDir);
    OtlpProfile.ProfilesData data = OtlpParser.parse(file);

    assertEquals(1, data.profiles().size());
    assertEquals(2, data.profiles().get(0).samples().size());
    // two of each, after each table's zero value
    assertEquals(3, data.dictionary().functionTable().size());
    assertEquals(3, data.dictionary().locationTable().size());
    assertEquals(3, data.dictionary().stackTable().size());
  }

  @Test
  void emptyStringAtIndexZero() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    Path file = b.write(tempDir);
    OtlpProfile.ProfilesData data = OtlpParser.parse(file);

    List<String> st = data.dictionary().stringTable();
    assertFalse(st.isEmpty());
    assertEquals("", st.get(0));
  }

  @Test
  void timeUnixNanoIsPreserved() throws Exception {
    long ts = 1_700_000_000_000_000_000L;
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("ns"));
    b.setTimeUnixNano(ts);

    int fn = b.addFunction(b.addString("main"), b.addString("main.go"));
    int loc = b.addLocation(fn, 1);
    int stack = b.addStack(List.of(loc));
    b.addSample(stack, List.of(), List.of(1L));

    Path file = b.write(tempDir);
    OtlpProfile.ProfilesData data = OtlpParser.parse(file);

    assertEquals(ts, data.profiles().get(0).timeUnixNano());
  }
}

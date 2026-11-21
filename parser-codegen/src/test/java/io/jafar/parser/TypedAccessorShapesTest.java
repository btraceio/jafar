package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrIgnore;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Interface methods {@code CodeGenerator} does not implement from a JFR field: default methods,
 * accessors of fields the recording lacks, accessors it cannot generate for their declared type,
 * and fields no accessor reads.
 */
public class TypedAccessorShapesTest {

  @JfrType("jdk.ExecutionSample")
  public interface WithDefault {
    long startTime();

    default String describe() {
      return "at " + startTime();
    }
  }

  @JfrType("jdk.ExecutionSample")
  public interface WithIgnoredDefault {
    @JfrField("startTime")
    long time();

    @JfrIgnore
    default long startTime() {
      return -42;
    }
  }

  @JfrType("jdk.ExecutionSample")
  public interface WithAbsentArray {
    long startTime();

    int[] noSuchField();
  }

  @JfrType("jdk.ExecutionSample")
  public interface WithNarrowing {
    // another field: a second accessor of startTime is checked when the class is generated
    String state();

    @JfrField("startTime")
    int start();
  }

  @JfrType("jdk.ExecutionSample")
  public interface WithRawInt {
    long startTime();

    @JfrField(value = "sampledThread", raw = true)
    int threadRef();
  }

  @JfrType("jdk.ExecutionSample")
  public interface Sample {
    Trace stackTrace();
  }

  @JfrType("jdk.types.StackTrace")
  public interface Trace {
    Frame[] frames();
  }

  /** {@code lineNumber} is also a field of jdk.types.StackFrame, which nothing here reads. */
  @JfrType("jdk.types.StackFrame")
  public interface Frame {
    @JfrField("bytecodeIndex")
    int lineNumber();
  }

  @JfrType("jdk.ExecutionSample")
  public interface PlainSample {
    PlainTrace stackTrace();
  }

  @JfrType("jdk.types.StackTrace")
  public interface PlainTrace {
    PlainFrame[] frames();
  }

  @JfrType("jdk.types.StackFrame")
  public interface PlainFrame {
    int bytecodeIndex();
  }

  private static Path recording() throws Exception {
    URL resource = TypedAccessorShapesTest.class.getClassLoader().getResource("test-ap.jfr");
    assertNotNull(resource, "test-ap.jfr is not on the test classpath");
    return Paths.get(resource.toURI());
  }

  /** {@code f} applied to every event, as rendered strings (a throw rendered as its message). */
  private static <T> List<String> each(Class<T> type, Function<T, Object> f) throws Exception {
    List<String> out = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(recording())) {
      p.handle(
          type,
          (e, ctl) -> {
            String v;
            try {
              v = String.valueOf(f.apply(e));
            } catch (RuntimeException | Error t) {
              v = "threw " + t;
            }
            out.add(v);
          });
      p.run();
    }
    assertFalse(out.isEmpty(), "no " + type.getSimpleName() + " events");
    List<String> sorted = new ArrayList<>(out);
    Collections.sort(sorted);
    return sorted;
  }

  @Test
  void aDefaultMethodIsInherited() throws Exception {
    // it was replaced by an empty method returning null
    for (String v : each(WithDefault.class, e -> e.describe() + "|at " + e.startTime())) {
      String[] parts = v.split("\\|");
      assertEquals(parts[1], parts[0], v);
    }
  }

  @Test
  void anIgnoredDefaultMethodIsInheritedNotZeroed() throws Exception {
    // the field it is named after is read by time(); it returned 0
    for (String v : each(WithIgnoredDefault.class, WithIgnoredDefault::startTime)) {
      assertEquals("-42", v);
    }
  }

  @Test
  void anArrayAccessorOfAnAbsentFieldReturnsNull() throws Exception {
    // generation failed for the whole parse: Unsupported type: [I
    for (String v : each(WithAbsentArray.class, e -> e.noSuchField() == null)) {
      assertEquals("true", v);
    }
    List<String> start = each(WithAbsentArray.class, e -> e.startTime());
    assertTrue(start.stream().anyMatch(s -> !s.equals("0")), "startTime still read");
  }

  @Test
  void anAccessorOfAnUnreturnableTypeThrowsNamingTheField() throws Exception {
    // it was left unimplemented: AbstractMethodError
    List<String> narrowing = each(WithNarrowing.class, WithNarrowing::start);
    assertEquals(
        "threw java.lang.IllegalStateException: "
            + WithNarrowing.class.getName()
            + ".start() declares int, which the runtime-generated class cannot return for JFR"
            + " field jdk.ExecutionSample.startTime (stored as long)",
        narrowing.get(0));
    assertEquals(1, new java.util.HashSet<>(narrowing).size());
    // the parse itself runs: the other accessor reads
    assertTrue(
        each(WithNarrowing.class, WithNarrowing::state).stream().anyMatch(s -> !s.equals("null")));

    List<String> raw = each(WithRawInt.class, WithRawInt::threadRef);
    assertTrue(
        raw.get(0)
            .startsWith(
                "threw java.lang.IllegalStateException: "
                    + WithRawInt.class.getName()
                    + ".threadRef() declares int"),
        raw.get(0));
    assertTrue(
        raw.get(0)
            .contains(
                "jdk.ExecutionSample.sampledThread (stored as a constant pool reference to java.lang.Thread)"),
        raw.get(0));
  }

  @Test
  void anAliasNamedAfterAnUnreadFieldReadsItsOwnField() throws Exception {
    // ClassFormatError (duplicate method name): a getter was generated for the unread lineNumber
    List<String> alias =
        each(
            Sample.class,
            e -> {
              StringBuilder sb = new StringBuilder();
              if (e.stackTrace() != null) {
                for (Frame f : e.stackTrace().frames()) {
                  sb.append(f.lineNumber()).append(',');
                }
              }
              return sb;
            });
    List<String> plain =
        each(
            PlainSample.class,
            e -> {
              StringBuilder sb = new StringBuilder();
              if (e.stackTrace() != null) {
                for (PlainFrame f : e.stackTrace().frames()) {
                  sb.append(f.bytecodeIndex()).append(',');
                }
              }
              return sb;
            });
    assertTrue(plain.stream().anyMatch(s -> !s.isEmpty()), "no frames read");
    assertEquals(plain, alias);
  }
}

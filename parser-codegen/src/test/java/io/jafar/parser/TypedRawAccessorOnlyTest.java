package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * An interface may read a pooled field only as its raw pool index, with no object accessor.
 *
 * <p>{@code CodeGenerator} kept the fields an interface reads in a {@code HashSet} of {@code
 * MetadataField}, whose equality is structural (type, pool, dimension) and ignores the field's
 * name. On {@code jdk.ExecutionSample}, {@code eventThread} and {@code sampledThread} are both
 * pooled {@code java.lang.Thread} references, so reading {@code sampledThread} counted {@code
 * eventThread} as read too, and the constructor stored it into {@code eventThread_ref}, which no
 * accessor had declared: {@code NoSuchFieldError}. With an object accessor next to the raw one the
 * other field's default accessor happened to declare it.
 */
public class TypedRawAccessorOnlyTest {

  @JfrType("jdk.ExecutionSample")
  public interface SampleThreadIndex {
    @JfrField(value = "sampledThread", raw = true)
    long sampledThreadIndex();

    long startTime();
  }

  @JfrType("jdk.ExecutionSample")
  public interface SampleThread {
    long startTime();

    ThreadRef sampledThread();

    @JfrField(value = "sampledThread", raw = true)
    long sampledThreadIndex();
  }

  /** Two raw accessors of one field, and still no object accessor. */
  @JfrType("jdk.ExecutionSample")
  public interface SampleTwoThreadIndices {
    @JfrField(value = "sampledThread", raw = true)
    long sampledThreadIndex();

    @JfrField(value = "sampledThread", raw = true)
    long threadIndex();

    long startTime();
  }

  @JfrType("java.lang.Thread")
  public interface ThreadRef {
    long javaThreadId();
  }

  @Test
  void aRawIndexAloneReadsTheSameIndexAsNextToTheObjectAccessor() throws Exception {
    Map<String, Long> withObject = indicesNextToTheObjectAccessor();
    Map<String, Long> alone = new ConcurrentHashMap<>();
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(recording())) {
      p.handle(
          SampleThreadIndex.class,
          (e, ctl) -> alone.merge(e.startTime() + " " + e.sampledThreadIndex(), 1L, Long::sum));
      p.run();
    }
    assertEquals(withObject, alone);
  }

  @Test
  void twoRawIndicesAloneAgreeAndReadTheSameIndexAsNextToTheObjectAccessor() throws Exception {
    Map<String, Long> withObject = indicesNextToTheObjectAccessor();
    Map<String, Long> twoRaw = new ConcurrentHashMap<>();
    AtomicLong mismatches = new AtomicLong();
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(recording())) {
      p.handle(
          SampleTwoThreadIndices.class,
          (e, ctl) -> {
            if (e.sampledThreadIndex() != e.threadIndex()) {
              mismatches.incrementAndGet();
            }
            twoRaw.merge(e.startTime() + " " + e.sampledThreadIndex(), 1L, Long::sum);
          });
      p.run();
    }
    assertEquals(0, mismatches.get(), "samples whose two raw indices differ");
    assertEquals(withObject, twoRaw);
  }

  /**
   * startTime + raw index -> how many samples, read next to the object accessor, which works on
   * every version; asserts the premise: threads resolve, behind more than one index.
   */
  private Map<String, Long> indicesNextToTheObjectAccessor() throws Exception {
    Map<String, Long> withObject = new ConcurrentHashMap<>();
    AtomicLong resolved = new AtomicLong();
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(recording())) {
      p.handle(
          SampleThread.class,
          (e, ctl) -> {
            if (e.sampledThread() != null) {
              resolved.incrementAndGet();
            }
            withObject.merge(e.startTime() + " " + e.sampledThreadIndex(), 1L, Long::sum);
          });
      p.run();
    }
    assertTrue(resolved.get() > 0, "no sample resolved its thread");
    assertTrue(
        withObject.keySet().stream().map(k -> k.substring(k.indexOf(' ') + 1)).distinct().count()
            > 1,
        "one index for every sample");
    return withObject;
  }

  private Path recording() throws Exception {
    URL resource = getClass().getClassLoader().getResource("test-ap.jfr");
    assertNotNull(resource, "test-ap.jfr is not on the test classpath");
    return Paths.get(resource.toURI());
  }
}

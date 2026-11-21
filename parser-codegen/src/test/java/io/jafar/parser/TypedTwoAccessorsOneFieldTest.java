package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.JafarSerializationException;
import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * Two accessors of one interface may read the same JFR field ({@code @JfrField} names it twice).
 *
 * <p>{@code CodeGenerator} emitted per-field members once per accessor, so defining the class
 * failed with {@code ClassFormatError}: for a pooled field the pool cache ({@code Duplicate field
 * name "name_cp"}, observed for {@code jdk.types.Method.name}), for an inline field the field
 * itself ({@code Duplicate field name "lineNumber"}). Each case below is pinned with an interface
 * graph of its own, so one broken case cannot hide behind another. Accessors are emitted in method
 * name order, so the two raw cases put the raw index first and last.
 */
public class TypedTwoAccessorsOneFieldTest {

  @JfrType("java.lang.Thread")
  public interface Thread {
    String javaName();

    long javaThreadId();
  }

  /** Two object accessors of the pooled sampledThread, and a raw index sorting before them. */
  @JfrType("jdk.ExecutionSample")
  public interface RawFirst {
    @JfrField("sampledThread")
    Thread thread();

    @JfrField("sampledThread")
    Thread sampledThread();

    @JfrField(value = "sampledThread", raw = true)
    long aThreadIndex();
  }

  /** The same, with the raw index sorting after the object accessors. */
  @JfrType("jdk.ExecutionSample")
  public interface RawLast {
    @JfrField("sampledThread")
    Thread thread();

    @JfrField("sampledThread")
    Thread sampledThread();

    @JfrField(value = "sampledThread", raw = true)
    long zThreadIndex();
  }

  @Test
  void twoObjectAccessorsAndARawIndexFirst() throws Exception {
    assertPooledThreadAccessors(
        RawFirst.class, (e, sink) -> sink.check(e.thread(), e.sampledThread(), e.aThreadIndex()));
  }

  @Test
  void twoObjectAccessorsAndARawIndexLast() throws Exception {
    assertPooledThreadAccessors(
        RawLast.class, (e, sink) -> sink.check(e.thread(), e.sampledThread(), e.zThreadIndex()));
  }

  /** What one sample's two thread accessors and raw index returned, checked against each other. */
  private static final class ThreadSink {
    final List<String> mismatches = Collections.synchronizedList(new ArrayList<>());
    final AtomicLong resolved = new AtomicLong();
    // the raw index is the pool key: one index, one thread (test-ap.jfr has one chunk)
    final Map<Long, Long> threadIdByIndex = new ConcurrentHashMap<>();

    void check(Thread a, Thread b, long index) {
      if (a == null && b == null) {
        return;
      }
      if (a == null
          || b == null
          || !a.javaName().equals(b.javaName())
          || a.javaThreadId() != b.javaThreadId()) {
        mismatches.add("thread " + describe(a) + " / " + describe(b));
        return;
      }
      resolved.incrementAndGet();
      Long earlier = threadIdByIndex.putIfAbsent(index, a.javaThreadId());
      if (earlier != null && earlier != a.javaThreadId()) {
        mismatches.add("index " + index + " is thread " + earlier + " and " + a.javaThreadId());
      }
    }
  }

  private <T> void assertPooledThreadAccessors(Class<T> iface, BiConsumer<T, ThreadSink> read)
      throws Exception {
    ThreadSink sink = new ThreadSink();
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(recording())) {
      p.handle(iface, (e, ctl) -> read.accept(e, sink));
      p.run();
    }
    // The premise: resolved threads, behind more than one pool index.
    assertTrue(sink.resolved.get() > 0, "no sample resolved its thread");
    assertTrue(sink.threadIdByIndex.size() > 1, "indices: " + sink.threadIdByIndex);
    assertNoMismatch(sink.mismatches);
  }

  @JfrType("jdk.ExecutionSample")
  public interface WithFrames {
    Frames stackTrace();
  }

  @JfrType("jdk.types.StackTrace")
  public interface Frames {
    Frame[] frames();
  }

  @JfrType("jdk.types.StackFrame")
  public interface Frame {
    Method method();

    int lineNumber();

    /** The same inline int field, widened. */
    @JfrField("lineNumber")
    long lineNumberWide();

    @JfrField("lineNumber")
    int line();
  }

  @JfrType("jdk.types.Method")
  public interface Method {
    String name();

    @JfrField("name")
    String methodName();
  }

  /**
   * jdk.types.Method.name is a pooled jdk.types.Symbol read as String; StackFrame.lineNumber is an
   * inline int read as int twice and as long.
   */
  @Test
  void twoStringAccessorsOfAPooledFieldAndThreeOfAnInlineField() throws Exception {
    List<String> mismatches = Collections.synchronizedList(new ArrayList<>());
    AtomicLong names = new AtomicLong();
    AtomicLong frames = new AtomicLong();
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(recording())) {
      p.handle(
          WithFrames.class,
          (e, ctl) -> {
            Frames st = e.stackTrace();
            if (st == null || st.frames() == null) {
              return;
            }
            for (Frame f : st.frames()) {
              frames.incrementAndGet();
              if (f.lineNumber() != f.line() || f.lineNumber() != f.lineNumberWide()) {
                mismatches.add(
                    "lineNumber " + f.lineNumber() + " / " + f.line() + " / " + f.lineNumberWide());
              }
              Method m = f.method();
              if (m == null || m.name() == null) {
                continue;
              }
              names.incrementAndGet();
              if (!m.name().equals(m.methodName())) {
                mismatches.add("method " + m.name() + " / " + m.methodName());
              }
            }
          });
      p.run();
    }
    assertTrue(frames.get() > 0 && names.get() > 0, "frames " + frames + ", names " + names);
    assertNoMismatch(mismatches);
  }

  @JfrType("jdk.ExecutionSample")
  public interface IncompatibleTypes {
    @JfrField("state")
    ThreadState state();

    @JfrField("state")
    String stateName();
  }

  @JfrType("jdk.types.ThreadState")
  public interface ThreadState {
    String name();
  }

  /**
   * One generated field cannot serve accessors of unrelated types: the parse fails naming them,
   * instead of defining a class whose second accessor fails when called.
   */
  @Test
  void accessorsOfOneFieldWithUnrelatedTypesFailNamingThem() throws Exception {
    Exception thrown =
        assertThrows(
            Exception.class,
            () -> {
              try (TypedJafarParser p = ParsingContext.create().newTypedParser(recording())) {
                p.handle(IncompatibleTypes.class, (e, ctl) -> {});
                p.run();
              }
            });
    Throwable t = thrown;
    while (t != null && !(t instanceof JafarSerializationException)) {
      t = t.getCause();
    }
    assertNotNull(t, "no JafarSerializationException in the cause chain of " + thrown);
    String message = String.valueOf(t.getMessage());
    assertTrue(
        message.contains("state()")
            && message.contains("stateName()")
            && message.contains("'state'"),
        message);
  }

  private Path recording() throws Exception {
    URL resource = getClass().getClassLoader().getResource("test-ap.jfr");
    assertNotNull(resource, "test-ap.jfr is not on the test classpath");
    return Paths.get(resource.toURI());
  }

  private static void assertNoMismatch(List<String> mismatches) {
    assertTrue(
        mismatches.isEmpty(),
        mismatches.size()
            + " mismatches, first: "
            + mismatches.subList(0, Math.min(5, mismatches.size())));
  }

  private static String describe(Thread t) {
    return t == null ? "null" : t.javaName() + "#" + t.javaThreadId();
  }
}

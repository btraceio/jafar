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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Two accessors of one interface may read the same JFR field ({@code @JfrField} names it twice).
 *
 * <p>For a pooled field, {@code CodeGenerator} emitted the field's pool cache ({@code <field>_cp}
 * and {@code <field>_cp$get}) once per accessor, so defining the class failed: {@code
 * ClassFormatError: Duplicate field name "sampledThread_cp"}. A raw accessor ({@code raw = true},
 * the pool index) next to an object accessor emits no cache of its own and already worked.
 */
public class TypedTwoAccessorsOneFieldTest {

  @JfrType("jdk.ExecutionSample")
  public interface Sample {
    @JfrField("sampledThread")
    Thread thread();

    @JfrField("sampledThread")
    Thread sampledThread();

    @JfrField(value = "sampledThread", raw = true)
    long sampledThreadRef();

    StackTrace stackTrace();
  }

  @JfrType("java.lang.Thread")
  public interface Thread {
    String javaName();

    long javaThreadId();
  }

  @JfrType("jdk.types.StackTrace")
  public interface StackTrace {
    Frame[] frames();
  }

  @JfrType("jdk.types.StackFrame")
  public interface Frame {
    Method method();
  }

  @JfrType("jdk.types.Method")
  public interface Method {
    String name();

    @JfrField("name")
    String methodName();
  }

  @Test
  void twoAccessorsOfOnePooledFieldReadTheSameValue() throws Exception {
    URL resource = getClass().getClassLoader().getResource("test-ap.jfr");
    assertNotNull(resource, "test-ap.jfr is not on the test classpath");
    Path jfr = Paths.get(resource.toURI());

    List<String> mismatches = Collections.synchronizedList(new ArrayList<>());
    long[] seen = new long[3]; // samples, threads, frames
    try (TypedJafarParser p = ParsingContext.create().newTypedParser(jfr)) {
      p.handle(
          Sample.class,
          (e, ctl) -> {
            synchronized (seen) {
              seen[0]++;
            }
            Thread a = e.thread();
            Thread b = e.sampledThread();
            if (a != null || b != null) {
              synchronized (seen) {
                seen[1]++;
              }
              if (a == null
                  || b == null
                  || !a.javaName().equals(b.javaName())
                  || a.javaThreadId() != b.javaThreadId()) {
                mismatches.add("thread " + describe(a) + " / " + describe(b));
              }
            }
            if (e.sampledThreadRef() == 0 && a != null) {
              mismatches.add("raw index 0 for a resolved thread " + describe(a));
            }
            StackTrace st = e.stackTrace();
            if (st != null && st.frames() != null) {
              for (Frame f : st.frames()) {
                Method m = f.method();
                if (m == null) {
                  continue;
                }
                synchronized (seen) {
                  seen[2]++;
                }
                if (!String.valueOf(m.name()).equals(String.valueOf(m.methodName()))) {
                  mismatches.add("method " + m.name() + " / " + m.methodName());
                }
              }
            }
          });
      p.run();
    }

    // The premise: both pooled fields are present and resolved, so both accessor pairs ran.
    assertTrue(
        seen[0] > 0 && seen[1] > 0 && seen[2] > 0,
        "samples/threads/frames: " + seen[0] + "/" + seen[1] + "/" + seen[2]);
    assertEquals(List.of(), mismatches.subList(0, Math.min(5, mismatches.size())));
  }

  private static String describe(Thread t) {
    return t == null ? "null" : t.javaName() + "#" + t.javaThreadId();
  }
}

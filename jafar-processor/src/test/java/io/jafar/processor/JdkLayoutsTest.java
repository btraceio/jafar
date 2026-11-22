package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.call;
import static io.jafar.processor.ProcessorTestSupport.parse;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Layouts of the JDK running the compiler: an interface for a {@code jdk.*} event type gets a
 * straight-line read for the order that JDK writes the type's fields in, so a recording made by
 * that JDK is read without the read loop's dispatch, and reads the same values as with it.
 */
class JdkLayoutsTest {
  @TempDir Path dir;

  private static final String SLEEP =
      "@JfrType(\"jdk.ThreadSleep\")\npublic interface PSleep {\n  long time();\n}\n";

  /** jdk.ThreadSleep events of this JVM, whose JDK also compiles the interface below. */
  private Path recordSleeps() throws Exception {
    Path jfr = dir.resolve("sleeps.jfr");
    try (Recording r = new Recording()) {
      r.enable("jdk.ThreadSleep").withThreshold(java.time.Duration.ZERO);
      r.start();
      for (int i = 1; i <= 3; i++) {
        Thread.sleep(i);
      }
      r.stop();
      r.dump(jfr);
    }
    return jfr;
  }

  /** The sleep times read, and which read the first event's plan used. */
  private static List<String> read(Path jfr, ClassLoader loader) throws Exception {
    List<String> times = Collections.synchronizedList(new ArrayList<>());
    String[] readBy = new String[1];
    parse(
        jfr,
        loader,
        loader.loadClass("test.PSleep"),
        ev -> {
          synchronized (readBy) {
            if (readBy[0] == null) {
              readBy[0] = readBy(ev);
            }
          }
          times.add(String.valueOf(call(ev, "time")));
        },
        "PSleep");
    List<String> out = new ArrayList<>(times);
    Collections.sort(out);
    out.add(0, readBy[0]);
    return out;
  }

  private static String readBy(Object handler) {
    try {
      Field planField = handler.getClass().getDeclaredField("plan");
      planField.setAccessible(true);
      Object plan = planField.get(handler);
      Field layout;
      try {
        layout = plan.getClass().getDeclaredField("layout");
      } catch (NoSuchFieldException none) {
        return "the loop";
      }
      layout.setAccessible(true);
      int k = layout.getInt(plan);
      return k == 0 ? "the loop" : "readLayout" + k;
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private ClassLoader compile(String... options) throws Exception {
    Path classes = dir.resolve("classes-" + options.length);
    ProcessorTestSupport.compileTo(
        classes, java.util.Arrays.asList(options), source("PSleep", SLEEP));
    return new java.net.URLClassLoader(
        new java.net.URL[] {classes.toUri().toURL()}, JdkLayoutsTest.class.getClassLoader());
  }

  @Test
  void aRecordingOfTheCompilingJdkIsReadStraightLine() throws Exception {
    Path jfr = recordSleeps();
    List<String> fast = read(jfr, compile());
    List<String> loop = read(jfr, compile("-Ajafar.layouts.jdk=false"));

    assertEquals("readLayout1", fast.get(0));
    assertEquals("the loop", loop.get(0));
    assertTrue(fast.size() >= 4, "no jdk.ThreadSleep events: " + fast);
    // the same values either way: the sleep times, in nanoseconds
    assertEquals(loop.subList(1, loop.size()), fast.subList(1, fast.size()));
    assertTrue(
        fast.subList(1, fast.size()).stream().allMatch(t -> Long.parseLong(t) > 0),
        fast.toString());
  }
}

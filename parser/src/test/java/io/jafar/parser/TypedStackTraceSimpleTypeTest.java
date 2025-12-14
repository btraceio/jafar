package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Test to verify that simple types (like jdk.types.Symbol) are properly unwrapped when accessed
 * through the typed API.
 *
 * <p>Bug: jdk.types.Method.name is of type jdk.types.Symbol (a simple type with a single "string"
 * field), but when accessed via typed API as String name(), it returns null because the constant
 * pool contains Symbol objects that need to be unwrapped.
 */
public class TypedStackTraceSimpleTypeTest {

  @JfrType("jdk.ExecutionSample")
  public interface JFRExecutionSample {
    long startTime();

    @JfrField("sampledThread")
    JFRThread thread();

    JFRStackTrace stackTrace();
  }

  @JfrType("jdk.types.StackTrace")
  public interface JFRStackTrace {
    boolean truncated();

    JFRStackFrame[] frames();
  }

  @JfrType("jdk.types.StackFrame")
  public interface JFRStackFrame {
    JFRMethod method();

    int lineNumber();

    int bytecodeIndex();
  }

  @JfrType("jdk.types.Method")
  public interface JFRMethod {
    @JfrField("type")
    JFRClass methodClass();

    String name();

    String descriptor();

    int modifiers();

    boolean hidden();
  }

  @JfrType("java.lang.Class")
  public interface JFRClass {
    String name();
  }

  @JfrType("java.lang.Thread")
  public interface JFRThread {
    String javaName();

    long javaThreadId();
  }

  @Test
  public void testSimpleTypeUnwrapping() throws Exception {
    // Use a standard JDK recording with ExecutionSample events
    Path jfrFile = Paths.get("parser/src/test/resources/test-jfr.jfr");
    if (!jfrFile.toFile().exists()) {
      // Try relative path from module root
      jfrFile = Paths.get("src/test/resources/test-jfr.jfr");
      if (!jfrFile.toFile().exists()) {
        System.out.println(
            "Skipping test - test-jfr.jfr not found at: " + jfrFile.toAbsolutePath());
        return;
      }
    }

    ParsingContext ctx = ParsingContext.create();
    AtomicInteger eventCount = new AtomicInteger();
    AtomicInteger eventsWithFrames = new AtomicInteger();
    AtomicInteger framesWithNullMethodName = new AtomicInteger();
    AtomicInteger framesWithNullClassName = new AtomicInteger();

    try (TypedJafarParser parser = ctx.newTypedParser(jfrFile)) {
      parser.handle(
          JFRExecutionSample.class,
          (event, control) -> {
            eventCount.incrementAndGet();

            JFRStackTrace stackTrace = event.stackTrace();
            if (stackTrace != null) {
              JFRStackFrame[] frames = stackTrace.frames();
              if (frames != null && frames.length > 0) {
                eventsWithFrames.incrementAndGet();

                // Check first frame
                JFRStackFrame frame = frames[0];
                if (frame != null && frame.method() != null) {
                  JFRMethod method = frame.method();
                  String methodName = method.name();
                  if (methodName == null) {
                    framesWithNullMethodName.incrementAndGet();
                  }

                  JFRClass methodClass = method.methodClass();
                  if (methodClass != null) {
                    String className = methodClass.name();
                    if (className == null) {
                      framesWithNullClassName.incrementAndGet();
                    }
                  }
                }

                // Stop after checking 100 events with frames
                if (eventsWithFrames.get() >= 100) {
                  control.abort();
                }
              }
            }
          });

      parser.run();
    }

    System.out.println("\n=== Test Results ===");
    System.out.println("Total ExecutionSample events: " + eventCount.get());
    System.out.println("Events with frames: " + eventsWithFrames.get());
    System.out.println("Frames with null method name: " + framesWithNullMethodName.get());
    System.out.println("Frames with null class name: " + framesWithNullClassName.get());

    // This test exposes the bug: method names and class names should NOT be null
    assertTrue(
        eventsWithFrames.get() > 0,
        "Should have found at least one ExecutionSample event with frames");
    assertEquals(
        0,
        framesWithNullMethodName.get(),
        "Method names should not be null - this indicates jdk.types.Symbol is not being unwrapped"
            + " properly");
    assertEquals(
        0,
        framesWithNullClassName.get(),
        "Class names should not be null - this indicates jdk.types.Symbol is not being unwrapped"
            + " properly");
  }
}

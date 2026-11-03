package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FailuresTest {

  @Test
  void namesWhatFailedAndWhyOnce() {
    IOException root = new IOException("Invalid JFR Magic Number: 3c21444f");
    IOException parse =
        new IOException("Error occurred while parsing JFR recording: " + root.getMessage(), root);
    UncheckedIOException outer = new UncheckedIOException("Failed to list chunks of /r.jfr", parse);
    assertEquals(
        "Failed to list chunks of /r.jfr: Error occurred while parsing JFR recording: "
            + "Invalid JFR Magic Number: 3c21444f",
        Failures.describe(outer));
  }

  @Test
  void aFailureWithoutAMessageIsNamedByItsType() {
    assertEquals("java.lang.NullPointerException", Failures.describe(new NullPointerException()));
    // An explicit null message with a cause: the type, then the cause's message.
    assertEquals(
        "java.lang.IllegalStateException: disk gone",
        Failures.describe(new IllegalStateException(null, new IOException("disk gone"))));
  }

  @Test
  void aCauseCycleIsWalkedOnce() {
    Exception a = new Exception("a");
    Exception b = new Exception("b");
    a.initCause(b);
    b.initCause(a); // permitted: initCause only rejects a throwable causing itself
    String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> Failures.describe(a));
    assertEquals("a: b", line);
  }
}

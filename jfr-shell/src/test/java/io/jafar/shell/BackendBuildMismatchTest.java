package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jafar.shell.backend.JfrBackend;
import org.junit.jupiter.api.Test;

/**
 * A development jfr-shell names a backend that was not built with it (doc/agents/Verification.md,
 * R1): without a local override it runs the released {@code jfr-shell-jafar} it fetched on its
 * first start, and a check typed into it exercises that release instead of the change.
 */
class BackendBuildMismatchTest {
  private static final String DEV = "0.0.0-SNAPSHOT";

  private static JfrBackend backend(String id, String version) {
    JfrBackend b = mock(JfrBackend.class);
    when(b.getId()).thenReturn(id);
    when(b.getVersion()).thenReturn(version);
    return b;
  }

  @Test
  void releasedBackendUnderADevelopmentShellIsNamed() {
    String warning = Main.backendBuildMismatch(DEV, backend("jafar", "0.29.0"));
    assertNotNull(warning);
    assertEquals(
        "Warning: this jfr-shell is a development build (0.0.0-SNAPSHOT) but its 'jafar' backend"
            + " is version 0.29.0, not the one built with it. To run the built backend, pass"
            + " -Djfr.shell.backend.jars=jfr-shell-jafar/build/libs/jfr-shell-jafar-0.0.0-SNAPSHOT.jar"
            + " (doc/agents/Verification.md, R1).",
        warning);
    assertNotNull(Main.backendBuildMismatch(DEV, backend("jdk", "0.29.0")));
  }

  @Test
  void backendBuiltWithTheShellIsNotNamed() {
    assertNull(Main.backendBuildMismatch(DEV, backend("jafar", DEV)));
    // No manifest: loaded from this tree's classes (the Gradle test classpath, an IDE).
    assertNull(Main.backendBuildMismatch(DEV, backend("jafar", "dev")));
  }

  @Test
  void releaseShellsAndOutsideBackendsAreNotNamed() {
    assertNull(Main.backendBuildMismatch("0.29.0", backend("jafar", "0.28.0")));
    assertNull(Main.backendBuildMismatch(DEV, backend("acme", "1.0")));
    assertNull(Main.backendBuildMismatch(DEV, backend("jafar", null)));
    assertNull(Main.backendBuildMismatch(null, backend("jafar", "0.29.0")));
  }
}

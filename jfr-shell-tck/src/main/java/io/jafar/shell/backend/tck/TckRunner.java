package io.jafar.shell.backend.tck;

import io.jafar.shell.backend.JfrBackend;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ServiceLoader;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * TCK Runner - executes the Technology Compatibility Kit against a backend JAR.
 *
 * <p>Usage:
 *
 * <pre>
 * java -jar jfr-shell-tck-all.jar &lt;backend.jar&gt; [test-recording.jfr]
 * </pre>
 *
 * <p>If no test recording is provided, uses a built-in small test file.
 */
public final class TckRunner {

  private static JfrBackend loadedBackend;
  private static Path testRecordingPath;

  public static void main(String[] args) {
    if (args.length < 1) {
      printUsage();
      System.exit(1);
    }

    Path backendJar = Paths.get(args[0]);
    if (!Files.exists(backendJar)) {
      System.err.println("Error: Backend JAR not found: " + backendJar);
      System.exit(1);
    }

    // Optional test recording path
    if (args.length >= 2) {
      testRecordingPath = Paths.get(args[1]);
      if (!Files.exists(testRecordingPath)) {
        System.err.println("Error: Test recording not found: " + testRecordingPath);
        System.exit(1);
      }
    } else {
      // Extract built-in test recording
      testRecordingPath = extractBuiltInTestRecording();
    }

    System.out.println("=== JFR Shell Backend TCK ===");
    System.out.println("Backend JAR: " + backendJar);
    System.out.println("Test recording: " + testRecordingPath);
    System.out.println();

    // Load backend from JAR
    loadedBackend = loadBackend(backendJar);
    if (loadedBackend == null) {
      System.err.println("Error: No JfrBackend implementation found in JAR");
      System.err.println(
          "Ensure the JAR contains META-INF/services/io.jafar.shell.backend.JfrBackend");
      System.exit(1);
    }

    System.out.println(
        "Loaded backend: " + loadedBackend.getName() + " (id=" + loadedBackend.getId() + ")");
    System.out.println("Capabilities: " + loadedBackend.getCapabilities());
    System.out.println();

    // Run TCK tests
    int exitCode = runTck();
    System.exit(exitCode);
  }

  private static void printUsage() {
    System.out.println("Usage: java -jar jfr-shell-tck-all.jar <backend.jar> [test-recording.jfr]");
    System.out.println();
    System.out.println("Arguments:");
    System.out.println("  backend.jar        Path to the backend plugin JAR to test");
    System.out.println(
        "  test-recording.jfr Optional path to a JFR test file (default: built-in small file)");
    System.out.println();
    System.out.println("The backend JAR must:");
    System.out.println("  - Implement io.jafar.shell.backend.JfrBackend");
    System.out.println("  - Register via META-INF/services/io.jafar.shell.backend.JfrBackend");
  }

  private static JfrBackend loadBackend(Path jarPath) {
    try {
      URL jarUrl = jarPath.toUri().toURL();
      URLClassLoader classLoader =
          new URLClassLoader(new URL[] {jarUrl}, TckRunner.class.getClassLoader());

      ServiceLoader<JfrBackend> loader = ServiceLoader.load(JfrBackend.class, classLoader);
      for (JfrBackend backend : loader) {
        return backend; // Return first found
      }
    } catch (Exception e) {
      System.err.println("Error loading backend: " + e.getMessage());
      e.printStackTrace();
    }
    return null;
  }

  private static Path extractBuiltInTestRecording() {
    try {
      InputStream is = TckRunner.class.getResourceAsStream("/tck-test.jfr");
      if (is == null) {
        System.err.println("Warning: Built-in test recording not found, tests may fail");
        return Paths.get("tck-test.jfr");
      }
      Path tempFile = Files.createTempFile("tck-test-", ".jfr");
      tempFile.toFile().deleteOnExit();
      Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
      is.close();
      return tempFile;
    } catch (IOException e) {
      System.err.println("Error extracting test recording: " + e.getMessage());
      return Paths.get("tck-test.jfr");
    }
  }

  private static int runTck() {
    // Create launcher and listener
    Launcher launcher = LauncherFactory.create();
    SummaryGeneratingListener listener = new SummaryGeneratingListener();

    // Discover and run tests
    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(TckTestSuite.class))
            .build();

    launcher.registerTestExecutionListeners(listener);
    launcher.execute(request);

    // Print summary
    TestExecutionSummary summary = listener.getSummary();
    System.out.println();
    System.out.println("=== TCK Results ===");
    System.out.println("Tests run: " + summary.getTestsFoundCount());
    System.out.println("Passed: " + summary.getTestsSucceededCount());
    System.out.println("Failed: " + summary.getTestsFailedCount());
    System.out.println("Skipped: " + summary.getTestsSkippedCount());

    if (!summary.getFailures().isEmpty()) {
      System.out.println();
      System.out.println("Failures:");
      for (TestExecutionSummary.Failure failure : summary.getFailures()) {
        System.out.println("  - " + failure.getTestIdentifier().getDisplayName());
        System.out.println("    " + failure.getException().getMessage());
      }
    }

    return summary.getTestsFailedCount() > 0 ? 1 : 0;
  }

  /** Returns the backend loaded by the runner. Used by TckTestSuite. */
  static JfrBackend getLoadedBackend() {
    return loadedBackend;
  }

  /** Returns the test recording path. Used by TckTestSuite. */
  static Path getTestRecordingPath() {
    return testRecordingPath;
  }
}

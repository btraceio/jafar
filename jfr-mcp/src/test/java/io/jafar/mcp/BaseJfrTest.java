package io.jafar.mcp;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for JFR-based tests providing small, fast test JFR files.
 *
 * <p>All test JFR files are generated once per test run using the JMC Writer API and are small (&lt;
 * 50KB) for fast test execution.
 */
public abstract class BaseJfrTest {

  protected static Path executionSampleFile;
  protected static Path exceptionFile;
  protected static Path comprehensiveFile;

  @BeforeAll
  static void createTestJfrFiles() throws Exception {
    // Create small JFR test files once for all tests
    executionSampleFile = JfrTestFileBuilder.createExecutionSampleFile(20);
    exceptionFile = JfrTestFileBuilder.createExceptionFile(10);
    comprehensiveFile = JfrTestFileBuilder.createComprehensiveFile();
  }

  /**
   * Returns path to a small JFR file with execution samples (CPU profiling events).
   *
   * @return path to test JFR file with ~20 execution sample events
   */
  protected static String getExecutionSampleJfr() {
    return executionSampleFile.toString();
  }

  /**
   * Returns path to a small JFR file with exception events.
   *
   * @return path to test JFR file with ~10 exception events
   */
  protected static String getExceptionJfr() {
    return exceptionFile.toString();
  }

  /**
   * Returns path to a comprehensive small JFR file with multiple event types.
   *
   * @return path to test JFR file with execution samples, exceptions, and GC events
   */
  protected static String getComprehensiveJfr() {
    return comprehensiveFile.toString();
  }
}

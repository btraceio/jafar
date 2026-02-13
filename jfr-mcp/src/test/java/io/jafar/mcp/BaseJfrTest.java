package io.jafar.mcp;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for tests using real production JFR files.
 *
 * <p>Tests extending this class use real JFR recordings from parser test resources. These tests
 * exercise the full processing pipeline with realistic data.
 *
 * <p>These tests are excluded by default in build.gradle and require -DenableIntegrationTests=true
 * to run, as they need large JFR files that may not be available in all environments.
 */
public abstract class BaseJfrTest {

  protected static Path realJfrFile;

  @BeforeAll
  static void findRealJfrFile() {
    // Use real JFR file from demo test resources (smallest available at ~171MB)
    realJfrFile = Path.of("../demo/src/test/resources/test-ap.jfr").normalize();
    if (!realJfrFile.toFile().exists()) {
      // Try alternative location
      realJfrFile = Path.of("demo/src/test/resources/test-ap.jfr").normalize();
    }
    if (!realJfrFile.toFile().exists()) {
      throw new IllegalStateException(
          "Real JFR file not found at: " + realJfrFile.toAbsolutePath());
    }
  }

  /**
   * Returns path to a real production JFR file for integration testing.
   *
   * @return path to real JFR file (~171MB)
   */
  protected static String getComprehensiveJfr() {
    return realJfrFile.toString();
  }

  /**
   * Returns path to execution sample JFR file (same as comprehensive for real files).
   *
   * @return path to real JFR file
   */
  protected static String getExecutionSampleJfr() {
    return realJfrFile.toString();
  }

  /**
   * Returns path to exception JFR file (same as comprehensive for real files).
   *
   * @return path to real JFR file
   */
  protected static String getExceptionJfr() {
    return realJfrFile.toString();
  }
}

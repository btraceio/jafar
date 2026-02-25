package io.jafar.mcp;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for tests using real JFR files.
 *
 * <p>Tests extending this class use a small (~2MB) Datadog JFR recording to avoid OOM. These tests
 * exercise the full processing pipeline with realistic data.
 *
 * <p>These tests are excluded by default in build.gradle and require -DenableIntegrationTests=true
 * to run.
 */
public abstract class BaseJfrTest {

  protected static Path realJfrFile;

  @BeforeAll
  static void findRealJfrFile() {
    // Use small JFR file (~2MB) to avoid OOM
    realJfrFile = Path.of("../demo/src/test/resources/test-dd.jfr").normalize();
    if (!realJfrFile.toFile().exists()) {
      realJfrFile = Path.of("demo/src/test/resources/test-dd.jfr").normalize();
    }
    if (!realJfrFile.toFile().exists()) {
      throw new IllegalStateException("JFR file not found at: " + realJfrFile.toAbsolutePath());
    }
  }

  /**
   * Returns path to the JFR file for integration testing.
   *
   * @return path to JFR file (~2MB)
   */
  protected static String getComprehensiveJfr() {
    return realJfrFile.toString();
  }

  /**
   * Returns path to execution sample JFR file.
   *
   * @return path to JFR file
   */
  protected static String getExecutionSampleJfr() {
    return realJfrFile.toString();
  }

  /**
   * Returns path to exception JFR file.
   *
   * @return path to JFR file
   */
  protected static String getExceptionJfr() {
    return realJfrFile.toString();
  }
}

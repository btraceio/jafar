package io.jafar.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Test to verify that TypeGenerator correctly handles name collisions by including namespace in
 * generated class names.
 *
 * <p>This test creates a JFR recording with events that have the same simple name but different
 * namespaces (e.g., "jdk.ExecutionSample" vs "datadog.ExecutionSample") and verifies that distinct
 * class files are generated for each.
 */
public class NameCollisionTest {

  @TempDir Path tempDir;

  private Path outputDir;
  private Path jfrFile;

  @BeforeEach
  void setUp() throws Exception {
    outputDir = tempDir.resolve("generated");
    Files.createDirectories(outputDir);
    jfrFile = tempDir.resolve("name-collision.jfr");
  }

  @Test
  void shouldGenerateDistinctClassesForSameNamedEventsInDifferentNamespaces() throws Exception {
    // Given: JFR file with events having the same simple name but different namespaces
    createJfrWithNameCollision(jfrFile);

    // When: generating types from the file
    TypeGenerator generator =
        new TypeGenerator(
            jfrFile,
            outputDir,
            "io.jafar.test.collision",
            false,
            typeName -> typeName.startsWith("jdk.") || typeName.startsWith("datadog."));

    generator.generate();

    // Then: should generate distinct class files for each namespace
    Path typesDir = outputDir.resolve("io/jafar/test/collision");
    assertThat(typesDir).exists();

    List<String> generatedFiles;
    try (Stream<Path> stream = Files.list(typesDir)) {
      generatedFiles =
          stream.map(Path::getFileName).map(Path::toString).sorted().collect(Collectors.toList());
    }

    System.out.println("Generated files: " + generatedFiles);

    // Verify both event types generated distinct files
    assertThat(generatedFiles)
        .contains("JFRJdkExecutionSample.java", "JFRDatadogExecutionSample.java")
        .as("Should generate distinct class files for jdk.ExecutionSample and datadog.ExecutionSample");

    // Verify the generated classes have different content
    String jdkContent = Files.readString(typesDir.resolve("JFRJdkExecutionSample.java"));
    String datadogContent = Files.readString(typesDir.resolve("JFRDatadogExecutionSample.java"));

    assertThat(jdkContent).contains("interface JFRJdkExecutionSample");
    assertThat(datadogContent).contains("interface JFRDatadogExecutionSample");
  }

  /**
   * Creates a JFR file with two events that have the same simple name but different namespaces.
   *
   * <p>Structure:
   *
   * <pre>
   * jdk.ExecutionSample extends Event {
   *   long timestamp;
   * }
   * datadog.ExecutionSample extends Event {
   *   String traceId;
   * }
   * </pre>
   */
  private void createJfrWithNameCollision(Path outputFile) throws Exception {
    try (Recording recording = Recordings.newRecording(outputFile)) {
      Types types = recording.getTypes();

      // Create jdk.ExecutionSample event
      Type jdkEventType =
          recording.registerEventType(
              "jdk.ExecutionSample",
              typeBuilder -> {
                typeBuilder.addField("timestamp", Types.Builtin.LONG);
              });

      // Create datadog.ExecutionSample event (same simple name, different namespace)
      Type datadogEventType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              typeBuilder -> {
                typeBuilder.addField("traceId", Types.Builtin.STRING);
              });

      // Write one instance of each event type
      recording.writeEvent(
          jdkEventType.asValue(
              v -> {
                v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                v.putField("startTime", System.nanoTime());
                v.putField("timestamp", 123456789L);
              }));

      recording.writeEvent(
          datadogEventType.asValue(
              v -> {
                v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                v.putField("startTime", System.nanoTime());
                v.putField("traceId", "trace-abc-123");
              }));
    }
  }
}

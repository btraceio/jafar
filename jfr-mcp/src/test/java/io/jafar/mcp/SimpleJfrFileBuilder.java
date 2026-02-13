package io.jafar.mcp;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Simplified JFR file builder that avoids complex JDK types and constant pool issues.
 *
 * <p>Instead of using JDK type initialization, this creates simple custom event types that work
 * reliably with the JMC Writer API.
 */
public final class SimpleJfrFileBuilder {

  private SimpleJfrFileBuilder() {}

  /**
   * Creates a JFR file with execution sample events. Uses minimal custom fields to avoid JMC Writer
   * API issues.
   */
  public static Path createExecutionSampleFile(int eventCount) throws Exception {
    Path tempFile = Files.createTempFile("test-exec-samples-", ".jfr");
    tempFile.toFile().deleteOnExit();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (Recording recording = Recordings.newRecording(baos)) {
      // Register custom execution sample event
      // Note: startTime, eventThread, stackTrace are implicit fields added automatically
      Type eventType = recording.registerEventType("jdk.ExecutionSample");

      for (int i = 0; i < eventCount; i++) {
        final long timestamp = System.nanoTime() + i * 1000;

        recording.writeEvent(
            eventType.asValue(
                v -> {
                  v.putField("startTime", timestamp);
                }));
      }
    }

    Files.write(tempFile, baos.toByteArray());
    return tempFile;
  }

  /** Creates a JFR file with exception events. */
  public static Path createExceptionFile(int eventCount) throws Exception {
    Path tempFile = Files.createTempFile("test-exceptions-", ".jfr");
    tempFile.toFile().deleteOnExit();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (Recording recording = Recordings.newRecording(baos)) {
      // Register custom exception event with simple string fields
      // Avoid implicit fields (startTime, eventThread, stackTrace)
      Type eventType =
          recording.registerEventType(
              "jdk.JavaExceptionThrow",
              type -> {
                type.addField("thrownClass", Types.Builtin.STRING);
                type.addField("message", Types.Builtin.STRING);
              });

      String[] exceptionTypes = {
        "java.lang.NullPointerException",
        "java.lang.IllegalArgumentException",
        "java.io.IOException",
        "java.lang.RuntimeException"
      };

      for (int i = 0; i < eventCount; i++) {
        String exType = exceptionTypes[i % exceptionTypes.length];
        String message = "Test exception " + i + ": " + exType;
        final long timestamp = System.nanoTime() + i * 1000;

        recording.writeEvent(
            eventType.asValue(
                v -> {
                  v.putField("startTime", timestamp);
                  v.putField("thrownClass", exType);
                  v.putField("message", message);
                }));
      }
    }

    Files.write(tempFile, baos.toByteArray());
    return tempFile;
  }

  /** Creates a comprehensive JFR file with multiple event types. */
  public static Path createComprehensiveFile() throws Exception {
    Path tempFile = Files.createTempFile("test-comprehensive-", ".jfr");
    tempFile.toFile().deleteOnExit();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (Recording recording = Recordings.newRecording(baos)) {
      // Execution samples (no custom fields, just use implicit ones)
      Type execType = recording.registerEventType("jdk.ExecutionSample");

      // Exception events (simple fields, avoid stackTrace)
      Type exceptionType =
          recording.registerEventType(
              "jdk.JavaExceptionThrow",
              type -> {
                type.addField("thrownClass", Types.Builtin.STRING);
                type.addField("message", Types.Builtin.STRING);
              });

      // GC events
      Type gcType =
          recording.registerEventType(
              "jdk.GCPhasePause",
              type -> {
                type.addField("duration", Types.Builtin.LONG);
                type.addField("name", Types.Builtin.STRING);
              });

      // Write execution samples
      for (int i = 0; i < 100; i++) {
        final long timestamp = System.nanoTime() + i * 1000;

        recording.writeEvent(
            execType.asValue(
                v -> {
                  v.putField("startTime", timestamp);
                }));
      }

      // Write exception events
      String[] exceptionTypes = {
        "java.lang.NullPointerException",
        "java.lang.IllegalArgumentException",
        "java.io.IOException"
      };

      java.util.Random random = new java.util.Random(42);
      for (int i = 0; i < 20; i++) {
        int idx = random.nextInt(exceptionTypes.length);
        String exType = exceptionTypes[idx];
        String message = "Test exception " + i;
        final long timestamp = System.nanoTime() + i * 2000;

        recording.writeEvent(
            exceptionType.asValue(
                v -> {
                  v.putField("startTime", timestamp);
                  v.putField("thrownClass", exType);
                  v.putField("message", message);
                }));
      }

      // Write GC events
      for (int i = 0; i < 3; i++) {
        final long timestamp = System.nanoTime() + i * 5000;
        final long duration = 1_000_000L + i * 500_000;
        recording.writeEvent(
            gcType.asValue(
                v -> {
                  v.putField("startTime", timestamp);
                  v.putField("duration", duration);
                  v.putField("name", "G1 Young Generation");
                }));
      }
    }

    Files.write(tempFile, baos.toByteArray());
    return tempFile;
  }
}

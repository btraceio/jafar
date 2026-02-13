package io.jafar.mcp;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Builder for creating small but realistic JFR test files using the JMC Writer API.
 *
 * <p>Creates test files with realistic event structures (stack traces, threads, timing) suitable
 * for unit testing JFR processing logic. Files are small (< 50KB) for fast test execution.
 */
public final class JfrTestFileBuilder {

  private JfrTestFileBuilder() {}

  /**
   * Creates a JFR file with execution sample events containing realistic stack traces.
   *
   * @param eventCount number of execution sample events to create
   * @return path to the created temp JFR file
   */
  public static Path createExecutionSampleFile(int eventCount) throws Exception {
    Path tempFile = Files.createTempFile("test-execution-samples-", ".jfr");
    tempFile.toFile().deleteOnExit();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (Recording recording =
        Recordings.newRecording(baos, settings -> settings.withJdkTypeInitialization())) {
      Types types = recording.getTypes();
      Type stackTraceType = types.getType(Types.JDK.STACK_TRACE);
      Type threadType = types.getType(Types.JDK.THREAD);
      Type frameType = types.getType(Types.JDK.STACK_FRAME);
      Type methodType = types.getType(Types.JDK.METHOD);
      Type classType = types.getType(Types.JDK.CLASS);

      // Note: startTime, eventThread, and stackTrace are added implicitly by JMC Writer
      Type eventType = recording.registerEventType("jdk.ExecutionSample");

      for (int i = 0; i < eventCount; i++) {
        // Create diverse stack traces
        String[][] stacks =
            new String[][] {
              {"hotMethod1", "callerMethod1", "deepMethod", "main"},
              {"hotMethod2", "callerMethod2", "anotherDeepMethod", "main"},
              {"hotMethod3", "shallowMethod", "main"},
              {"compute", "process", "run", "main"},
              {"allocate", "create", "initialize", "main"}
            };
        String[] stack = stacks[i % stacks.length];
        final long timestamp = System.nanoTime() + i * 1000;

        recording.writeEvent(
            eventType.asValue(
                v -> {
                  v.putField("startTime", timestamp);
                  v.putField("eventThread", threadType.nullValue());
                  v.putField(
                      "stackTrace",
                      stackTraceType.asValue(
                          st -> {
                            st.putField(
                                "frames",
                                createFrames(types, frameType, methodType, classType, stack));
                            st.putField("truncated", false);
                          }));
                }));
      }
    }

    Files.write(tempFile, baos.toByteArray());
    return tempFile;
  }

  /**
   * Creates a JFR file with exception events containing stack traces and exception details.
   *
   * @param eventCount number of exception events to create
   * @return path to the created temp JFR file
   */
  public static Path createExceptionFile(int eventCount) throws Exception {
    Path tempFile = Files.createTempFile("test-exceptions-", ".jfr");
    tempFile.toFile().deleteOnExit();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (Recording recording =
        Recordings.newRecording(baos, settings -> settings.withJdkTypeInitialization())) {
      Types types = recording.getTypes();
      Type stackTraceType = types.getType(Types.JDK.STACK_TRACE);
      Type threadType = types.getType(Types.JDK.THREAD);
      Type frameType = types.getType(Types.JDK.STACK_FRAME);
      Type methodType = types.getType(Types.JDK.METHOD);
      Type classType = types.getType(Types.JDK.CLASS);

      // Note: startTime, eventThread, and stackTrace are added implicitly by JMC Writer
      Type eventType =
          recording.registerEventType(
              "jdk.JavaExceptionThrow",
              type -> {
                type.addField(
                    "thrownClass",
                    classType,
                    field -> {
                      field.addAnnotation(types.getType("jdk.jfr.Label"), "Thrown Class");
                    });
                type.addField(
                    "message",
                    Types.Builtin.STRING,
                    field -> {
                      field.addAnnotation(types.getType("jdk.jfr.Label"), "Message");
                    });
              });

      String[] exceptionTypes = {
        "java.lang.NullPointerException",
        "java.lang.IllegalArgumentException",
        "java.io.IOException",
        "java.lang.RuntimeException"
      };

      for (int i = 0; i < eventCount; i++) {
        final String exType = exceptionTypes[i % exceptionTypes.length];
        final String message = "Test exception " + i + ": " + exType;
        final long timestamp = System.nanoTime() + i * 1000;

        recording.writeEvent(
            eventType.asValue(
                v -> {
                  v.putField("startTime", timestamp);
                  v.putField("eventThread", threadType.nullValue());
                  v.putField(
                      "thrownClass",
                      classType.asValue(
                          c -> {
                            c.putField("name", exType);
                          }));
                  v.putField("message", message);
                  v.putField(
                      "stackTrace",
                      stackTraceType.asValue(
                          st -> {
                            String[] stack = {exType + ".<init>", "throwException", "main"};
                            st.putField(
                                "frames",
                                createFrames(types, frameType, methodType, classType, stack));
                            st.putField("truncated", false);
                          }));
                }));
      }
    }

    Files.write(tempFile, baos.toByteArray());
    return tempFile;
  }

  /**
   * Creates a comprehensive JFR file with multiple event types for full integration testing.
   *
   * @return path to the created temp JFR file
   */
  public static Path createComprehensiveFile() throws Exception {
    Path tempFile = Files.createTempFile("test-comprehensive-", ".jfr");
    tempFile.toFile().deleteOnExit();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (Recording recording =
        Recordings.newRecording(baos, settings -> settings.withJdkTypeInitialization())) {
      Types types = recording.getTypes();
      Type stackTraceType = types.getType(Types.JDK.STACK_TRACE);
      Type threadType = types.getType(Types.JDK.THREAD);
      Type frameType = types.getType(Types.JDK.STACK_FRAME);
      Type methodType = types.getType(Types.JDK.METHOD);
      Type classType = types.getType(Types.JDK.CLASS);

      // Note: startTime, eventThread, and stackTrace are added implicitly for event types
      Type execSampleType = recording.registerEventType("jdk.ExecutionSample");

      // Register Exception events
      Type exceptionType =
          recording.registerEventType(
              "jdk.JavaExceptionThrow",
              type -> {
                type.addField("thrownClass", classType);
                type.addField("message", Types.Builtin.STRING);
              });

      // Register GC events (non-execution events need explicit duration/name fields)
      Type gcType =
          recording.registerEventType(
              "jdk.GCPhasePause",
              type -> {
                type.addField("duration", Types.Builtin.LONG);
                type.addField("name", Types.Builtin.STRING);
              });

      // Write execution samples
      String[][] stacks = {
        {"hotMethod1", "callerMethod1", "main"},
        {"hotMethod2", "callerMethod2", "main"},
        {"compute", "process", "main"}
      };

      for (int i = 0; i < 20; i++) {
        final String[] stack = stacks[i % stacks.length];
        final long timestamp = System.nanoTime() + i * 1000;
        recording.writeEvent(
            execSampleType.asValue(
                v -> {
                  v.putField("startTime", timestamp);
                  v.putField("eventThread", threadType.nullValue());
                  v.putField(
                      "stackTrace",
                      stackTraceType.asValue(
                          st -> {
                            st.putField(
                                "frames",
                                createFrames(types, frameType, methodType, classType, stack));
                            st.putField("truncated", false);
                          }));
                }));
      }

      // Write exception events
      String[] exceptionTypes = {
        "java.lang.NullPointerException", "java.lang.RuntimeException"
      };

      for (int i = 0; i < 5; i++) {
        final String exType = exceptionTypes[i % exceptionTypes.length];
        final String message = "Test exception " + i;
        final long timestamp = System.nanoTime() + i * 2000;
        recording.writeEvent(
            exceptionType.asValue(
                v -> {
                  v.putField("startTime", timestamp);
                  v.putField("eventThread", threadType.nullValue());
                  v.putField(
                      "thrownClass",
                      classType.asValue(
                          c -> {
                            c.putField("name", exType);
                            // Populate all required Class fields for parser compatibility
                            c.putField("classLoader", types.getType(Types.JDK.CLASS_LOADER).nullValue());
                            c.putField("modifiers", 1); // ACC_PUBLIC
                            c.putField("hidden", false);
                          }));
                  v.putField("message", message);
                  v.putField(
                      "stackTrace",
                      stackTraceType.asValue(
                          st -> {
                            String[] stack = {exType + ".<init>", "throwException", "main"};
                            st.putField(
                                "frames",
                                createFrames(types, frameType, methodType, classType, stack));
                            st.putField("truncated", false);
                          }));
                }));
      }

      // Write GC events
      for (int i = 0; i < 3; i++) {
        final long timestamp = System.nanoTime() + i * 5000;
        final long duration = 1_000_000L + i * 500_000; // 1-2.5ms
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

  // Helper method to create stack frames
  private static TypedValue[] createFrames(
      Types types, Type frameType, Type methodType, Type classType, String[] methodNames) {
    TypedValue[] frames = new TypedValue[methodNames.length];
    for (int i = 0; i < methodNames.length; i++) {
      final int index = i;
      frames[i] =
          frameType.asValue(
              f -> {
                f.putField(
                    "method",
                    methodType.asValue(
                        m -> {
                          m.putField("name", methodNames[index]);
                          m.putField(
                              "type",
                              classType.asValue(
                                  c -> {
                                    c.putField("name", "TestClass");
                                    // Populate all required Class fields for parser compatibility
                                    c.putField("classLoader", types.getType(Types.JDK.CLASS_LOADER).nullValue());
                                    c.putField("modifiers", 1); // ACC_PUBLIC
                                    c.putField("hidden", false);
                                  }));
                          // Populate all required Method fields
                          m.putField("descriptor", "()V"); // void method with no args
                          m.putField("modifiers", 1); // ACC_PUBLIC
                          m.putField("hidden", false);
                        }));
                f.putField("lineNumber", index + 10);
                f.putField("bytecodeIndex", -1);
                f.putField("type", "java");
              });
    }
    return frames;
  }
}

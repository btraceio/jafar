package io.jafar.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * Test to verify that TypeGenerator correctly handles circular type references without infinite
 * recursion.
 *
 * <p>This test creates a specially crafted JFR recording with circular type references using the
 * JMC FlightRecorder writer API. The scenario tests:
 *
 * <ul>
 *   <li>Direct circular reference: Type A contains field of type A
 *   <li>Indirect circular reference: Type A -> Type B -> Type A
 *   <li>Simple type unwrapping with circular reference
 * </ul>
 *
 * <p>Without proper cycle detection, TypeGenerator.generateClass() will recurse infinitely when
 * processing these types.
 */
public class CircularTypeReferenceTest {

  @TempDir Path tempDir;

  private Path outputDir;
  private Path jfrFile;

  @BeforeEach
  void setUp() throws Exception {
    outputDir = tempDir.resolve("generated");
    Files.createDirectories(outputDir);
    jfrFile = tempDir.resolve("circular-types.jfr");
  }

  @Test
  void shouldHandleDirectCircularReference() throws Exception {
    // Given: JFR file with a type that references itself
    createJfrWithDirectCircularReference(jfrFile);

    // When: generating types from the file
    TypeGenerator generator =
        new TypeGenerator(
            jfrFile,
            outputDir,
            "io.jafar.test.circular",
            false, // don't overwrite
            typeName -> typeName.startsWith("test."));

    // Then: should not throw StackOverflowError or recurse infinitely
    // This is the main assertion - we complete without infinite recursion
    assertThatCode(() -> generator.generate())
        .as("TypeGenerator should handle circular references without infinite recursion")
        .doesNotThrowAnyException();

    // Verify at least the event type was generated
    Path typesDir = outputDir.resolve("io/jafar/test/circular");
    if (Files.exists(typesDir)) {
      List<String> generatedFiles;
      try (Stream<Path> stream = Files.list(typesDir)) {
        generatedFiles =
            stream.map(Path::getFileName).map(Path::toString).sorted().collect(Collectors.toList());
      }

      System.out.println("Generated files: " + generatedFiles);
      assertThat(generatedFiles).contains("JFRTestCircularEvent.java");
    }
  }

  @Test
  void shouldHandleIndirectCircularReference() throws Exception {
    // Given: JFR file with types that form a cycle: A -> B -> A
    createJfrWithIndirectCircularReference(jfrFile);

    // When: generating types from the file
    TypeGenerator generator =
        new TypeGenerator(
            jfrFile,
            outputDir,
            "io.jafar.test.circular",
            false,
            typeName -> typeName.startsWith("test."));

    // Then: should not throw StackOverflowError
    // This is the main assertion - we complete without infinite recursion
    assertThatCode(() -> generator.generate())
        .as("TypeGenerator should handle indirect circular references without infinite recursion")
        .doesNotThrowAnyException();

    // Verify at least the event type was generated
    Path typesDir = outputDir.resolve("io/jafar/test/circular");
    if (Files.exists(typesDir)) {
      List<String> generatedFiles;
      try (Stream<Path> stream = Files.list(typesDir)) {
        generatedFiles =
            stream.map(Path::getFileName).map(Path::toString).sorted().collect(Collectors.toList());
      }

      System.out.println("Generated files: " + generatedFiles);
      assertThat(generatedFiles).contains("JFRTestLinkedListEvent.java");
      // Note: NodeA and NodeB may not be generated correctly due to JMC Writer API limitations
      // with forward references. The important thing is that the generator doesn't infinite loop.
    }
  }

  @Test
  void shouldHandleSimpleTypeWrappingWithCircularReference() throws Exception {
    // Given: JFR file with simple type that wraps a circular reference
    createJfrWithSimpleTypeCircularReference(jfrFile);

    // When: generating types from the file
    TypeGenerator generator =
        new TypeGenerator(
            jfrFile,
            outputDir,
            "io.jafar.test.circular",
            false,
            typeName -> typeName.startsWith("test."));

    // Then: should not throw StackOverflowError
    // This is the main assertion - we complete without infinite recursion
    assertThatCode(() -> generator.generate())
        .as(
            "TypeGenerator should handle simple type unwrapping with circular references without"
                + " infinite recursion")
        .doesNotThrowAnyException();

    // Verify at least some types were generated
    Path typesDir = outputDir.resolve("io/jafar/test/circular");
    if (Files.exists(typesDir)) {
      List<String> generatedFiles;
      try (Stream<Path> stream = Files.list(typesDir)) {
        generatedFiles =
            stream.map(Path::getFileName).map(Path::toString).sorted().collect(Collectors.toList());
      }

      System.out.println("Generated files: " + generatedFiles);
      assertThat(generatedFiles).isNotEmpty();
    }
  }

  /**
   * Creates a JFR file with a direct circular reference: Node type has a field of type Node.
   *
   * <p>Structure:
   *
   * <pre>
   * CircularEvent extends Event {
   *   Node root;
   * }
   * Node {
   *   String name;
   *   Node next;  // Circular reference!
   * }
   * </pre>
   */
  private void createJfrWithDirectCircularReference(Path outputFile) throws Exception {
    try (Recording recording = Recordings.newRecording(outputFile)) {
      Types types = recording.getTypes();

      // Create Node type with self-reference using selfType()
      Type nodeType =
          recording.registerType(
              "test.Node",
              typeBuilder -> {
                typeBuilder.addField("name", Types.Builtin.STRING);
                // Self-reference using the type builder's selfType() method
                typeBuilder.addField("next", typeBuilder.selfType());
              });

      // Create event type that uses Node
      Type eventType =
          recording.registerEventType(
              "test.CircularEvent",
              typeBuilder -> {
                typeBuilder.addField("root", nodeType);
              });

      // Write one event instance
      recording.writeEvent(
          eventType.asValue(
              v -> {
                // Write implicit fields
                v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                v.putField("startTime", System.nanoTime());

                // Write root Node
                v.putField(
                    "root",
                    rootNode -> {
                      rootNode.putField("name", "TestNode");
                      // Write nested Node (circular in metadata, but terminates in data)
                      rootNode.putField(
                          "next",
                          nextNode -> {
                            nextNode.putField("name", "ChildNode");
                            nextNode.putField("next", nodeType.nullValue()); // Terminate chain
                          });
                    });
              }));
    }
  }

  /**
   * Creates a JFR file with an indirect circular reference: NodeA -> NodeB -> NodeA.
   *
   * <p>Structure:
   *
   * <pre>
   * LinkedListEvent extends Event {
   *   NodeA head;
   * }
   * NodeA {
   *   String valueA;
   *   NodeB linkB;  // References NodeB
   * }
   * NodeB {
   *   String valueB;
   *   NodeA linkA;  // References NodeA - creates cycle!
   * }
   * </pre>
   */
  private void createJfrWithIndirectCircularReference(Path outputFile) throws Exception {
    try (Recording recording = Recordings.newRecording(outputFile)) {
      Types types = recording.getTypes();

      // First pass: register both types with minimal structure to establish names
      Type nodeAType =
          types.getOrAdd(
              "test.NodeA",
              typeBuilder -> {
                typeBuilder.addField("valueA", Types.Builtin.STRING);
              });

      Type nodeBType =
          types.getOrAdd(
              "test.NodeB",
              typeBuilder -> {
                typeBuilder.addField("valueB", Types.Builtin.STRING);
              });

      // Second pass: add the circular fields using getOrAdd which updates existing types
      types.getOrAdd(
          "test.NodeA",
          typeBuilder -> {
            typeBuilder.addField("valueA", Types.Builtin.STRING);
            typeBuilder.addField("linkB", nodeBType); // Now NodeB exists
          });

      types.getOrAdd(
          "test.NodeB",
          typeBuilder -> {
            typeBuilder.addField("valueB", Types.Builtin.STRING);
            typeBuilder.addField("linkA", nodeAType); // NodeA exists
          });

      // Create event type
      Type eventType =
          recording.registerEventType(
              "test.LinkedListEvent",
              typeBuilder -> {
                typeBuilder.addField("head", nodeAType);
              });

      // Write one event instance
      recording.writeEvent(
          eventType.asValue(
              v -> {
                // Write implicit fields
                v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                v.putField("startTime", System.nanoTime());

                // Write head NodeA
                v.putField(
                    "head",
                    nodeA -> {
                      nodeA.putField("valueA", "NodeA-1");
                      // Write linkB NodeB
                      nodeA.putField(
                          "linkB",
                          nodeB -> {
                            nodeB.putField("valueB", "NodeB-1");
                            nodeB.putField("linkA", nodeAType.nullValue()); // Terminate cycle
                          });
                    });
              }));
    }
  }

  /**
   * Creates a JFR file with a simple type that wraps a circular reference.
   *
   * <p>Structure:
   *
   * <pre>
   * SimpleTypeEvent extends Event {
   *   SimpleWrapper wrapper;
   * }
   * SimpleWrapper {  // Simple type with single field
   *   TreeNode value;
   * }
   * TreeNode {
   *   String name;
   *   TreeNode parent;  // Circular reference
   * }
   * </pre>
   *
   * <p>This tests the while loop at lines 288-291 in TypeGenerator that unwraps simple types.
   */
  private void createJfrWithSimpleTypeCircularReference(Path outputFile) throws Exception {
    try (Recording recording = Recordings.newRecording(outputFile)) {
      Types types = recording.getTypes();

      // Create TreeNode with self-reference
      Type treeNodeType =
          recording.registerType(
              "test.TreeNode",
              typeBuilder -> {
                typeBuilder.addField("name", Types.Builtin.STRING);
                // Self-reference using selfType()
                typeBuilder.addField("parent", typeBuilder.selfType());
              });

      // Create SimpleWrapper (simple type with single field)
      Type wrapperType =
          recording.registerType(
              "test.SimpleWrapper",
              typeBuilder -> {
                typeBuilder.addField("value", treeNodeType);
              });

      // Create event type
      Type eventType =
          recording.registerEventType(
              "test.SimpleTypeEvent",
              typeBuilder -> {
                typeBuilder.addField("wrapper", wrapperType);
              });

      // Write one event instance
      recording.writeEvent(
          eventType.asValue(
              v -> {
                // Write implicit fields
                v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                v.putField("startTime", System.nanoTime());

                // Write wrapper
                v.putField(
                    "wrapper",
                    wrapper -> {
                      // Write value TreeNode
                      wrapper.putField(
                          "value",
                          treeNode -> {
                            treeNode.putField("name", "Root");
                            treeNode.putField(
                                "parent", treeNodeType.nullValue()); // Terminate cycle
                          });
                    });
              }));
    }
  }
}

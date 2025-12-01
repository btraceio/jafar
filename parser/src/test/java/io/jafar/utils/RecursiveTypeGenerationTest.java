package io.jafar.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test to verify recursive type generation from JFR event types.
 *
 * <p>This test exposes the bug where nested types are not fully generated when using the
 * TypeGenerator with runtime event types. For example, when generating types for
 * jdk.ExecutionSample, it should recursively generate all dependent types:
 *
 * <p>JFRExecutionSample -> JFRStackTrace (generated) -> JFRStackFrame (MISSING - bug!) -> JFRMethod
 * -> JFRClass -> etc.
 */
public class RecursiveTypeGenerationTest {

  @TempDir Path tempDir;

  private Path outputDir;

  @BeforeEach
  void setUp() throws IOException {
    outputDir = tempDir.resolve("generated");
    Files.createDirectories(outputDir);
  }

  @Test
  void shouldGenerateAllNestedTypesRecursivelyFromRuntime() throws Exception {
    // Given: TypeGenerator configured for ExecutionSample event
    Predicate<String> filter = "jdk.ExecutionSample"::equals;
    TypeGenerator generator =
        new TypeGenerator(
            null, // null = use runtime JFR types
            outputDir,
            "io.jafar.test.types",
            false, // don't overwrite
            filter);

    // When: generating types
    generator.generate();

    // Then: all types should be generated
    Path typesDir = outputDir.resolve("io/jafar/test/types");
    List<String> generatedFiles = Collections.emptyList();
    try (Stream<Path> stream = Files.list(typesDir)) {
      generatedFiles =
          stream.map(Path::getFileName).map(Path::toString).sorted().collect(Collectors.toList());
    }

    System.out.println("==> Generated files: " + generatedFiles);

    // Primary event type
    assertThat(generatedFiles).contains("JFRExecutionSample.java");

    // Direct dependencies from ExecutionSample
    assertThat(generatedFiles).contains("JFRThread.java");
    assertThat(generatedFiles).contains("JFRStackTrace.java");

    // Nested dependencies - these are missing due to the bug!
    assertThat(generatedFiles)
        .as("StackTrace references StackFrame, should be generated recursively")
        .contains("JFRStackFrame.java");

    // Read the StackTrace file to verify it references StackFrame
    Path stackTraceFile = typesDir.resolve("JFRStackTrace.java");
    String stackTraceContent = Files.readString(stackTraceFile);
    assertThat(stackTraceContent)
        .as("StackTrace should reference StackFrame type")
        .contains("JFRStackFrame");

    // Verify deeper nested dependencies
    if (generatedFiles.contains("JFRStackFrame.java")) {
      Path stackFrameFile = typesDir.resolve("JFRStackFrame.java");
      String stackFrameContent = Files.readString(stackFrameFile);

      // StackFrame typically references Method
      if (stackFrameContent.contains("JFRMethod")) {
        assertThat(generatedFiles)
            .as("StackFrame references Method, should be generated recursively")
            .contains("JFRMethod.java");
      }
    }
  }

  @Test
  void shouldGenerateNestedTypesForSystemProcess() throws Exception {
    // Given: TypeGenerator configured for SystemProcess event
    Predicate<String> filter = "jdk.SystemProcess"::equals;
    TypeGenerator generator =
        new TypeGenerator(null, outputDir, "io.jafar.test.types", false, filter);

    // When: generating types
    generator.generate();

    // Then: SystemProcess and its nested types should exist
    Path typesDir = outputDir.resolve("io/jafar/test/types");
    List<String> generatedFiles = Collections.emptyList();
    try (Stream<Path> stream = Files.list(typesDir)) {
      generatedFiles =
          stream.map(Path::getFileName).map(Path::toString).collect(Collectors.toList());
    }

    assertThat(generatedFiles).contains("JFRSystemProcess.java");

    // Verify all referenced types are generated
    Path systemProcessFile = typesDir.resolve("JFRSystemProcess.java");
    String content = Files.readString(systemProcessFile);

    // Extract all JFR type references from the file
    Set<String> referencedTypes = extractJfrTypeReferences(content);

    for (String referencedType : referencedTypes) {
      assertThat(generatedFiles)
          .as("Referenced type %s should be generated", referencedType)
          .contains(referencedType + ".java");
    }
  }

  @Test
  void shouldHandleOverwriteCorrectlyForNestedTypes() throws Exception {
    // Given: types already generated once
    Predicate<String> filter = "jdk.ExecutionSample"::equals;
    TypeGenerator generator1 =
        new TypeGenerator(null, outputDir, "io.jafar.test.types", false, filter);
    generator1.generate();

    Path typesDir = outputDir.resolve("io/jafar/test/types");
    long fileCountAfterFirstRun = 0;
    try (Stream<Path> stream = Files.list(typesDir)) {
      fileCountAfterFirstRun = stream.count();
    }
    // When: regenerating with overwrite=true
    TypeGenerator generator2 =
        new TypeGenerator(
            null,
            outputDir,
            "io.jafar.test.types",
            true, // overwrite enabled
            filter);

    // Then: should not throw exception and should regenerate all types
    generator2.generate();

    long fileCountAfterSecondRun = 0;
    try (Stream<Path> stream = Files.list(typesDir)) {
      fileCountAfterSecondRun = stream.count();
    }
    assertThat(fileCountAfterSecondRun)
        .as("Same number of files should exist after regeneration with overwrite=true")
        .isEqualTo(fileCountAfterFirstRun);
  }

  @Test
  void allGeneratedTypesShouldBeValidJavaInterfaces() throws Exception {
    // Given: TypeGenerator configured for ExecutionSample
    Predicate<String> filter = "jdk.ExecutionSample"::equals;
    TypeGenerator generator =
        new TypeGenerator(null, outputDir, "io.jafar.test.types", false, filter);

    // When: generating types
    generator.generate();

    // Then: all generated files should be valid Java interfaces
    Path typesDir = outputDir.resolve("io/jafar/test/types");
    try (Stream<Path> stream = Files.list(typesDir)) {
      stream
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(
              javaFile -> {
                try {
                  String content = Files.readString(javaFile);
                  assertThat(content)
                      .as("File %s should contain package declaration", javaFile.getFileName())
                      .contains("package io.jafar.test.types;");
                  assertThat(content)
                      .as("File %s should contain import for JfrType", javaFile.getFileName())
                      .contains("import io.jafar.parser.api.*;");
                  assertThat(content)
                      .as("File %s should be an interface", javaFile.getFileName())
                      .contains("public interface JFR");
                  assertThat(content)
                      .as("File %s should have JfrType annotation", javaFile.getFileName())
                      .contains("@JfrType(");
                } catch (IOException e) {
                  throw new RuntimeException("Failed to read " + javaFile, e);
                }
              });
    }
  }

  /**
   * Helper method to extract JFR type references from generated interface code. Looks for patterns
   * like "JFRTypeName fieldName()" to find referenced types.
   */
  private Set<String> extractJfrTypeReferences(String content) {
    return content
        .lines()
        .filter(line -> line.trim().contains("JFR") && line.trim().endsWith("();"))
        .map(
            line -> {
              String trimmed = line.trim();
              // Extract type name (e.g., "JFRStackFrame" from "JFRStackFrame frames();")
              int jfrIndex = trimmed.indexOf("JFR");
              int spaceIndex = trimmed.indexOf(' ', jfrIndex);
              if (jfrIndex >= 0 && spaceIndex > jfrIndex) {
                String typePart = trimmed.substring(jfrIndex, spaceIndex);
                // Handle arrays
                return typePart.replace("[]", "");
              }
              return null;
            })
        .filter(type -> type != null && !type.equals("JFR"))
        .collect(Collectors.toSet());
  }
}

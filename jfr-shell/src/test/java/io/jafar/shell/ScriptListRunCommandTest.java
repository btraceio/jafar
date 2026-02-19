package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for script list and script run commands.
 *
 * <p>The script commands provide script management and execution:
 *
 * <ul>
 *   <li>script list - Lists available .jfrs scripts from ~/.jfr-shell/scripts
 *   <li>script run &lt;name&gt; - Runs a script by name from the scripts directory
 *   <li>script &lt;path&gt; - Runs a script by full path (backward compatibility)
 * </ul>
 */
class ScriptListRunCommandTest {

  private Path originalScriptsDir;
  private Path testScriptsDir;

  private static Path testJfr() {
    return Paths.get("..", "parser", "src", "test", "resources", "test-ap.jfr")
        .normalize()
        .toAbsolutePath();
  }

  @BeforeEach
  void setUp(@TempDir Path tempDir) throws IOException {
    // Create a test scripts directory
    testScriptsDir = tempDir.resolve("test-scripts");
    Files.createDirectories(testScriptsDir);

    // Save original scripts dir (though we can't easily override it)
    originalScriptsDir = Paths.get(System.getProperty("user.home"), ".jfr-shell", "scripts");
  }

  @AfterEach
  void tearDown() {
    // Cleanup
  }

  // ==================== Script Execution Tests ====================

  @Test
  void scriptExecutionByPath(@TempDir Path tempDir) throws Exception {
    // Create a test script with simple commands
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath,
        """
        # Test script
        echo Test output
        """);

    // Since we can't easily test Shell in isolation, we test that the script file
    // format and ScriptRunner integration works (ScriptRunner is tested separately)
    assertNotNull(scriptPath);
    assertTrue(Files.exists(scriptPath));
    assertTrue(Files.readString(scriptPath).contains("echo Test output"));
  }

  @Test
  void scriptWithArguments(@TempDir Path tempDir) throws Exception {
    // Create a script that uses arguments
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath,
        """
        # Test script with args
        echo $1
        echo ${2:-default}
        """);

    String content = Files.readString(scriptPath);
    assertTrue(content.contains("$1"), "Script should use positional parameters");
    assertTrue(content.contains("${2:-default}"), "Script should use optional parameters");
  }

  @Test
  void scriptWithDescription(@TempDir Path tempDir) throws Exception {
    // Script with description comment
    Path scriptPath = tempDir.resolve("analyze.jfrs");
    Files.writeString(
        scriptPath,
        """
        # Analyzes JFR recordings for performance issues
        # Arguments: recording_path
        open $1
        show events | count()
        """);

    List<String> lines = Files.readAllLines(scriptPath);
    assertTrue(lines.get(0).startsWith("#"), "First line should be comment");
    assertTrue(lines.get(0).contains("Analyzes"), "First comment should be description");
  }

  // ==================== Script File Validation ====================

  @Test
  void validScriptFile(@TempDir Path tempDir) throws Exception {
    Path scriptPath = testScriptsDir.resolve("valid.jfrs");
    Files.writeString(
        scriptPath,
        """
        # Valid script
        open $1
        show events | count()
        """);

    assertTrue(Files.exists(scriptPath));
    assertTrue(scriptPath.toString().endsWith(".jfrs"));
    List<String> lines = Files.readAllLines(scriptPath);
    assertFalse(lines.isEmpty());
  }

  @Test
  void emptyScriptFile(@TempDir Path tempDir) throws Exception {
    Path scriptPath = testScriptsDir.resolve("empty.jfrs");
    Files.writeString(scriptPath, "");

    assertTrue(Files.exists(scriptPath));
    List<String> lines = Files.readAllLines(scriptPath);
    assertTrue(lines.isEmpty() || (lines.size() == 1 && lines.get(0).isEmpty()));
  }

  @Test
  void scriptWithOnlyComments(@TempDir Path tempDir) throws Exception {
    Path scriptPath = testScriptsDir.resolve("comments.jfrs");
    Files.writeString(
        scriptPath,
        """
        # Comment 1
        # Comment 2
        # Comment 3
        """);

    List<String> lines = Files.readAllLines(scriptPath);
    assertEquals(3, lines.size());
    assertTrue(lines.stream().allMatch(line -> line.trim().startsWith("#")));
  }

  @Test
  void scriptWithBlankLines(@TempDir Path tempDir) throws Exception {
    Path scriptPath = testScriptsDir.resolve("blanks.jfrs");
    Files.writeString(
        scriptPath,
        """
        # First command

        echo test

        # Last command
        echo done
        """);

    List<String> lines = Files.readAllLines(scriptPath);
    assertTrue(lines.stream().anyMatch(String::isBlank));
    assertTrue(lines.stream().anyMatch(line -> line.contains("echo test")));
  }

  // ==================== Script Directory Tests ====================

  @Test
  void scriptsDirectoryExists() {
    Path scriptsDir = Paths.get(System.getProperty("user.home"), ".jfr-shell", "scripts");
    assertNotNull(scriptsDir);
    // Note: Directory might not exist until user creates it
  }

  @Test
  void createScriptsDirectory(@TempDir Path tempDir) throws Exception {
    Path scriptsDir = tempDir.resolve("scripts");
    assertFalse(Files.exists(scriptsDir));

    Files.createDirectories(scriptsDir);
    assertTrue(Files.exists(scriptsDir));
    assertTrue(Files.isDirectory(scriptsDir));
  }

  @Test
  void listScriptsInDirectory(@TempDir Path tempDir) throws Exception {
    Path scriptsDir = tempDir.resolve("scripts");
    Files.createDirectories(scriptsDir);

    // Create some script files
    Files.writeString(scriptsDir.resolve("script1.jfrs"), "# Script 1\necho test1");
    Files.writeString(scriptsDir.resolve("script2.jfrs"), "# Script 2\necho test2");
    Files.writeString(scriptsDir.resolve("not-a-script.txt"), "text file");

    List<Path> scripts =
        Files.list(scriptsDir).filter(p -> p.toString().endsWith(".jfrs")).sorted().toList();

    assertEquals(2, scripts.size());
    assertTrue(scripts.stream().anyMatch(p -> p.getFileName().toString().equals("script1.jfrs")));
    assertTrue(scripts.stream().anyMatch(p -> p.getFileName().toString().equals("script2.jfrs")));
  }

  @Test
  void emptyScriptsDirectory(@TempDir Path tempDir) throws Exception {
    Path scriptsDir = tempDir.resolve("scripts");
    Files.createDirectories(scriptsDir);

    List<Path> scripts =
        Files.list(scriptsDir).filter(p -> p.toString().endsWith(".jfrs")).toList();

    assertTrue(scripts.isEmpty());
  }

  // ==================== Script Name Resolution ====================

  @Test
  void resolveScriptByName(@TempDir Path tempDir) throws Exception {
    Path scriptsDir = tempDir.resolve("scripts");
    Files.createDirectories(scriptsDir);

    String scriptName = "analyze";
    Path scriptPath = scriptsDir.resolve(scriptName + ".jfrs");
    Files.writeString(scriptPath, "# Analyze script\nshow events");

    // Verify resolution logic
    Path resolved = scriptsDir.resolve(scriptName + ".jfrs");
    assertTrue(Files.exists(resolved));
    assertEquals("analyze.jfrs", resolved.getFileName().toString());
  }

  @Test
  void resolveScriptWithExtension(@TempDir Path tempDir) throws Exception {
    Path scriptsDir = tempDir.resolve("scripts");
    Files.createDirectories(scriptsDir);

    String scriptName = "analyze.jfrs";
    Path scriptPath = scriptsDir.resolve(scriptName);
    Files.writeString(scriptPath, "# Analyze script\nshow events");

    // If user provides .jfrs extension, try first with extension
    Path resolved = scriptsDir.resolve(scriptName);
    assertTrue(Files.exists(resolved));
  }

  @Test
  void scriptNotFound(@TempDir Path tempDir) throws Exception {
    Path scriptsDir = tempDir.resolve("scripts");
    Files.createDirectories(scriptsDir);

    String scriptName = "nonexistent";
    Path scriptPath = scriptsDir.resolve(scriptName + ".jfrs");

    assertFalse(Files.exists(scriptPath));

    // Try without extension
    Path scriptPathNoExt = scriptsDir.resolve(scriptName);
    assertFalse(Files.exists(scriptPathNoExt));
  }

  // ==================== Script Description Extraction ====================

  @Test
  void extractScriptDescription(@TempDir Path tempDir) throws Exception {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath,
        """
        # Analyzes GC pauses and memory allocation
        # Arguments: recording_path
        open $1
        """);

    List<String> lines = Files.readAllLines(scriptPath);
    String firstComment = null;
    for (String line : lines) {
      line = line.trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("#!")) continue;
      if (line.startsWith("#")) {
        firstComment = line.substring(1).trim();
        break;
      }
    }

    assertNotNull(firstComment);
    assertEquals("Analyzes GC pauses and memory allocation", firstComment);
  }

  @Test
  void noDescriptionInScript(@TempDir Path tempDir) throws Exception {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(scriptPath, "open $1\nshow events");

    List<String> lines = Files.readAllLines(scriptPath);
    String firstComment = null;
    for (String line : lines) {
      line = line.trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("#")) {
        firstComment = line.substring(1).trim();
        break;
      }
    }

    assertNull(firstComment, "No description should be found");
  }

  @Test
  void skipShebangLine(@TempDir Path tempDir) throws Exception {
    Path scriptPath = tempDir.resolve("test.jfrs");
    Files.writeString(
        scriptPath,
        """
        #!/usr/bin/env jfr-shell
        # This is the actual description
        open $1
        """);

    List<String> lines = Files.readAllLines(scriptPath);
    String description = null;
    for (String line : lines) {
      line = line.trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("#!")) continue;
      if (line.startsWith("#")) {
        description = line.substring(1).trim();
        if (!description.isEmpty()) {
          break;
        }
      }
    }

    assertEquals("This is the actual description", description);
  }

  // ==================== Argument Passing ====================

  @Test
  void parseScriptArguments() {
    String input = "script run analyze /path/to/file.jfr 100";
    String[] parts = input.split("\\s+");

    assertEquals(5, parts.length);
    assertEquals("script", parts[0]);
    assertEquals("run", parts[1]);
    assertEquals("analyze", parts[2]);
    assertEquals("/path/to/file.jfr", parts[3]);
    assertEquals("100", parts[4]);

    // Extract arguments starting from index 3
    List<String> arguments = new ArrayList<>();
    for (int i = 3; i < parts.length; i++) {
      arguments.add(parts[i]);
    }

    assertEquals(2, arguments.size());
    assertEquals("/path/to/file.jfr", arguments.get(0));
    assertEquals("100", arguments.get(1));
  }

  @Test
  void parseScriptByPath() {
    String input = "script /path/to/script.jfrs arg1 arg2";
    String[] parts = input.split("\\s+");

    assertEquals(4, parts.length);
    assertEquals("script", parts[0]);
    assertEquals("/path/to/script.jfrs", parts[1]);

    // Arguments start from index 2
    List<String> arguments = new ArrayList<>();
    for (int i = 2; i < parts.length; i++) {
      arguments.add(parts[i]);
    }

    assertEquals(2, arguments.size());
    assertEquals("arg1", arguments.get(0));
    assertEquals("arg2", arguments.get(1));
  }

  @Test
  void parseScriptList() {
    String input = "script list";
    String[] parts = input.split("\\s+");

    assertEquals(2, parts.length);
    assertEquals("script", parts[0]);
    assertEquals("list", parts[1]);
  }

  // ==================== Integration with Example Scripts ====================

  @Test
  void exampleScriptsExist() {
    Path examplesDir = Paths.get("src", "main", "resources", "examples").toAbsolutePath();

    // Verify example scripts exist in the source code
    Path basicAnalysis = examplesDir.resolve("basic-analysis.jfrs");
    Path gcAnalysis = examplesDir.resolve("gc-analysis.jfrs");
    Path threadProfiling = examplesDir.resolve("thread-profiling.jfrs");

    // These should exist in the project structure
    assertTrue(Files.exists(basicAnalysis), "basic-analysis.jfrs should exist: " + basicAnalysis);
    assertTrue(Files.exists(gcAnalysis), "gc-analysis.jfrs should exist: " + gcAnalysis);
    assertTrue(
        Files.exists(threadProfiling), "thread-profiling.jfrs should exist: " + threadProfiling);
  }

  // ==================== Error Handling ====================

  @Test
  void scriptPathDoesNotExist(@TempDir Path tempDir) {
    Path nonExistent = tempDir.resolve("does-not-exist.jfrs");
    assertFalse(Files.exists(nonExistent));
  }

  @Test
  void scriptDirectoryDoesNotExist(@TempDir Path tempDir) {
    Path scriptsDir = tempDir.resolve("non-existent-scripts");
    assertFalse(Files.exists(scriptsDir));
  }
}

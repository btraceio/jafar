package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JFRSessionTest {

  @Test
  void shouldRejectInvalidFile(@TempDir Path tempDir) throws IOException {
    // Create a file with invalid content (not a JFR file)
    Path invalidFile = tempDir.resolve("invalid.jfr");
    Files.writeString(invalidFile, "This is not a JFR file");

    // Should throw IOException when trying to open
    ParsingContext context = ParsingContext.create();
    IOException exception =
        assertThrows(
            IOException.class,
            () -> new JFRSession(invalidFile, context),
            "Should reject non-JFR files");

    // Verify error is about parsing failure (original message wrapped)
    assertTrue(
        exception.getMessage().contains("Error occurred while parsing")
            || exception.getMessage().contains("Invalid JFR Magic Number"),
        "Error message should indicate parsing failure, got: " + exception.getMessage());
  }

  @Test
  void shouldRejectPngFile(@TempDir Path tempDir) throws IOException {
    // Create a file with PNG header
    Path pngFile = tempDir.resolve("test.png");
    byte[] pngHeader = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    Files.write(pngFile, pngHeader);

    // Should throw IOException when trying to open
    ParsingContext context = ParsingContext.create();
    IOException exception =
        assertThrows(
            IOException.class, () -> new JFRSession(pngFile, context), "Should reject PNG files");

    // Verify error is about parsing failure (original message wrapped)
    assertTrue(
        exception.getMessage().contains("Error occurred while parsing")
            || exception.getMessage().contains("Invalid JFR Magic Number"),
        "Error message should indicate parsing failure, got: " + exception.getMessage());
  }
}

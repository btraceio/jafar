package io.jafar.mcp.validation;

import java.nio.file.Files;
import java.nio.file.Path;

/** Common file validation for MCP open tools. */
public final class FileValidator {

  private FileValidator() {}

  /** Returns {@code null} when valid, otherwise the exact user-facing error message. */
  public static String readableRegularFileError(Path path, String displayPath) {
    if (!Files.exists(path)) {
      return "File not found: " + displayPath;
    }
    if (!Files.isRegularFile(path)) {
      return "Not a file: " + displayPath;
    }
    if (!Files.isReadable(path)) {
      return "File not readable: " + displayPath;
    }
    return null;
  }
}

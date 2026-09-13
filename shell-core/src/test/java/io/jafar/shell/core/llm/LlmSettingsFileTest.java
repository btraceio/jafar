package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The settings file exists so a long-lived credential does not have to live in an environment
 * variable, which every child process inherits and which lands in crash dumps and CI logs.
 */
class LlmSettingsFileTest {

  @TempDir Path dir;

  private Path write(String content) throws IOException {
    Path file = dir.resolve("llm.properties");
    Files.writeString(file, content);
    try {
      Files.setPosixFilePermissions(
          file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystem; the permission tests below assume their own state anyway.
    }
    return file;
  }

  private static LlmConfig configWith(Map<String, String> shellVars, LlmSettingsFile file) {
    return new LlmConfig(shellVars::get, () -> Optional.ofNullable(file));
  }

  @Test
  void readsSettingsUsingTheSameNamesSetUses() throws Exception {
    LlmSettingsFile file =
        LlmSettingsFile.of(write("llm.backend=openai\nllm.api-key=sk-from-file\n"));

    assertEquals("openai", file.get("llm.backend"));
    assertEquals("sk-from-file", file.get("llm.api-key"));
    assertNull(file.get("llm.model"), "absent keys are null, not empty");
  }

  @Test
  void commentsAndBlankValuesAreIgnored() throws Exception {
    LlmSettingsFile file =
        LlmSettingsFile.of(write("# a comment\nllm.model=\nllm.backend=ollama\n"));

    assertNull(file.get("llm.model"), "a blank value is not a value");
    assertEquals("ollama", file.get("llm.backend"));
  }

  @Test
  void aFileOthersCanReadIsReportedRatherThanTrusted() throws Exception {
    Path file = write("llm.api-key=sk-exposed\n");
    try {
      Files.setPosixFilePermissions(
          file,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OTHERS_READ));
    } catch (UnsupportedOperationException e) {
      return; // Nothing to assert on a filesystem without POSIX permissions.
    }

    Optional<String> warning = LlmSettingsFile.of(file).warning();
    assertTrue(warning.isPresent(), "a world-readable key file must be called out");
    assertTrue(warning.get().contains("chmod 600"), warning.get());
  }

  @Test
  void aPrivateFileWarnsAboutNothing() throws Exception {
    assertTrue(LlmSettingsFile.of(write("llm.api-key=sk-private\n")).warning().isEmpty());
  }

  @Test
  void anUnreadableFileIsReportedNotSilentlyEmpty() {
    LlmSettingsFile missing = LlmSettingsFile.of(dir.resolve("does-not-exist.properties"));

    assertTrue(missing.warning().isPresent(), "an explicit path that is not there is a mistake");
    assertNull(missing.get("llm.api-key"));
  }

  // ── precedence ──────────────────────────────────────────────────────────────

  @Test
  void theFileSuppliesValuesNothingElseSets() throws Exception {
    LlmConfig config =
        configWith(Map.of(), LlmSettingsFile.of(write("llm.backend=ollama\nllm.model=qwen\n")));

    assertEquals("ollama", config.backendId());
    assertEquals("qwen", config.model());
    assertEquals(LlmConfig.Source.SETTINGS_FILE, config.sourceOf("llm.backend", "NO_SUCH_VAR"));
  }

  @Test
  void aSetCommandBeatsTheFile() throws Exception {
    LlmConfig config =
        configWith(
            Map.of("llm.backend", "anthropic"), LlmSettingsFile.of(write("llm.backend=ollama\n")));

    assertEquals("anthropic", config.backendId());
    assertEquals(LlmConfig.Source.SHELL_VARIABLE, config.sourceOf("llm.backend", "NO_SUCH_VAR"));
  }

  @Test
  void withNoFileTheDefaultsStillApply() {
    LlmConfig config = configWith(Map.of(), null);

    assertEquals("auto", config.backendId());
    assertEquals(LlmConfig.Source.DEFAULT, config.sourceOf("llm.backend", "NO_SUCH_VAR"));
    assertTrue(config.settingsFile().isEmpty());
  }
}

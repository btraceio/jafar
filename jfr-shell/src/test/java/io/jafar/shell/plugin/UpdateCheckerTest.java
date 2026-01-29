package io.jafar.shell.plugin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

  private PluginRegistry registry;
  private UpdateChecker checker;

  @BeforeEach
  void setUp() {
    registry = mock(PluginRegistry.class);
    checker = new UpdateChecker(registry);
  }

  @Test
  void detectsPatchUpdate() {
    // Mock installed plugin
    PluginMetadata installed =
        PluginMetadata.installed(
            "io.btrace", "jfr-shell-jdk", "0.9.0-SNAPSHOT", "local", Instant.now());

    // Mock registry with newer patch version
    PluginRegistry.PluginDefinition available =
        new PluginRegistry.PluginDefinition(
            "io.btrace", "jfr-shell-jdk", "0.9.1-SNAPSHOT", "local");

    when(registry.get("jdk")).thenReturn(Optional.of(available));

    // Check for updates
    List<UpdateChecker.PluginUpdate> updates = checker.checkForUpdates(Map.of("jdk", installed));

    assertEquals(1, updates.size());
    UpdateChecker.PluginUpdate update = updates.get(0);
    assertEquals("jdk", update.pluginId());
    assertEquals("0.9.0-SNAPSHOT", update.currentVersion());
    assertEquals("0.9.1-SNAPSHOT", update.availableVersion());
    assertTrue(update.shouldAutoInstall(), "Patch updates should auto-install");
  }

  @Test
  void detectsMinorUpdate() {
    PluginMetadata installed =
        PluginMetadata.installed("io.btrace", "jfr-shell-jdk", "0.9.0", "local", Instant.now());

    PluginRegistry.PluginDefinition available =
        new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "0.10.0", "local");

    when(registry.get("jdk")).thenReturn(Optional.of(available));

    List<UpdateChecker.PluginUpdate> updates = checker.checkForUpdates(Map.of("jdk", installed));

    assertEquals(1, updates.size());
    UpdateChecker.PluginUpdate update = updates.get(0);
    assertFalse(update.shouldAutoInstall(), "Minor updates should not auto-install");
  }

  @Test
  void detectsMajorUpdate() {
    PluginMetadata installed =
        PluginMetadata.installed("io.btrace", "jfr-shell-jdk", "0.9.0", "local", Instant.now());

    PluginRegistry.PluginDefinition available =
        new PluginRegistry.PluginDefinition("io.btrace", "jfr-shell-jdk", "1.0.0", "local");

    when(registry.get("jdk")).thenReturn(Optional.of(available));

    List<UpdateChecker.PluginUpdate> updates = checker.checkForUpdates(Map.of("jdk", installed));

    assertEquals(1, updates.size());
    assertFalse(updates.get(0).shouldAutoInstall(), "Major updates should not auto-install");
  }

  @Test
  void ignoresWhenNoUpdateAvailable() {
    PluginMetadata installed =
        PluginMetadata.installed(
            "io.btrace", "jfr-shell-jdk", "0.9.0-SNAPSHOT", "local", Instant.now());

    PluginRegistry.PluginDefinition available =
        new PluginRegistry.PluginDefinition(
            "io.btrace", "jfr-shell-jdk", "0.9.0-SNAPSHOT", "local");

    when(registry.get("jdk")).thenReturn(Optional.of(available));

    List<UpdateChecker.PluginUpdate> updates = checker.checkForUpdates(Map.of("jdk", installed));

    assertTrue(updates.isEmpty(), "Same version should not trigger update");
  }

  @Test
  void ignoresPluginNotInRegistry() {
    PluginMetadata installed =
        PluginMetadata.installed("io.btrace", "jfr-shell-custom", "1.0.0", "local", Instant.now());

    when(registry.get("custom")).thenReturn(Optional.empty());

    List<UpdateChecker.PluginUpdate> updates = checker.checkForUpdates(Map.of("custom", installed));

    assertTrue(updates.isEmpty(), "Plugin not in registry should be ignored");
  }

  @Test
  void handlesMultiplePlugins() {
    // Mock two installed plugins with different update scenarios
    PluginMetadata jdk =
        PluginMetadata.installed("io.btrace", "jfr-shell-jdk", "0.9.0", "local", Instant.now());

    PluginMetadata jafar =
        PluginMetadata.installed("io.btrace", "jfr-shell-jafar", "0.8.0", "local", Instant.now());

    // JDK has patch update, Jafar has minor update
    when(registry.get("jdk"))
        .thenReturn(
            Optional.of(
                new PluginRegistry.PluginDefinition(
                    "io.btrace", "jfr-shell-jdk", "0.9.1", "local")));

    when(registry.get("jafar"))
        .thenReturn(
            Optional.of(
                new PluginRegistry.PluginDefinition(
                    "io.btrace", "jfr-shell-jafar", "0.9.0", "local")));

    List<UpdateChecker.PluginUpdate> updates =
        checker.checkForUpdates(Map.of("jdk", jdk, "jafar", jafar));

    assertEquals(2, updates.size());

    // Verify both updates detected with correct types
    long autoInstallCount =
        updates.stream().filter(UpdateChecker.PluginUpdate::shouldAutoInstall).count();
    assertEquals(1, autoInstallCount, "Only patch update should auto-install");
  }
}

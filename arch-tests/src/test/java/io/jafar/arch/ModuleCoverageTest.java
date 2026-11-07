package io.jafar.arch;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Non-vacuity and drift guard for the architecture rules (AC-2.4).
 *
 * <ul>
 *   <li>(1) every covered module must import more than 0 classes — an empty import would make the
 *       rules vacuously pass for that module, including the Java-25 shell modules and the Java-8
 *       parser modules;
 *   <li>(2) every {@code include ':x'} in settings.gradle must be classified in a module group (R7
 *       drift guard): an unclassified module means the group lists in arch-tests/build.gradle went
 *       stale;
 *   <li>(3) prints the class count per module so coverage is visible in the build log.
 * </ul>
 *
 * <p>demo/ is a separate Gradle build and is not covered; recorded under R8 in doc/agents/Gates.md.
 */
class ModuleCoverageTest {

  /**
   * Modules exempt from classification. 'parser' is the published aggregate with no sources of its
   * own; 'arch-tests' is this module (boundary rules about boundary rules); any module named
   * 'test-*' is test tooling by the repository's naming convention (test-recordings, and future
   * test tooling modules) and sits outside the shipped module graph. The convention is the
   * exemption — modules are not enumerated here (R7).
   */
  private static final Set<String> EXEMPT_MODULES = new HashSet<>();

  static {
    EXEMPT_MODULES.add("parser");
    EXEMPT_MODULES.add("arch-tests");
  }

  @Test
  void everyCoveredModuleImportsClasses() {
    ModuleClasses classes = ModuleClasses.load();
    for (String module : classes.modules()) {
      int count = classes.classCount(module);
      if (count <= 0) {
        fail(
            "module '"
                + module
                + "' imported 0 classes from "
                + classes.dirOf(module)
                + " — the architecture rules would vacuously pass for it");
      }
      // (3) coverage evidence: the class count per module, visible in the build log
      System.out.println(
          "arch-coverage " + module + ": " + count + " classes (" + classes.dirOf(module) + ")");
    }
  }

  @Test
  void everySettingsIncludeIsClassifiedOrExempt() {
    assertClassified(readIncludes(), ModuleClasses.load().groups());
  }

  /**
   * Permanent fire test (R5): the drift check must fail by name on an include that no group
   * classifies. Runs check (2) against a copy of the real settings file with an extra {@code
   * include ':zz'} — the groups are the real ones, so the failure must name 'zz'.
   */
  @Test
  void unclassifiedIncludeFires() throws IOException {
    Path settings = settingsPath();
    String withExtra = new String(Files.readAllBytes(settings), UTF_8) + "\ninclude ':zz'\n";
    Path temp = Files.createTempFile("jafar-arch-fire-settings", ".gradle");
    Files.write(temp, withExtra.getBytes(UTF_8));
    try {
      List<String> includes = ModuleClasses.readIncludes(temp);
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> assertClassified(includes, ModuleClasses.load().groups()));
      assertTrue(
          failure.getMessage().contains("classify zz in arch-tests/build.gradle"),
          "expected the failure to name the unclassified module, got: " + failure.getMessage());
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static Path settingsPath() {
    String property = System.getProperty("jafar.arch.settings");
    if (property == null || property.isEmpty()) {
      fail(
          "jafar.arch.settings system property is missing — pass it from arch-tests/build.gradle"
              + " (R3: no silent default)");
    }
    return Paths.get(property);
  }

  private static List<String> readIncludes() {
    return ModuleClasses.readIncludes(settingsPath());
  }

  private static void assertClassified(
      List<String> includes, Map<String, ? extends Set<String>> groups) {
    Set<String> classified = new HashSet<>();
    for (Set<String> members : groups.values()) {
      classified.addAll(members);
    }
    for (String include : includes) {
      if (!classified.contains(include) && !isExempt(include)) {
        throw new IllegalStateException(
            "module '"
                + include
                + "' is included in settings.gradle but no module group classifies it: classify "
                + include
                + " in arch-tests/build.gradle");
      }
    }
  }

  private static boolean isExempt(String module) {
    return EXEMPT_MODULES.contains(module) || module.startsWith("test-");
  }
}

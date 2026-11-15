package io.jafar.arch;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import io.jafar.arch.fixture.ReachIntoConsumersFixture;
import io.jafar.arch.fixture.ReachIntoHdumpImplFixture;
import io.jafar.arch.fixture.ReachIntoParserImplFixture;
import io.jafar.parser.api.archfixture.ApiToImplFixture;
import io.jafar.parser.impl.archfixture.StaticCounterFixture;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Permanent fire tests (R5, AC-2.3): every rule must fail, naming itself, on a synthetic violator,
 * and the frozen store must not grow in CI mode.
 *
 * <p>The violators are the fixture classes compiled in this module ({@code io.jafar.arch.fixture},
 * {@code io.jafar.parser.api.archfixture}, {@code io.jafar.parser.impl.archfixture}). Each fire
 * test loads the REAL module classes with ONE module's class directory redirected to a temp copy
 * that additionally contains the fixture — so the fixture is mapped into that module exactly like
 * its real classes, and every other module's real classes are present, keeping the rule's checked
 * set non-empty.
 *
 * <p>The CI-mode test proves the freeze configuration cannot add violations: it copies the
 * committed store to a temp dir, points {@code freeze.store.default.path} at it with store creation
 * and refreeze off, and asserts the frozen rule (a) fails on the violator while the temp store
 * stays byte-identical, and passes without the violator.
 */
class ArchRuleFireTest {

  @AfterEach
  void resetArchConfiguration() {
    // The CI-mode test repoints the freeze store and the fixture loads repoint module
    // properties; reset() reloads archunit.properties (plus any archunit.* system properties),
    // so no fire test leaks configuration into another test.
    ArchConfiguration.get().reset();
  }

  @Test
  void ruleAFiresOnShellClassReachingIntoParserImpl() throws IOException {
    ModuleClasses modules = loadWithFixture("jfr-shell", ReachIntoParserImplFixture.class);
    assertFires(ArchitectureRules.ruleA(modules), "a:", ReachIntoParserImplFixture.class, modules);
  }

  @Test
  void ruleBFiresOnPprofClassReachingIntoHdumpImpl() throws IOException {
    ModuleClasses modules = loadWithFixture("pprof-parser", ReachIntoHdumpImplFixture.class);
    assertFires(ArchitectureRules.ruleB(modules), "b:", ReachIntoHdumpImplFixture.class, modules);
  }

  @Test
  void ruleCFiresOnApiClassDependingOnImpl() throws IOException {
    ModuleClasses modules = loadWithFixture("parser-core", ApiToImplFixture.class);
    boolean fired = false;
    for (ArchRule rule : ArchitectureRules.ruleC(modules)) {
      try {
        rule.check(modules.classes());
      } catch (AssertionError failure) {
        // These rules run unfrozen, so rules for other modules fail on their pre-existing
        // violations (the ones ArchitectureRulesTest freezes); only a failure naming the
        // fixture proves that rule (c) fires.
        if (failure.getMessage().contains(ApiToImplFixture.class.getSimpleName())) {
          fired = true;
        }
      }
    }
    assertTrue(fired, "expected a rule (c) to fire on " + ApiToImplFixture.class.getName());
  }

  @Test
  void ruleDFiresOnShellCoreClassDependingOnConsumer() throws IOException {
    ModuleClasses modules = loadWithFixture("shell-core", ReachIntoConsumersFixture.class);
    assertFires(ArchitectureRules.ruleD(modules), "d:", ReachIntoConsumersFixture.class, modules);
  }

  @Test
  void ruleEFiresOnMutableStaticFieldInParserImpl() throws IOException {
    ModuleClasses modules = loadWithFixture("parser-core", StaticCounterFixture.class);
    assertFires(ArchitectureRules.ruleE(modules), "e:", StaticCounterFixture.class, modules);
  }

  /**
   * CI mode (AC-2.3): the committed store is copied to a temp dir and used with store creation and
   * refreeze off. The frozen rule (a) over the real classes plus the violator fails and the temp
   * store is byte-identical afterwards; without the violator it passes (and still does not write).
   */
  @Test
  void frozenStoreCannotGrowInCiMode() throws IOException {
    Path tempStore = copyCommittedStoreToTempDir();
    Map<String, String> before = snapshot(tempStore);
    ArchConfiguration config = ArchConfiguration.get();
    config.setProperty("freeze.store.default.path", tempStore.toString());
    config.setProperty("freeze.store.default.allowStoreCreation", "false");
    config.setProperty("freeze.refreeze", "false");
    try {
      ModuleClasses withViolator = loadWithFixture("jfr-shell", ReachIntoParserImplFixture.class);
      assertFires(
          FreezingArchRule.freeze(ArchitectureRules.ruleA(withViolator)),
          "a:",
          ReachIntoParserImplFixture.class,
          withViolator);
      assertEquals(before, snapshot(tempStore), "a failing CI-mode run must not write the store");

      ModuleClasses real = ModuleClasses.load();
      FreezingArchRule.freeze(ArchitectureRules.ruleA(real)).check(real.classes());
      assertEquals(
          before, snapshot(tempStore), "a passing CI-mode run must not write the store either");
    } finally {
      ArchConfiguration.get().reset();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private static void assertFires(
      ArchRule rule, String letter, Class<?> fixture, ModuleClasses modules) {
    AssertionError failure =
        assertThrows(AssertionError.class, () -> rule.check(modules.classes()));
    assertTrue(
        failure.getMessage().contains(letter),
        "expected the failure to name the rule '" + letter + "', got: " + failure.getMessage());
    assertTrue(
        failure.getMessage().contains(fixture.getSimpleName()),
        "expected the failure to name the fixture "
            + fixture.getName()
            + ", got: "
            + failure.getMessage());
  }

  /**
   * Loads the real module classes, with {@code module}'s class directory redirected to a temp copy
   * that additionally contains the compiled fixture classes — so the fixtures are mapped into that
   * module exactly like its real classes (the membership, not the package, decides which rule
   * covers them). The module property is restored and the temp dir deleted afterwards; the import
   * is complete and in memory by then.
   */
  private static ModuleClasses loadWithFixture(String module, Class<?>... fixtures)
      throws IOException {
    String property = "jafar.arch.module." + module;
    String realDir = System.getProperty(property);
    if (realDir == null || realDir.isEmpty()) {
      throw new IllegalStateException(
          property + " is missing — pass it from arch-tests/build.gradle (R3: no silent default)");
    }
    Path temp = Files.createTempDirectory("jafar-arch-fire-" + module.replace('-', '_') + ".");
    System.setProperty(property, temp.toString());
    try {
      copyTree(Paths.get(realDir), temp);
      for (Class<?> fixture : fixtures) {
        String resource = fixture.getName().replace('.', '/') + ".class";
        try (InputStream in =
            ArchRuleFireTest.class.getClassLoader().getResourceAsStream(resource)) {
          if (in == null) {
            throw new IllegalStateException("fixture class resource not found: " + resource);
          }
          Path target = temp.resolve(resource);
          Files.createDirectories(target.getParent());
          Files.copy(in, target);
        }
      }
      return ModuleClasses.load();
    } finally {
      System.setProperty(property, realDir);
      deleteTree(temp);
    }
  }

  private static Path copyCommittedStoreToTempDir() throws IOException {
    String configured = ArchConfiguration.get().getProperty("freeze.store.default.path");
    if (configured == null || configured.isEmpty()) {
      throw new IllegalStateException("freeze.store.default.path is not configured");
    }
    Path committed = Paths.get(configured);
    if (!Files.isDirectory(committed)) {
      throw new IllegalStateException(
          "the committed violation store is missing at "
              + committed.toAbsolutePath()
              + " — generate it with ./gradlew :arch-tests:test -ParchFreeze");
    }
    Path temp = Files.createTempDirectory("jafar-arch-fire-store.");
    copyTree(committed, temp);
    return temp;
  }

  /** Every regular file under {@code dir}, relative path → UTF-8 content, for byte comparison. */
  private static Map<String, String> snapshot(Path dir) throws IOException {
    Map<String, String> snapshot = new TreeMap<>();
    List<Path> files;
    try (var paths = Files.walk(dir)) {
      files = paths.filter(Files::isRegularFile).toList();
    }
    for (Path file : files) {
      snapshot.put(dir.relativize(file).toString(), new String(Files.readAllBytes(file), UTF_8));
    }
    return snapshot;
  }

  private static void copyTree(Path source, Path target) throws IOException {
    if (!Files.isDirectory(source)) {
      throw new IllegalStateException("directory to copy does not exist: " + source);
    }
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(target.resolve(source.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(file, target.resolve(source.relativize(file)));
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void deleteTree(Path dir) {
    try {
      List<Path> paths;
      try (var walk = Files.walk(dir)) {
        paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
      }
      for (Path path : paths) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot delete " + dir, e);
    }
  }
}

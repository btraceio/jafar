package io.jafar.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A2 (doc/agents/Architecture.md, "Architectural invariants"): the internal packages are contracts,
 * not walls. {@code io.jafar.parser.internal_api} is deliberately shared by the parser modules,
 * shell-core, the Jafar shell backend and tools; a module's {@code impl} / {@code internal} /
 * {@code index} packages are nobody else's business. Both facts are load bearing: an agent that
 * "fixes" an internal_api import as a layering violation, or starts importing another module's
 * internals, breaks the architecture.
 */
public final class InternalApiContractTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importAllMainSources() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.jafar");
  }

  @Test
  void parserInternalApiIsUsedOnlyByItsWhitelistedConsumers() {
    // The whitelist is the actual consumer list: the parser modules (including the
    // io.jafar.utils TypeGenerator that lives in parser-core), the shells and their
    // backends, tools, and jfr2pprof. (The demo project is a separate Gradle build and
    // also uses it, but is not on this classpath; its use is unchecked here.)
    noClasses()
        .that()
        .resideOutsideOfPackages(
            "io.jafar.parser..",
            "io.jafar.utils..",
            "io.jafar.shell..",
            "io.jafar.tools..",
            "io.jafar.jfr2pprof..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("io.jafar.parser.internal_api..")
        .because(
            "io.jafar.parser.internal_api is a cross-module internal contract with a "
                + "fixed consumer list; extending it is an architecture decision, "
                + "not a convenience import (doc/agents/Architecture.md, invariant A2)")
        .check(classes);
  }

  @Test
  void hdumpInternalsAreConfinedToTheHdumpModules() {
    noClasses()
        .that()
        .resideOutsideOfPackages("io.jafar.hdump..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "io.jafar.hdump.impl..", "io.jafar.hdump.internal..", "io.jafar.hdump.index..")
        .because(
            "hdump implementation packages are private to hdump-parser and hdump-shell "
                + "(doc/agents/Architecture.md, invariant A2)")
        .check(classes);
  }

  @Test
  void pprofInternalsAreConfinedToThePprofModules() {
    noClasses()
        .that()
        .resideOutsideOfPackages("io.jafar.pprof..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("io.jafar.pprof.internal..")
        .because(
            "pprof implementation packages are private to pprof-parser and pprof-shell "
                + "(doc/agents/Architecture.md, invariant A2)")
        .check(classes);
  }

  @Test
  void otlpInternalsAreConfinedToTheOtlpModules() {
    noClasses()
        .that()
        .resideOutsideOfPackages("io.jafar.otlp..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("io.jafar.otlp.internal..")
        .because(
            "otlp implementation packages are private to otlp-parser and otlp-shell "
                + "(doc/agents/Architecture.md, invariant A2)")
        .check(classes);
  }
}

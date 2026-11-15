package io.jafar.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A3/A4 (doc/agents/Architecture.md, "Architectural invariants"): every extension point is a
 * ServiceLoader-discovered SPI ({@code JfrBackend}, {@code ShellModule}, {@code LlmBackend}, {@code
 * HandlerFactory}, {@code DeserializerFactory}, {@code UntypedEventStreamFactory}). The SPI package
 * never depends on implementations; backends are discovered, never hard-wired.
 */
public final class SpiRulesTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importAllMainSources() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.jafar");
  }

  /** The SPI types: canaries against accidental conversion to classes or enums. */
  private static final List<String> SPI_TYPES =
      List.of(
          "io.jafar.shell.backend.JfrBackend",
          "io.jafar.shell.core.ShellModule",
          "io.jafar.shell.core.llm.LlmBackend",
          "io.jafar.parser.api.HandlerFactory",
          "io.jafar.parser.internal_api.DeserializerFactory",
          "io.jafar.parser.internal_api.UntypedEventStreamFactory");

  @Test
  void spiTypesRemainInterfaces() {
    for (String fqn : SPI_TYPES) {
      classes()
          .that()
          .haveFullyQualifiedName(fqn)
          .should()
          .beInterfaces()
          .because(
              fqn
                  + " is a ServiceLoader-discovered SPI; converting it to a class "
                  + "breaks every external implementation "
                  + "(doc/agents/Architecture.md, invariant A3)")
          .check(classes);
    }
  }

  @Test
  void backendSpiDoesNotDependOnBackendImplementations() {
    noClasses()
        .that()
        .resideInAPackage("io.jafar.shell.backend..")
        .and()
        .resideOutsideOfPackage("io.jafar.shell.backend.impl..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("io.jafar.shell.backend.impl..")
        .because(
            "backend implementations live in plugin modules and are discovered via "
                + "ServiceLoader; the SPI must not know them "
                + "(doc/agents/Architecture.md, invariant A3)")
        .check(classes);
  }

  @Test
  void llmSpiDoesNotDependOnLlmBackends() {
    noClasses()
        .that()
        .resideInAPackage("io.jafar.shell.core.llm..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("io.jafar.shell.llm..")
        .because(
            "LLM backends (llm-anthropic, llm-openai) are discovered via "
                + "ServiceLoader; shell-core must not reference them "
                + "(doc/agents/Architecture.md, invariant A3)")
        .check(classes);
  }
}

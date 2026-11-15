package io.jafar.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static io.jafar.arch.SignatureLeaks.notExposeInPublicSignatures;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A1 (doc/agents/Architecture.md, "Architectural invariants"): the {@code api} package of a module
 * is its public surface. Implementation types ({@code impl}, {@code internal}, {@code
 * internal_api}, {@code index}) may be constructed inside method bodies — the factory-in-api
 * pattern — but must never appear in public or protected signatures.
 *
 * <p>parser-core is the documented exception (A5): its api shares the {@code internal_api} metadata
 * model ({@code MetadataClass} in {@code JafarRecordedEvent.type()}, the hybrid {@code
 * ParserContext}), so for parser-core only the {@code impl} package is forbidden in signatures.
 */
public final class ApiSurfacePurityTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importAllMainSources() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.jafar");
  }

  @Test
  void hdumpApiDoesNotExposeImplementationTypes() {
    apiPurity(
            "io.jafar.hdump.api",
            "io.jafar.hdump.impl..",
            "io.jafar.hdump.internal..",
            "io.jafar.hdump.index..")
        .check(classes);
  }

  @Test
  void pprofApiDoesNotExposeImplementationTypes() {
    apiPurity("io.jafar.pprof.api", "io.jafar.pprof.internal..").check(classes);
  }

  @Test
  void otlpApiDoesNotExposeImplementationTypes() {
    apiPurity("io.jafar.otlp.api", "io.jafar.otlp.internal..").check(classes);
  }

  @Test
  void parserApiDoesNotExposeImplTypes() {
    apiPurity("io.jafar.parser.api", "io.jafar.parser.impl..").check(classes);
  }

  private static ArchRule apiPurity(String apiPackage, String... forbidden) {
    return classes()
        .that()
        .resideInAPackage(apiPackage)
        .should(notExposeInPublicSignatures(forbidden))
        .because(
            apiPackage
                + " is the module's public surface; implementation types may be "
                + "constructed in method bodies but must not appear in signatures "
                + "(doc/agents/Architecture.md, invariant A1)");
  }
}

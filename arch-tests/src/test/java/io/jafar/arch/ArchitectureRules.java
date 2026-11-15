package io.jafar.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClass.Predicates;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The module-boundary rules (a)–(e), one static factory per rule. Every factory takes the {@link
 * ModuleClasses} membership, so the identical rule runs on the real module classes
 * (ArchitectureRulesTest) and on fixture-augmented imports (ArchRuleFireTest) — module membership,
 * not package names, decides what a rule covers, because packages overlap across modules.
 *
 * <p>Each rule's description starts with its letter ("a: …") so a failure names the rule that
 * fired; the descriptions also key the frozen violation store sections, so they must stay stable —
 * changing one invalidates its store section.
 */
public final class ArchitectureRules {

  /**
   * Rule (c) covers the modules that own a public api package plus implementation packages of their
   * own. parser-codegen is deliberately absent: its whole purpose is generating classes INTO {@code
   * io.jafar.parser.impl}, and it sits in the parser plane with parser-core, so rules (a)/(c) would
   * be contradictory for it. The package root maps each module to its {@code io.jafar.<root>}
   * namespace; patterns for packages a module does not have simply match nothing ("where those
   * exist").
   */
  private static final Map<String, String> MODULE_PACKAGE_ROOT = new LinkedHashMap<>();

  static {
    MODULE_PACKAGE_ROOT.put("parser-core", "io.jafar.parser");
    MODULE_PACKAGE_ROOT.put("hdump-parser", "io.jafar.hdump");
    MODULE_PACKAGE_ROOT.put("pprof-parser", "io.jafar.pprof");
    MODULE_PACKAGE_ROOT.put("otlp-parser", "io.jafar.otlp");
  }

  private ArchitectureRules() {}

  /**
   * (a) Classes not residing in the parser plane (parser-core, parser-codegen) must not depend on
   * {@code io.jafar.parser.impl..} or {@code io.jafar.parser.internal_api..}.
   */
  public static ArchRule ruleA(ModuleClasses modules) {
    return noClasses()
        .that(not(modules.residesInGroup("parserPlane")))
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("io.jafar.parser.impl..", "io.jafar.parser.internal_api..")
        .as(
            "a: classes outside the parser plane (parser-core, parser-codegen) must not depend on"
                + " io.jafar.parser.impl.. or io.jafar.parser.internal_api..");
  }

  /**
   * (b) Classes not residing in hdump-parser must not depend on {@code io.jafar.hdump.impl..},
   * {@code io.jafar.hdump.internal..} or {@code io.jafar.hdump.index..}.
   */
  public static ArchRule ruleB(ModuleClasses modules) {
    return noClasses()
        .that(not(modules.residesIn("hdump-parser")))
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "io.jafar.hdump.impl..", "io.jafar.hdump.internal..", "io.jafar.hdump.index..")
        .as(
            "b: classes outside hdump-parser must not depend on io.jafar.hdump.impl..,"
                + " io.jafar.hdump.internal.. or io.jafar.hdump.index..");
  }

  /**
   * (c) Per module in {parser-core, hdump-parser, pprof-parser, otlp-parser}: classes in that
   * module's {@code ..api..} packages must not depend on that module's implementation packages. One
   * rule per module, so each has its own store section.
   */
  public static List<ArchRule> ruleC(ModuleClasses modules) {
    List<ArchRule> rules = new ArrayList<>();
    for (Map.Entry<String, String> entry : MODULE_PACKAGE_ROOT.entrySet()) {
      String module = entry.getKey();
      String root = entry.getValue();
      DescribedPredicate<JavaClass> inModule = modules.residesIn(module);
      rules.add(
          noClasses()
              .that(inModule.and(Predicates.resideInAPackage(root + ".api..")))
              .should()
              .dependOnClassesThat(
                  inModule.and(
                      Predicates.resideInAnyPackage(
                          root + ".impl..",
                          root + ".internal..",
                          root + ".internal_api..",
                          root + ".index..")))
              .as(
                  "c: classes in "
                      + root
                      + ".api.. must not depend on "
                      + module
                      + " implementation packages ("
                      + root
                      + ".impl.., "
                      + root
                      + ".internal.., "
                      + root
                      + ".internal_api.., "
                      + root
                      + ".index..)"));
    }
    return rules;
  }

  /**
   * (d) Classes residing in the parser modules or shell-core must not depend on classes residing in
   * consumer modules ({@code shells → shell-core → parser}, never the reverse).
   */
  public static ArchRule ruleD(ModuleClasses modules) {
    return noClasses()
        .that(modules.residesInGroup("parserModules").or(modules.residesInGroup("shellCore")))
        .should()
        .dependOnClassesThat(modules.residesInGroup("consumers"))
        .as(
            "d: classes residing in parser modules or shell-core must not depend on classes"
                + " residing in consumer modules");
  }

  /**
   * (e) Classes residing in parser modules in non-api packages must not declare a field that is
   * static and not final. Synthetic fields are ignored.
   */
  public static ArchRule ruleE(ModuleClasses modules) {
    return noClasses()
        .that(
            modules
                .residesInGroup("parserModules")
                .and(Predicates.resideOutsideOfPackage("..api..")))
        .should(declareMutableStaticFields())
        .as(
            "e: classes residing in parser modules in non-api packages must not declare a field"
                + " that is static and not final (synthetic fields are ignored)");
  }

  /**
   * Reports the forbidden property, phrased positively: with {@code noClasses().should(...)},
   * ArchUnit's NeverCondition inverts the events, so the rule fails exactly when a class satisfies
   * this condition (empirically verified — a "violated" event under noClasses() is inverted into a
   * pass).
   */
  private static ArchCondition<JavaClass> declareMutableStaticFields() {
    return new ArchCondition<JavaClass>("declare a field that is static and not final") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        for (JavaField field : javaClass.getFields()) {
          Set<JavaModifier> modifiers = field.getModifiers();
          if (modifiers.contains(JavaModifier.SYNTHETIC)) {
            // compiler-generated (e.g. this$0), not a hand-written field
            continue;
          }
          if (modifiers.contains(JavaModifier.STATIC) && !modifiers.contains(JavaModifier.FINAL)) {
            events.add(
                SimpleConditionEvent.satisfied(
                    javaClass,
                    "declares static non-final field '"
                        + field.getName()
                        + "' in "
                        + javaClass.getName()));
          }
        }
      }
    };
  }
}

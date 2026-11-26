package io.jafar.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClass.Predicates;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
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
 * The architecture rules (a)–(j), one static factory per rule. Every factory takes the {@link
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

  /**
   * Rule (f) uses the same module keys as rule (c) plus the per-module implementation packages
   * whose types must not appear in api signatures. parser-core includes {@code internal_api} — the
   * same reach-ins rule (c) freezes as debt, sharpened to the signature level.
   */
  private static final Map<String, List<String>> MODULE_IMPL_PACKAGES = new LinkedHashMap<>();

  static {
    MODULE_IMPL_PACKAGES.put(
        "parser-core", List.of("io.jafar.parser.impl..", "io.jafar.parser.internal_api.."));
    MODULE_IMPL_PACKAGES.put(
        "hdump-parser",
        List.of("io.jafar.hdump.impl..", "io.jafar.hdump.internal..", "io.jafar.hdump.index.."));
    MODULE_IMPL_PACKAGES.put("pprof-parser", List.of("io.jafar.pprof.internal.."));
    MODULE_IMPL_PACKAGES.put("otlp-parser", List.of("io.jafar.otlp.internal.."));
  }

  /**
   * Rule (h): the ServiceLoader SPIs — every extension point of the shells and the parser is
   * discovered through one of these interfaces; converting any of them to a class or enum breaks
   * every external implementation at once.
   */
  private static final List<String> SPI_TYPES =
      List.of(
          "io.jafar.shell.backend.JfrBackend",
          "io.jafar.shell.core.ShellModule",
          "io.jafar.shell.core.llm.LlmBackend",
          "io.jafar.parser.api.HandlerFactory",
          "io.jafar.parser.internal_api.DeserializerFactory",
          "io.jafar.parser.internal_api.UntypedEventStreamFactory");

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
   * (f) Per module in {parser-core, hdump-parser, pprof-parser, otlp-parser}: classes in that
   * module's {@code ..api..} packages must not expose that module's implementation types in public
   * or protected signatures — parameters, returns, throws, fields, supertypes, generic arguments
   * included. Factory method bodies may construct impl classes behind the api type (the
   * factory-in-api pattern), so this is a signature rule, not a dependency rule: rule (c) governs
   * dependencies, this sharpens the api-facing half. One rule per module, so each has its own store
   * section.
   */
  public static List<ArchRule> ruleF(ModuleClasses modules) {
    List<ArchRule> rules = new ArrayList<>();
    for (Map.Entry<String, String> entry : MODULE_PACKAGE_ROOT.entrySet()) {
      String module = entry.getKey();
      String root = entry.getValue();
      List<String> forbidden = MODULE_IMPL_PACKAGES.get(module);
      DescribedPredicate<JavaClass> inModule = modules.residesIn(module);
      rules.add(
          classes()
              .that(inModule.and(Predicates.resideInAPackage(root + ".api..")))
              .should(notExposeImplementationTypesInPublicSignatures(forbidden))
              .as(
                  "f: classes in "
                      + root
                      + ".api.. must not expose "
                      + module
                      + " implementation types ("
                      + String.join(", ", forbidden)
                      + ") in public or protected signatures"));
    }
    return rules;
  }

  /**
   * (g) Per module in {pprof-parser, otlp-parser}: classes outside the module must not depend on
   * its {@code internal..} package — the pprof/otlp analogue of rule (b).
   */
  public static List<ArchRule> ruleG(ModuleClasses modules) {
    List<ArchRule> rules = new ArrayList<>();
    for (String module : List.of("pprof-parser", "otlp-parser")) {
      String root = MODULE_PACKAGE_ROOT.get(module);
      rules.add(
          noClasses()
              .that(not(modules.residesIn(module)))
              .should()
              .dependOnClassesThat()
              .resideInAPackage(root + ".internal..")
              .as("g: classes outside " + module + " must not depend on " + root + ".internal.."));
    }
    return rules;
  }

  /**
   * (h) The ServiceLoader SPI types must remain interfaces. Checked unfrozen in the fire test via
   * the parameterized factory; the production rule runs over the real module classes frozen like
   * every other rule (green today, and a converted SPI type must fail loudly, not be
   * grandfatherable by accident).
   */
  public static ArchRule ruleH() {
    return spiTypesRemainInterfaces(SPI_TYPES);
  }

  /**
   * The (h) rule body, parameterized by fully qualified names so the fire test can aim it at a
   * fixture class instead of a real SPI type (a fixture cannot make a real interface a class).
   */
  public static ArchRule spiTypesRemainInterfaces(List<String> fqns) {
    return classes()
        .that(
            DescribedPredicate.describe(
                "are one of the listed SPI types", javaClass -> fqns.contains(javaClass.getName())))
        .should()
        .beInterfaces()
        .allowEmptyShould(false)
        .as("h: the ServiceLoader SPI types must remain interfaces");
  }

  /**
   * (i) The ServiceLoader SPIs never know their implementations. Backend and LLM implementations
   * live in plugin modules ({@code ..backend.impl..}, {@code io.jafar.shell.llm..}) and are
   * discovered at runtime; a hard reference from the SPI packages defeats the plugin architecture.
   * Package-based on purpose: the SPI convention is named by package across modules, and module
   * membership would blur it.
   */
  public static List<ArchRule> ruleI() {
    return List.of(
        noClasses()
            .that(
                Predicates.resideInAPackage("io.jafar.shell.backend..")
                    .and(not(Predicates.resideInAPackage("io.jafar.shell.backend.impl.."))))
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.jafar.shell.backend.impl..")
            .as(
                "i: classes in io.jafar.shell.backend.. outside io.jafar.shell.backend.impl.. must"
                    + " not depend on io.jafar.shell.backend.impl.."),
        noClasses()
            .that(Predicates.resideInAPackage("io.jafar.shell.core.llm.."))
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.jafar.shell.llm..")
            .as("i: classes in io.jafar.shell.core.llm.. must not depend on io.jafar.shell.llm.."));
  }

  /**
   * (j) No class in a covered module formats with the JVM default locale. On a ru-RU or de-DE host
   * those calls wrote {@code "47,8%"} next to a JSON number {@code 47.8} (issue #224), under ar-EG
   * {@code %d} produced Arabic-Indic digits (jafar-processor then generated uncompilable source),
   * and date patterns localize month and day names. Pass a {@code Locale}: {@code Locale.ROOT} for
   * anything a program, an agent or a bug report reads. Calls, method references and constructor
   * references all count, and an owner matches with its subtypes ({@code DecimalFormat.getInstance}
   * is {@code NumberFormat.getInstance}). Not frozen: there is no grandfathered debt.
   */
  public static ArchRule ruleJ() {
    return classes()
        .should(notFormatWithTheDefaultLocale())
        .as(
            "j: no class formats with the JVM default locale (String.format/formatted,"
                + " PrintStream/PrintWriter/Console printf/format, MessageFormat, NumberFormat,"
                + " DecimalFormat, Formatter, DateTimeFormatter.ofPattern and SimpleDateFormat"
                + " without a Locale)");
  }

  /** One forbidden member: owner (matched with subtypes), name, exact raw parameter types. */
  private record DefaultLocaleMember(Class<?> owner, String name, List<String> params) {
    boolean matches(JavaClass targetOwner, String targetName, List<String> targetParams) {
      return targetName.equals(name)
          && targetParams.equals(params)
          && targetOwner.isAssignableTo(owner);
    }
  }

  private static DefaultLocaleMember member(Class<?> owner, String name, Class<?>... params) {
    List<String> names = new ArrayList<>();
    for (Class<?> p : params) {
      names.add(p.getName());
    }
    return new DefaultLocaleMember(owner, name, names);
  }

  /** The default-locale overloads rule (j) forbids; constructors are named {@code <init>}. */
  static final List<DefaultLocaleMember> DEFAULT_LOCALE_MEMBERS =
      List.of(
          member(String.class, "format", String.class, Object[].class),
          member(String.class, "formatted", Object[].class),
          member(java.io.PrintStream.class, "printf", String.class, Object[].class),
          member(java.io.PrintStream.class, "format", String.class, Object[].class),
          member(java.io.PrintWriter.class, "printf", String.class, Object[].class),
          member(java.io.PrintWriter.class, "format", String.class, Object[].class),
          member(java.io.Console.class, "printf", String.class, Object[].class),
          member(java.io.Console.class, "format", String.class, Object[].class),
          member(java.text.MessageFormat.class, "format", String.class, Object[].class),
          member(java.text.MessageFormat.class, "<init>", String.class),
          member(java.text.NumberFormat.class, "getInstance"),
          member(java.text.NumberFormat.class, "getNumberInstance"),
          member(java.text.NumberFormat.class, "getPercentInstance"),
          member(java.text.NumberFormat.class, "getIntegerInstance"),
          member(java.text.NumberFormat.class, "getCurrencyInstance"),
          member(java.text.NumberFormat.class, "getCompactNumberInstance"),
          member(java.text.DecimalFormat.class, "<init>"),
          member(java.text.DecimalFormat.class, "<init>", String.class),
          member(java.text.SimpleDateFormat.class, "<init>"),
          member(java.text.SimpleDateFormat.class, "<init>", String.class),
          member(java.time.format.DateTimeFormatter.class, "ofPattern", String.class));

  private static ArchCondition<JavaClass> notFormatWithTheDefaultLocale() {
    return new ArchCondition<JavaClass>("not format with the JVM default locale") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        List<JavaAccess<?>> accesses = new ArrayList<>();
        accesses.addAll(javaClass.getMethodCallsFromSelf());
        accesses.addAll(javaClass.getConstructorCallsFromSelf());
        accesses.addAll(javaClass.getMethodReferencesFromSelf());
        accesses.addAll(javaClass.getConstructorReferencesFromSelf());
        for (JavaAccess<?> access : accesses) {
          if (!(access.getTarget() instanceof CodeUnitAccessTarget target)) {
            continue;
          }
          List<String> params = new ArrayList<>();
          for (JavaClass p : target.getRawParameterTypes()) {
            params.add(p.getName());
          }
          boolean forbidden = false;
          for (DefaultLocaleMember m : DEFAULT_LOCALE_MEMBERS) {
            if (m.matches(target.getOwner(), target.getName(), params)) {
              forbidden = true;
              break;
            }
          }
          // Any java.util.Formatter constructor without a Locale parameter
          if (!forbidden
              && target.getName().equals("<init>")
              && target.getOwner().isAssignableTo(java.util.Formatter.class)
              && !params.contains(java.util.Locale.class.getName())) {
            forbidden = true;
          }
          if (forbidden) {
            events.add(SimpleConditionEvent.violated(javaClass, access.getDescription()));
          }
        }
      }
    };
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

  /**
   * Rule (f)'s condition: reports an api class whose public or protected signatures — supertype,
   * interfaces, field types, method and constructor parameters, returns and throws clauses, generic
   * arguments included via raw-type traversal — reference a type from the forbidden packages.
   * Bodies are not inspected: an api factory may construct an impl class behind the api type
   * (HeapDumpParser.parse builds a HeapDumpImpl behind HeapDump), so a plain dependency rule would
   * be too strict.
   */
  private static ArchCondition<JavaClass> notExposeImplementationTypesInPublicSignatures(
      List<String> forbiddenPackages) {
    DescribedPredicate<JavaClass> forbidden =
        Predicates.resideInAnyPackage(forbiddenPackages.toArray(new String[0]));
    return new ArchCondition<>(
        "not expose "
            + String.join(", ", forbiddenPackages)
            + " types in public or protected signatures") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        List<String> leaks = new ArrayList<>();
        javaClass
            .getRawSuperclass()
            .ifPresent(
                superclass -> {
                  if (!"java.lang.Object".equals(superclass.getName())
                      && forbidden.test(superclass)) {
                    leaks.add("extends " + superclass.getName());
                  }
                });
        for (JavaClass iface : javaClass.getRawInterfaces()) {
          if (forbidden.test(iface)) {
            leaks.add("implements " + iface.getName());
          }
        }
        for (JavaField field : javaClass.getFields()) {
          if (visible(field) && !synthetic(field)) {
            field
                .getType()
                .getAllInvolvedRawTypes()
                .forEach(
                    type -> {
                      if (forbidden.test(type)) {
                        leaks.add("field " + field.getName() + " : " + type.getName());
                      }
                    });
          }
        }
        for (JavaMethod method : javaClass.getMethods()) {
          if (visible(method) && !synthetic(method)) {
            collectCodeUnitLeaks(method, forbidden, leaks);
          }
        }
        for (JavaConstructor constructor : javaClass.getConstructors()) {
          if (visible(constructor) && !synthetic(constructor)) {
            collectCodeUnitLeaks(constructor, forbidden, leaks);
          }
        }
        if (!leaks.isEmpty()) {
          // Sorted: frozen store lines must be byte-stable across JVM runs, and ArchUnit's
          // getMethods()/getFields() return unordered sets — their iteration order is not.
          leaks.sort(String::compareTo);
          events.add(
              new SimpleConditionEvent(
                  javaClass,
                  false,
                  String.format("%s leaks implementation types: %s", javaClass.getName(), leaks)));
        }
      }
    };
  }

  private static void collectCodeUnitLeaks(
      JavaCodeUnit unit, DescribedPredicate<JavaClass> forbidden, List<String> leaks) {
    unit.getParameters()
        .forEach(
            parameter ->
                parameter
                    .getType()
                    .getAllInvolvedRawTypes()
                    .forEach(
                        type -> {
                          if (forbidden.test(type)) {
                            leaks.add(
                                "parameter of " + unit.getName() + "(..) : " + type.getName());
                          }
                        }));
    if (unit instanceof JavaMethod) {
      ((JavaMethod) unit)
          .getReturnType()
          .getAllInvolvedRawTypes()
          .forEach(
              type -> {
                if (forbidden.test(type)) {
                  leaks.add("return type of " + unit.getName() + "(..) : " + type.getName());
                }
              });
    }
    unit.getThrowsClause()
        .getTypes()
        .forEach(
            type -> {
              if (forbidden.test(type)) {
                leaks.add("throws of " + unit.getName() + "(..) : " + type.getName());
              }
            });
  }

  private static boolean visible(JavaMember member) {
    return member.getModifiers().contains(JavaModifier.PUBLIC)
        || member.getModifiers().contains(JavaModifier.PROTECTED);
  }

  private static boolean synthetic(JavaMember member) {
    return member.getModifiers().contains(JavaModifier.SYNTHETIC);
  }
}

package io.jafar.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helper that flags api classes whose <em>signatures</em> leak implementation types.
 *
 * <p>Factory method bodies may construct impl classes (that is the accepted pattern: {@code
 * HeapDumpParser.parse} builds a {@code HeapDumpImpl} behind the api type), so a plain dependency
 * rule would be too strict. This condition walks only the visible shape of a class: superclass,
 * interfaces, public/protected fields, and public/protected method and constructor parameter,
 * return and throws types — generic arguments included, via raw-type traversal.
 */
final class SignatureLeaks {

  private SignatureLeaks() {}

  static ArchCondition<JavaClass> notExposeInPublicSignatures(String... forbiddenPackages) {
    DescribedPredicate<JavaClass> forbidden =
        JavaClass.Predicates.resideInAnyPackage(forbiddenPackages);
    return new ArchCondition<>(
        "not expose types from "
            + Arrays.toString(forbiddenPackages)
            + " in public or protected signatures") {
      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        List<String> leaks = new ArrayList<>();

        clazz
            .getRawSuperclass()
            .ifPresent(
                sc -> {
                  if (!"java.lang.Object".equals(sc.getName()) && forbidden.test(sc)) {
                    leaks.add("extends " + sc.getName());
                  }
                });
        for (JavaClass iface : clazz.getRawInterfaces()) {
          if (forbidden.test(iface)) {
            leaks.add("implements " + iface.getName());
          }
        }
        for (JavaField field : clazz.getFields()) {
          if (visible(field) && !synthetic(field)) {
            field
                .getType()
                .getAllInvolvedRawTypes()
                .forEach(
                    t -> {
                      if (forbidden.test(t)) {
                        leaks.add("field " + field.getName() + " : " + t.getName());
                      }
                    });
          }
        }
        for (JavaMethod method : clazz.getMethods()) {
          if (visible(method) && !synthetic(method)) {
            method
                .getParameters()
                .forEach(
                    p ->
                        p.getType()
                            .getAllInvolvedRawTypes()
                            .forEach(
                                t -> {
                                  if (forbidden.test(t)) {
                                    leaks.add(
                                        "parameter of "
                                            + method.getName()
                                            + "(..) : "
                                            + t.getName());
                                  }
                                }));
            method
                .getReturnType()
                .getAllInvolvedRawTypes()
                .forEach(
                    t -> {
                      if (forbidden.test(t)) {
                        leaks.add("return type of " + method.getName() + "(..) : " + t.getName());
                      }
                    });
            method
                .getThrowsClause()
                .getTypes()
                .forEach(
                    t -> {
                      if (forbidden.test(t)) {
                        leaks.add("throws of " + method.getName() + "(..) : " + t.getName());
                      }
                    });
          }
        }
        for (JavaConstructor ctor : clazz.getConstructors()) {
          if (visible(ctor) && !synthetic(ctor)) {
            ctor.getParameters()
                .forEach(
                    p ->
                        p.getType()
                            .getAllInvolvedRawTypes()
                            .forEach(
                                t -> {
                                  if (forbidden.test(t)) {
                                    leaks.add(
                                        "parameter of " + ctor.getName() + "(..) : " + t.getName());
                                  }
                                }));
            ctor.getThrowsClause()
                .getTypes()
                .forEach(
                    t -> {
                      if (forbidden.test(t)) {
                        leaks.add("throws of " + ctor.getName() + " : " + t.getName());
                      }
                    });
          }
        }

        if (!leaks.isEmpty()) {
          events.add(
              new SimpleConditionEvent(
                  clazz,
                  false,
                  String.format("%s leaks implementation types: %s", clazz.getName(), leaks)));
        }
      }
    };
  }

  private static boolean visible(JavaMember member) {
    return member.getModifiers().contains(JavaModifier.PUBLIC)
        || member.getModifiers().contains(JavaModifier.PROTECTED);
  }

  private static boolean synthetic(JavaMember member) {
    return member.getModifiers().contains(JavaModifier.SYNTHETIC);
  }
}

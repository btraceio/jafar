package io.jafar.parser.impl.archfixture;

/**
 * Fire-test violator for rule (e) (see ArchRuleFireTest): ArchRuleFireTest maps this class into the
 * parser-core module, and this static non-final field is exactly the mutable static state rule (e)
 * forbids in parser implementation packages.
 *
 * <p>The field is never read; its declaration alone is the violation. The archfixture subpackage
 * keeps the violator out of the real implementation sources; only the fire tests place it in a
 * module's class set.
 */
public final class StaticCounterFixture {
  static int counter;
}

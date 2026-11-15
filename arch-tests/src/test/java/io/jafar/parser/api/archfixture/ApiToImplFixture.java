package io.jafar.parser.api.archfixture;

import io.jafar.parser.impl.ParsingContextImpl;

/**
 * Fire-test violator for rule (c) (see ArchRuleFireTest): ArchRuleFireTest maps this class into the
 * parser-core module, so this api-package class depends on an existing {@code io.jafar.parser.impl}
 * class — the within-module direction rule (c) forbids.
 *
 * <p>The field is never read; the field descriptor alone is the violation. The archfixture
 * subpackage keeps the violator out of the real api sources; only the fire tests place it in a
 * module's class set.
 */
public final class ApiToImplFixture {
  private ParsingContextImpl reachIn = null;
}

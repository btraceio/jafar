package io.jafar.arch.fixture;

import io.jafar.parser.impl.ParsingContextImpl;

/**
 * Fire-test violator for rule (a) (see ArchRuleFireTest): ArchRuleFireTest maps this class into the
 * jfr-shell module (a consumer), and the field's type creates the dependency on the existing
 * io.jafar.parser.impl class that rule (a) forbids.
 *
 * <p>The field is never read; the field descriptor alone is the violation. This class is never
 * imported by the rules as part of any real module — only the fire tests place it there.
 */
public final class ReachIntoParserImplFixture {
  private ParsingContextImpl reachIn = null;
}

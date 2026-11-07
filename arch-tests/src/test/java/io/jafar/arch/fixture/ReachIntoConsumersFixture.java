package io.jafar.arch.fixture;

import io.jafar.tools.Scrubber;

/**
 * Fire-test violator for rule (d) (see ArchRuleFireTest): ArchRuleFireTest maps this class into the
 * shell-core module, and the field's type creates the dependency on the tools module (a consumer)
 * that rule (d) forbids from shell-core and the parser modules.
 *
 * <p>The field is never read; the field descriptor alone is the violation. This class is never
 * imported by the rules as part of any real module — only the fire tests place it there.
 */
public final class ReachIntoConsumersFixture {
  private Scrubber reachIn = null;
}

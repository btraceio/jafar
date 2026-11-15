package io.jafar.parser.api.archfixture;

import io.jafar.parser.impl.ParsingContextImpl;

/**
 * Fire-test violator for rule (f) (see ArchRuleFireTest): ArchRuleFireTest maps this class into the
 * parser-core module, so this api-package class exposes an existing {@code io.jafar.parser.impl}
 * type in a public signature — the signature-level direction rule (f) forbids. Unlike {@link
 * ApiToImplFixture}, whose violation is the field descriptor alone (a dependency), the violation
 * here is the public method's return type.
 *
 * <p>The method never returns anything but null; the signature alone is the violation. The
 * archfixture subpackage keeps the violator out of the real api sources; only the fire tests place
 * it in a module's class set.
 */
public final class ApiSignatureLeakFixture {
  public ParsingContextImpl leak() {
    return null;
  }
}

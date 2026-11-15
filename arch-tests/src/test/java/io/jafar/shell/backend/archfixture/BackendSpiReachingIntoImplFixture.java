package io.jafar.shell.backend.archfixture;

import io.jafar.shell.backend.impl.JafarBackend;

/**
 * Fire-test violator for rule (i) (see ArchRuleFireTest): ArchRuleFireTest maps this class into the
 * shell-core module, so this backend-SPI-package class (outside {@code ..backend.impl..}) depends
 * on a real backend implementation class — a hard reference where discovery is the architecture.
 *
 * <p>The field is never read; the field descriptor alone is the violation. The archfixture
 * subpackage keeps the violator out of the real SPI sources; only the fire tests place it in a
 * module's class set.
 */
public final class BackendSpiReachingIntoImplFixture {
  private JafarBackend reachIn = null;
}

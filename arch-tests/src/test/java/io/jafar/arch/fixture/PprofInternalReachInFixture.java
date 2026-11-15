package io.jafar.arch.fixture;

import io.jafar.pprof.internal.PprofReader;

/**
 * Fire-test violator for rule (g) (see ArchRuleFireTest): ArchRuleFireTest maps this class into the
 * jfr-shell module — a consumer, so outside pprof-parser by membership — and it reaches into {@code
 * io.jafar.pprof.internal}, which is exactly what rule (g) forbids.
 *
 * <p>The field is never read; the field descriptor alone is the violation.
 */
public final class PprofInternalReachInFixture {
  private PprofReader reachIn = null;
}

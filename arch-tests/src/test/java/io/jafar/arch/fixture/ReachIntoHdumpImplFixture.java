package io.jafar.arch.fixture;

import io.jafar.hdump.impl.HeapDumpImpl;

/**
 * Fire-test violator for rule (b) (see ArchRuleFireTest): ArchRuleFireTest maps this class into the
 * pprof-parser module, and the field's type creates the dependency on the existing
 * io.jafar.hdump.impl class that rule (b) forbids outside hdump-parser.
 *
 * <p>The field is never read; the field descriptor alone is the violation. This class is never
 * imported by the rules as part of any real module — only the fire tests place it there.
 */
public final class ReachIntoHdumpImplFixture {
  private HeapDumpImpl reachIn = null;
}

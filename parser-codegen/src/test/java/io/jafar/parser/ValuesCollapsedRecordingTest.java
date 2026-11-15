package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

/**
 * Issue 108 against a real recording: constant-pool references to simple types collapse to their
 * value, decided by the recording's metadata, and nothing else collapses.
 */
class ValuesCollapsedRecordingTest {
  private static final Path RECORDING =
      Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  @Test
  void collapsesExactlyTheSimpleTypeReferences() throws Exception {
    LongAdder simpleSeen = new LongAdder();
    LongAdder structSeen = new LongAdder();
    Map<String, LongAdder> failures = new ConcurrentHashMap<>();

    try (UntypedJafarParser p = ParsingContext.create().newUntypedParser(RECORDING)) {
      p.handle(
          (type, event, ctl) -> {
            Map<String, Object> deep = Values.resolvedDeep(event);
            Map<String, Object> collapsed = Values.resolvedDeepCollapsed(event);
            for (Map.Entry<String, Object> e : event.entrySet()) {
              if (!(e.getValue() instanceof ComplexType)) {
                continue;
              }
              ComplexType ct = (ComplexType) e.getValue();
              Object d = deep.get(e.getKey());
              Object c = collapsed.get(e.getKey());
              if (ct.isSimpleType() && d instanceof Map && ((Map<?, ?>) d).size() == 1) {
                simpleSeen.increment();
                Object only = ((Map<?, ?>) d).values().iterator().next();
                // The wrapper's single value, itself collapsed if it was a reference.
                if (c instanceof Map
                    || (!(only instanceof Map)
                        && !String.valueOf(only).equals(String.valueOf(c)))) {
                  failures
                      .computeIfAbsent(type.getName() + "." + e.getKey(), k -> new LongAdder())
                      .increment();
                }
              } else if (d instanceof Map) {
                structSeen.increment();
                if (!(c instanceof Map)) {
                  failures
                      .computeIfAbsent(type.getName() + "." + e.getKey(), k -> new LongAdder())
                      .increment();
                }
              }
            }
          });
      p.run();
    }

    assertTrue(failures.isEmpty(), "wrong shape for: " + failures);
    assertTrue(simpleSeen.sum() > 0, "recording has no simple-type references to test");
    assertTrue(structSeen.sum() > 0, "recording has no struct references to keep");
  }

  @Test
  void rawShapeIsStillTheWrapper() throws Exception {
    // The additive guarantee: getValue() and resolvedDeep() are unchanged, so existing consumers
    // keep getting the one-entry wrapper.
    LongAdder wrappers = new LongAdder();
    Map<String, LongAdder> odd = new ConcurrentHashMap<>();
    try (UntypedJafarParser p = ParsingContext.create().newUntypedParser(RECORDING)) {
      p.handle(
          (type, event, ctl) -> {
            for (Map.Entry<String, Object> e : event.entrySet()) {
              Object v = e.getValue();
              if (v instanceof ComplexType && ((ComplexType) v).isSimpleType()) {
                Map<String, Object> w = ((ComplexType) v).getValue();
                if (w == null || w.size() != 1) {
                  odd.computeIfAbsent(
                          type.getName() + "." + e.getKey() + "=" + w, k -> new LongAdder())
                      .increment();
                } else {
                  wrappers.increment();
                }
              }
            }
          });
      p.run();
    }
    assertTrue(wrappers.sum() > 0);
    // A reference whose pool entry is absent resolves to null (e.g. datadog.* _dd.trace.operation);
    // it is the only way a simple-type reference lacks its wrapper.
    odd.forEach((k, n) -> assertTrue(k.endsWith("=null"), "unexpected wrapper shape: " + k));
  }
}

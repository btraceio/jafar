package io.jafar.parser.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.impl.UntypedJafarParserImpl;
import io.jafar.parser.types.JFRGCHeapSummary;
import io.jafar.testsupport.work.WorkBudgets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Work budgets of the entry points parser-codegen serves: the untyped parse read with the generated
 * reader ({@code EventStreamGenerated}, the default when parser-codegen is on the classpath) and
 * the typed parse. Same agent, budget file and three-identical-runs rule as parser-core's {@code
 * UntypedWorkBudgetTest}.
 *
 * <p>The classes parser-codegen defines at runtime (one deserializer per event type) carry no
 * probe: their names are per type and per run. Their work is counted at the stable methods around
 * them — {@code events.decoded} at the call that runs a generated deserializer, {@code
 * varints.read} inside {@code RecordingStream.readVarint}, which generated code calls like any
 * other.
 */
class GeneratedWorkBudgetTest {

  private static final Path TCK = Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");
  private static final int TCK_EVENTS = 67657;
  private static final String FILTERED_TYPE = "jdk.GCHeapSummary";
  private static final int FILTERED_EVENTS = 1892;

  @Test
  void untyped() throws Exception {
    WorkBudgets.measureAndCheck(
        "parser-codegen.untyped.tck-test.jfr",
        () -> {
          AtomicInteger events = new AtomicInteger();
          try (UntypedJafarParser parser = parser()) {
            parser.handle((type, value, ctl) -> events.incrementAndGet());
            parser.run();
          }
          assertEquals(TCK_EVENTS, events.get(), "tck-test.jfr event count changed");
        });
  }

  @Test
  void untypedFiltered() throws Exception {
    WorkBudgets.measureAndCheck(
        "parser-codegen.untyped-filtered.tck-test.jfr",
        () -> {
          AtomicInteger events = new AtomicInteger();
          try (UntypedJafarParser parser = parser()) {
            parser.handle(
                TypeFilter.named(FILTERED_TYPE), (type, value, ctl) -> events.incrementAndGet());
            parser.run();
          }
          assertEquals(FILTERED_EVENTS, events.get(), FILTERED_TYPE + " count changed");
        });
  }

  /**
   * The typed parse with one {@code jdk.GCHeapSummary} handler, as the typed allocation budget
   * measures it: only the registered type is decoded.
   */
  @Test
  void typed() throws Exception {
    WorkBudgets.measureAndCheck(
        "parser-codegen.typed.tck-test.jfr",
        () -> {
          AtomicInteger events = new AtomicInteger();
          try (TypedJafarParser parser = ParsingContext.create().newTypedParser(TCK)) {
            parser.handle(JFRGCHeapSummary.class, (event, ctl) -> events.incrementAndGet());
            parser.run();
          }
          assertEquals(FILTERED_EVENTS, events.get(), FILTERED_TYPE + " count changed");
        });
  }

  /** An untyped parser on a fresh context, asserted to read with the generated reader. */
  private static UntypedJafarParser parser() {
    UntypedJafarParser parser = ParsingContext.create().newUntypedParser(TCK);
    assertTrue(
        ((UntypedJafarParserImpl) parser).usesGeneratedReader(),
        "parse would use the generic reader, which parser-core budgets");
    return parser;
  }
}

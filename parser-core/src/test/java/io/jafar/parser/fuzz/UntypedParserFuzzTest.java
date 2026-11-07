package io.jafar.parser.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Jazzer fuzz target for the untyped JFR parser (AC-4.5, kb/a2-jazzer-jdk-floor).
 *
 * <p>Regression mode (the default, what {@code :parser-core:test} runs): Jazzer replays every file
 * committed under {@code src/test/resources/io/jafar/parser/fuzz/UntypedParserFuzzTestInputs/
 * parseUntyped/} — plus the empty input — as the {@code data} argument, as ordinary JUnit
 * parameters, without JAZZER_FUZZ set. (Jazzer still dynamically attaches its instrumentation agent
 * in this mode; its INFO lines on stderr are Jazzer's, not parser logging.) Setting {@code
 * JAZZER_FUZZ} to a non-empty value switches the same test to coverage-guided fuzzing; that is the
 * nightly job's business, never the PR run.
 *
 * <p>The crash oracle contract lives in {@link EscapeOracle}: only {@code io.jafar.*} exceptions
 * and {@code IOException} may escape the parse of a fuzz input; anything else, including every
 * {@code Error} anywhere in the cause chain and any hang beyond {@code jafar.fuzz.inputTimeoutMs},
 * fails the run naming the input.
 */
public class UntypedParserFuzzTest {

  @FuzzTest
  void parseUntyped(byte[] data) {
    check(data, parseBothStrategies(data));
  }

  /**
   * The fuzz target's own entry into the oracle. Also driven by {@code EscapeOracleTest} (AC-4.6)
   * to prove that a non-jafar throwable thrown anywhere inside the target's path fails the run
   * naming the input — the injected failure stands in for a parser bug.
   */
  static void check(byte[] data, EscapeOracle.CheckedAction action) {
    EscapeOracle.check(data, action);
  }

  /**
   * Writes the input to a temp file and parses it once per untyped strategy. The write is inside
   * the checked action on purpose: a failure while staging the input must fail the oracle too, not
   * be reported as an infrastructure error outside it.
   */
  static EscapeOracle.CheckedAction parseBothStrategies(byte[] data) {
    return () -> {
      Path recording = Files.createTempFile("jazzer-untyped", ".jfr");
      try {
        Files.write(recording, data);
        parse(recording, UntypedStrategy.SPARSE_ACCESS);
        parse(recording, UntypedStrategy.FULL_ITERATION);
      } finally {
        Files.deleteIfExists(recording);
      }
    };
  }

  private static void parse(Path recording, UntypedStrategy strategy) throws Exception {
    // Fresh context per parse: a fuzz finding must be reproducible from the input alone, so no
    // state may be shared between inputs or between the two strategies.
    ParsingContext ctx = ParsingContext.create();
    try (UntypedJafarParser parser = ctx.newUntypedParser(recording, strategy)) {
      parser.handle((type, event, ctl) -> {});
      parser.run();
    }
  }
}

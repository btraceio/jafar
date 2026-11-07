package io.jafar.parser.fuzz;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

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
 *
 * <p>Session bound (verified against jazzer-junit 0.30.0): a coverage-guided session runs until
 * libFuzzer's {@code -max_total_time} expires. {@code @FuzzTest(maxDuration)} defaults to {@code
 * "5m"} and is only a fallback — {@code jazzer.max.duration} (or {@code JAZZER_MAX_DURATION})
 * overrides the annotation, because Jazzer applies the annotation value only when the option is
 * still at its default. The build wires {@code -Pjafar.fuzz.maxDuration} (default {@code 5m}) to
 * that system property, so a session is bounded without editing this file; the annotation stays
 * bare at its default as a result.
 */
public class UntypedParserFuzzTest {

  @FuzzTest
  void parseUntyped(byte[] data) {
    check(data, parseBothStrategies(data));
  }

  /**
   * R9 wiring proof for the session bound (T38): {@code parser-core/build.gradle} sets {@code
   * -Djazzer.max.duration} from {@code -Pjafar.fuzz.maxDuration} on every test JVM, and Jazzer
   * consults it only in fuzzing mode — so the fuzz loop itself cannot prove the wiring (a
   * regression replay never reads the bound, and a fuzzing session is over in seconds if it finds a
   * crash, before the bound matters). This assertion is what fails if the wiring is removed: Jazzer
   * would silently fall back to the {@code @FuzzTest} default. It also refuses the two values that
   * would silently UNBOUND a session: an empty string (Jazzer treats it as unlimited) and a zero
   * duration (libFuzzer's {@code -max_total_time=0} means no limit) — R3, no fallback may hide a
   * misconfiguration.
   */
  @Test
  void sessionBoundIsWiredToTheTestJvm() {
    String bound = System.getProperty("jazzer.max.duration");
    assertNotNull(bound, "jazzer.max.duration must be set by the build (-Pjafar.fuzz.maxDuration)");
    // An empty string means "unlimited" to Jazzer, so it must fail here rather than silently
    // unbound a nightly session.
    assertTrue(!bound.isBlank(), "jazzer.max.duration must not be empty (Jazzer: unlimited)");
    // Mirror jazzer-junit 0.30.0's duration parse (Utils.durationStringToSeconds builds a
    // java.time.Duration from "PT" + the value with sec/min/hr and spaces normalized), so a
    // value Jazzer would reject fails HERE, in regression mode too, instead of only the
    // moment a nightly session starts.
    long seconds =
        Duration.parse(
                "PT"
                    + bound
                        .replace("sec", "s")
                        .replace("min", "m")
                        .replace("hr", "h")
                        .replace(" ", ""))
            .getSeconds();
    // libFuzzer's -max_total_time=0 means no limit, so a zero bound is the same silent unbound.
    assertTrue(
        seconds > 0,
        "jazzer.max.duration=" + bound + " must bound the session, not disable the bound");
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

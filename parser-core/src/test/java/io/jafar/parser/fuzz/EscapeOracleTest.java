package io.jafar.parser.fuzz;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.JafarParseException;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * Permanent fire tests for the fuzz oracle (AC-4.6): a corpus input or seeded harness condition
 * that makes a non-jafar throwable escape the parse fails the run naming the input. Every branch of
 * {@link EscapeOracle} (D4) is exercised here so a regression in the oracle itself cannot silently
 * downgrade a finding to a pass.
 */
class EscapeOracleTest {

  private static final long GENEROUS_TIMEOUT_MS = 10_000;

  private static final byte[] INPUT = "oracle-input".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OTHER_INPUT = "oracle-input-other".getBytes(StandardCharsets.UTF_8);

  private static final Map<String, String> NO_ESCAPES = Collections.emptyMap();
  private static final String IAE = IllegalArgumentException.class.getName();

  private static final String CORPUS_DIR = "UntypedParserFuzzTestInputs/parseUntyped/";

  private static String sha(byte[] data) {
    return EscapeOracle.sha256Hex(data);
  }

  @Test
  void iaeWrappedInIOExceptionFailsNamingTheInput() {
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                EscapeOracle.check(
                    INPUT,
                    () -> {
                      throw new IOException("wrapped", new IllegalArgumentException("boom"));
                    },
                    NO_ESCAPES,
                    GENEROUS_TIMEOUT_MS));
    assertTrue(failure.getMessage().contains(sha(INPUT)), failure.getMessage());
    assertTrue(
        failure.getMessage().contains("java.io.IOException <- java.lang.IllegalArgumentException"),
        failure.getMessage());
    assertTrue(failure.getMessage().contains("root cause"), failure.getMessage());
  }

  @Test
  void stackOverflowErrorNestedInTheChainFails() {
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                EscapeOracle.check(
                    INPUT,
                    () -> {
                      throw new JafarParseException(
                          "metadata recursion", new StackOverflowError("readSubelements"));
                    },
                    NO_ESCAPES,
                    GENEROUS_TIMEOUT_MS));
    assertTrue(failure.getMessage().contains(sha(INPUT)), failure.getMessage());
    assertTrue(failure.getMessage().contains("StackOverflowError"), failure.getMessage());
    assertTrue(failure.getMessage().contains("cause chain"), failure.getMessage());
  }

  @Test
  void outOfMemoryErrorFails() {
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                EscapeOracle.check(
                    INPUT,
                    () -> {
                      throw new OutOfMemoryError("allocation bomb from a corrupted length");
                    },
                    NO_ESCAPES,
                    GENEROUS_TIMEOUT_MS));
    assertTrue(failure.getMessage().contains(sha(INPUT)), failure.getMessage());
    assertTrue(failure.getMessage().contains("OutOfMemoryError"), failure.getMessage());
  }

  @Test
  void jafarParseExceptionPasses() {
    assertDoesNotThrow(
        () ->
            EscapeOracle.check(
                INPUT,
                () -> {
                  throw new JafarParseException("malformed recording");
                },
                NO_ESCAPES,
                GENEROUS_TIMEOUT_MS));
  }

  @Test
  void ioExceptionRootPasses() {
    assertDoesNotThrow(
        () ->
            EscapeOracle.check(
                INPUT,
                () -> {
                  throw new EOFException("truncated after the header");
                },
                NO_ESCAPES,
                GENEROUS_TIMEOUT_MS));
  }

  @Test
  void listedEscapePassesOnlyWithExactlyTheListedThrowable() {
    Map<String, String> listed = Map.of(sha(INPUT), IAE);
    assertDoesNotThrow(
        () ->
            EscapeOracle.check(
                INPUT,
                () -> {
                  throw new IllegalArgumentException("pinned");
                },
                listed,
                GENEROUS_TIMEOUT_MS));
    // Exactly that throwable: a different delivered class must still fail.
    AssertionError changed =
        assertThrows(
            AssertionError.class,
            () ->
                EscapeOracle.check(
                    INPUT,
                    () -> {
                      throw new StackOverflowError("not what the entry pins");
                    },
                    listed,
                    GENEROUS_TIMEOUT_MS));
    assertTrue(changed.getMessage().contains(sha(INPUT)), changed.getMessage());
    assertTrue(changed.getMessage().contains("listed as " + IAE), changed.getMessage());
  }

  @Test
  void staleListedEscapeFails() {
    AssertionError stale =
        assertThrows(
            AssertionError.class,
            () ->
                // The input parses cleanly now, so its allowlist entry can no longer fire.
                EscapeOracle.check(INPUT, () -> {}, Map.of(sha(INPUT), IAE), GENEROUS_TIMEOUT_MS));
    assertTrue(stale.getMessage().contains("stale known-escape " + sha(INPUT)), stale.getMessage());
    assertTrue(stale.getMessage().contains("remove the entry"), stale.getMessage());
  }

  @Test
  void legalEscapeAlsoCountsAsStaleForAListedInput() {
    // A listed input that now delivers a LEGAL throwable (io.jafar.*/IOException) is just as
    // stale: the entry exists to excuse a verdict that no longer happens.
    AssertionError stale =
        assertThrows(
            AssertionError.class,
            () ->
                EscapeOracle.check(
                    INPUT,
                    () -> {
                      throw new JafarParseException("now handled properly");
                    },
                    Map.of(sha(INPUT), IAE),
                    GENEROUS_TIMEOUT_MS));
    assertTrue(stale.getMessage().contains("stale known-escape " + sha(INPUT)), stale.getMessage());
  }

  @Test
  void neverReleasedLatchFailsAsHang() {
    CountDownLatch never = new CountDownLatch(1);
    long start = System.nanoTime();
    AssertionError hang =
        assertThrows(
            AssertionError.class,
            () -> EscapeOracle.check(INPUT, () -> never.await(), NO_ESCAPES, 100));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertTrue(hang.getMessage().contains("hang"), hang.getMessage());
    assertTrue(hang.getMessage().contains(sha(INPUT)), hang.getMessage());
    // The bound must actually bound: a hang test that takes the default 10 s to fail would
    // make the oracle itself the slowest thing in the suite.
    assertTrue(elapsedMs < 5_000, "hang detection took " + elapsedMs + " ms");
  }

  @Test
  void injectedIaeThroughTheFuzzTestsOwnPathFailsNamingTheInput() {
    // Drive the fuzz target's real entry into the oracle (UntypedParserFuzzTest.check), not the
    // oracle unit: an IAE injected where the parser would run must fail the run naming the input.
    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                UntypedParserFuzzTest.check(
                    OTHER_INPUT,
                    () -> {
                      throw new IllegalArgumentException("injected where the parser runs");
                    }));
    assertTrue(failure.getMessage().contains(sha(OTHER_INPUT)), failure.getMessage());
    assertTrue(failure.getMessage().contains(IAE), failure.getMessage());
  }

  @Test
  void committedAllowlistIsWellFormedAndCoversCommittedCorpusInputs() {
    // Guards the resource path and the parser of the committed file: a missing file is a
    // misconfiguration, not an empty list, and a malformed entry line fails the load itself
    // (R3). The allowlist stopped being empty when the adversarial recordings (T25) pinned the
    // first known escapes, so the invariant now is the file header's corpus-file rule: every
    // listed sha256 must be one of the committed fuzz corpus inputs — a pin may only ever
    // excuse a corpus input, never an arbitrary sha.
    Map<String, String> escapes = EscapeOracle.loadKnownEscapes();
    Set<String> corpus = committedCorpusShas();
    for (String sha : escapes.keySet()) {
      assertTrue(
          corpus.contains(sha),
          "known-escape "
              + sha
              + " does not match any input committed under "
              + CORPUS_DIR
              + "; a pin may only excuse a committed corpus input");
    }
  }

  /**
   * sha256 of every input committed under the Jazzer regression corpus (T24's {@code adv-*} seeds).
   */
  private static Set<String> committedCorpusShas() {
    try {
      URL dir = EscapeOracle.class.getResource(CORPUS_DIR);
      if (dir == null) {
        throw new AssertionError(
            "fuzz corpus directory " + CORPUS_DIR + " is not on the test classpath");
      }
      File[] seeds = new File(dir.toURI()).listFiles();
      if (seeds == null || seeds.length == 0) {
        throw new AssertionError(
            "fuzz corpus directory " + CORPUS_DIR + " holds no inputs a pin could cover");
      }
      Set<String> shas = new HashSet<>();
      for (File seed : seeds) {
        shas.add(EscapeOracle.sha256Hex(Files.readAllBytes(seed.toPath())));
      }
      return shas;
    } catch (IOException | java.net.URISyntaxException e) {
      throw new AssertionError("failed to read the fuzz corpus under " + CORPUS_DIR, e);
    }
  }

  @Test
  void timeoutDefaultsToTenSecondsAndIsOverridable() {
    String property = "jafar.fuzz.inputTimeoutMs";
    String original = System.getProperty(property);
    try {
      System.clearProperty(property);
      assertEquals(10_000L, EscapeOracle.timeoutMs());
      System.setProperty(property, "2500");
      assertEquals(2500L, EscapeOracle.timeoutMs());
    } finally {
      if (original == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, original);
      }
    }
  }
}
